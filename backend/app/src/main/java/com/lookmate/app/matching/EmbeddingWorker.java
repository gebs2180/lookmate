package com.lookmate.app.matching;

import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.matching.CandidateQueries.PendingImage;
import com.lookmate.app.matching.EmbeddingStore.Subject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Embeds images for the current model: READY portfolio photos in batches claimed with {@code FOR UPDATE SKIP LOCKED}
 * (so several app instances share the work without embedding a photo twice), and one request's inspiration photo at
 * a time for the {@link Ranker}. A portfolio photo that fails backs off and, after {@code max-attempts}, is left alone.
 */
@Component
class EmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingWorker.class);

    private final CandidateQueries queries;
    private final EmbeddingStore store;
    private final EmbeddingProvider provider;
    private final ImageSource images;
    private final MatchRequests requests;
    private final MatchingProperties.Worker settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    EmbeddingWorker(CandidateQueries queries, EmbeddingStore store, EmbeddingProvider provider, ImageSource images, MatchRequests requests,
                    MatchingProperties properties, TransactionTemplate tx, Optional<Clock> clock) {
        this.queries = queries;
        this.store = store;
        this.provider = provider;
        this.images = images;
        this.requests = requests;
        this.settings = properties.worker();
        this.tx = tx;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * One batch of portfolio photos. Returns how many it embedded: 0 when there was nothing to do or everything failed,
     * so a pass doesn't spin on photos that keep failing.
     */
    int portfolioBatch() {
        Integer n = tx.execute(s -> {
            Instant now = clock.instant();
            List<PendingImage> claimed = queries.claimPortfolioPhotosToEmbed(provider.model(), now, settings.maxAttempts(), settings.batchSize());
            return embedPortfolio(claimed, now);
        });
        return n == null ? 0 : n;
    }

    private int embedPortfolio(List<PendingImage> claimed, Instant now) {
        List<PendingImage> read = new ArrayList<>();
        List<EmbeddingProvider.Image> bytes = new ArrayList<>();
        for (PendingImage p : claimed) {
            try {
                bytes.add(new EmbeddingProvider.Image(images.read(p.objectKey()), p.contentType()));
                read.add(p);
            } catch (RuntimeException e) {
                failed(Subject.PORTFOLIO_PHOTO, p.id(), "read: " + e.getClass().getSimpleName(), now);
            }
        }
        if (read.isEmpty()) {
            return 0;
        }
        try {
            List<float[]> vectors = provider.embedBatch(bytes);
            if (vectors.size() != read.size()) {
                throw new IllegalStateException("provider returned " + vectors.size() + " vectors for " + read.size() + " images");
            }
            for (int i = 0; i < read.size(); i++) {
                store.save(Subject.PORTFOLIO_PHOTO, read.get(i).id(), provider.model(), vectors.get(i), now);
            }
            return read.size();
        } catch (RuntimeException batchFailure) {
            // One bad photo shouldn't hold up the rest: try them one by one.
            log.warn("Embedding batch of {} failed ({}); trying one by one", read.size(), batchFailure.getClass().getSimpleName());
            int saved = 0;
            for (int i = 0; i < read.size(); i++) {
                try {
                    store.save(Subject.PORTFOLIO_PHOTO, read.get(i).id(), provider.model(),
                            provider.embed(bytes.get(i).bytes(), bytes.get(i).contentType()), now);
                    saved++;
                } catch (RuntimeException e) {
                    failed(Subject.PORTFOLIO_PHOTO, read.get(i).id(), "embed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), now);
                }
            }
            return saved;
        }
    }

    /** Whether the request's inspiration photo has an embedding for the current model. */
    boolean hasInspiration(UUID requestId) {
        return store.has(Subject.MATCH_REQUEST, requestId, provider.model());
    }

    /**
     * Embeds a claimed request's inspiration photo (bytes from intake, metadata already stripped). Returns the attempts
     * so far if it failed, or 0 if it's embedded.
     */
    int embedInspiration(UUID requestId) {
        Instant now = clock.instant();
        try {
            MatchRequests.Inspiration photo = requests.inspirationBytes(requestId)
                    .orElseThrow(() -> new IllegalStateException("no inspiration photo (not uploaded, or deleted)"));
            store.save(Subject.MATCH_REQUEST, requestId, provider.model(), provider.embed(photo.bytes(), photo.contentType()), now);
            return 0;
        } catch (RuntimeException e) {
            return failed(Subject.MATCH_REQUEST, requestId, e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }
    }

    private int failed(Subject subject, UUID id, String error, Instant now) {
        int attempts = store.failed(subject, id, provider.model(), error, settings.retryBackoff(), now);
        log.warn("Could not embed {} {} (attempt {}): {}", subject, id, attempts, error);
        return attempts;
    }

    int maxAttempts() {
        return settings.maxAttempts();
    }
}
