package com.lookmate.app.matching;

import com.lookmate.app.matching.CandidateQueries.PendingImage;
import com.lookmate.app.matching.EmbeddingStore.Subject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Embeds READY portfolio photos and the inspiration photos of requests waiting to be matched, for the current model.
 * Each batch is claimed with {@code FOR UPDATE SKIP LOCKED} in its own transaction, so several app instances share
 * the work without embedding an image twice. An image that fails backs off and, after {@code max-attempts}, is left
 * alone; a request whose inspiration photo can't be embedded becomes FAILED for the admin to see.
 */
@Component
class EmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingWorker.class);

    private final CandidateQueries queries;
    private final EmbeddingStore store;
    private final RequestTransitions transitions;
    private final EmbeddingProvider provider;
    private final ImageSource images;
    private final MatchingProperties.Worker settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    EmbeddingWorker(CandidateQueries queries, EmbeddingStore store, RequestTransitions transitions, EmbeddingProvider provider,
                    ImageSource images, MatchingProperties properties, TransactionTemplate tx, java.util.Optional<Clock> clock) {
        this.queries = queries;
        this.store = store;
        this.transitions = transitions;
        this.provider = provider;
        this.images = images;
        this.settings = properties.worker();
        this.tx = tx;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * One batch of portfolio photos. Returns how many it embedded: 0 when there was nothing to do or everything failed,
     * so a pass doesn't spin on images that keep failing.
     */
    int portfolioBatch() {
        Integer n = tx.execute(s -> {
            Instant now = clock.instant();
            List<PendingImage> claimed = queries.claimPortfolioPhotosToEmbed(provider.model(), now, settings.maxAttempts(), settings.batchSize());
            return embed(Subject.PORTFOLIO_PHOTO, claimed, now);
        });
        return n == null ? 0 : n;
    }

    /** One batch of inspiration photos. */
    int inspirationBatch() {
        Integer n = tx.execute(s -> {
            Instant now = clock.instant();
            List<PendingImage> claimed = queries.claimInspirationPhotosToEmbed(provider.model(), now, settings.maxAttempts(), settings.batchSize());
            return embed(Subject.MATCH_REQUEST, claimed, now);
        });
        return n == null ? 0 : n;
    }

    private int embed(Subject subject, List<PendingImage> claimed, Instant now) {
        if (claimed.isEmpty()) {
            return 0;
        }
        List<PendingImage> read = new ArrayList<>();
        List<EmbeddingProvider.Image> bytes = new ArrayList<>();
        for (PendingImage p : claimed) {
            try {
                bytes.add(new EmbeddingProvider.Image(images.read(p.objectKey()), p.contentType()));
                read.add(p);
            } catch (RuntimeException e) {
                failed(subject, p, "read: " + e.getClass().getSimpleName(), now);
            }
        }
        if (read.isEmpty()) {
            return 0;
        }
        List<float[]> vectors;
        try {
            vectors = provider.embedBatch(bytes);
            if (vectors.size() != read.size()) {
                throw new IllegalStateException("provider returned " + vectors.size() + " vectors for " + read.size() + " images");
            }
        } catch (RuntimeException batchFailure) {
            // One bad image shouldn't hold up the rest: try them one by one.
            log.warn("Embedding batch of {} failed ({}); trying one by one", read.size(), batchFailure.getClass().getSimpleName());
            int saved = 0;
            for (int i = 0; i < read.size(); i++) {
                try {
                    store.save(subject, read.get(i).id(), provider.model(), provider.embed(bytes.get(i).bytes(), bytes.get(i).contentType()), now);
                    saved++;
                } catch (RuntimeException e) {
                    failed(subject, read.get(i), "embed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), now);
                }
            }
            return saved;
        }
        for (int i = 0; i < read.size(); i++) {
            store.save(subject, read.get(i).id(), provider.model(), vectors.get(i), now);
        }
        return read.size();
    }

    private void failed(Subject subject, PendingImage p, String error, Instant now) {
        int attempts = store.failed(subject, p.id(), provider.model(), error, settings.retryBackoff(), now);
        log.warn("Could not embed {} {} (attempt {}): {}", subject, p.id(), attempts, error);
        if (subject == Subject.MATCH_REQUEST && attempts >= settings.maxAttempts()) {
            transitions.failed(p.id(), "The inspiration photo could not be read for matching", now);
        }
    }
}
