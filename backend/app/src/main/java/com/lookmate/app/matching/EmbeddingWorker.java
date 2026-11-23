package com.lookmate.app.matching;

import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.matching.CandidateQueries.PendingImage;
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
 * Provider calls run outside any transaction.
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
     *
     * <p>The claim is a short transaction that also writes a lease ({@code claim-lease}) for each photo, so the
     * provider call holds no row locks or connection, and no other pass takes the photos meanwhile. Each photo is then
     * saved on its own: a photo that can't be saved is counted as failed and backs off, without undoing the others or
     * being claimed again straight away (review #8).
     */
    int portfolioBatch() {
        String model = provider.model();
        List<PendingImage> claimed = tx.execute(s -> {
            Instant now = clock.instant();
            List<PendingImage> rows = queries.claimPortfolioPhotosToEmbed(model, now, settings.maxAttempts(), settings.batchSize());
            rows.forEach(p -> store.lease(p.id(), model, now.plus(settings.claimLease()), now));
            return rows;
        });
        return claimed == null || claimed.isEmpty() ? 0 : embedPortfolio(claimed, model);
    }

    private int embedPortfolio(List<PendingImage> claimed, String model) {
        List<PendingImage> read = new ArrayList<>();
        List<EmbeddingProvider.Image> bytes = new ArrayList<>();
        for (PendingImage p : claimed) {
            try {
                bytes.add(new EmbeddingProvider.Image(images.read(p.objectKey()), p.contentType()));
                read.add(p);
            } catch (RuntimeException e) {
                failedPortfolio(p.id(), model, "read: " + e.getClass().getSimpleName());
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
            // One bad photo shouldn't hold up the rest: try them one by one.
            log.warn("Embedding batch of {} failed ({}); trying one by one", read.size(), batchFailure.getClass().getSimpleName());
            vectors = new ArrayList<>();
            for (int i = 0; i < read.size(); i++) {
                try {
                    vectors.add(provider.embed(bytes.get(i).bytes(), bytes.get(i).contentType()));
                } catch (RuntimeException e) {
                    vectors.add(null);
                    failedPortfolio(read.get(i).id(), model, "embed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }
        int saved = 0;
        for (int i = 0; i < read.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null) {
                continue;
            }
            UUID id = read.get(i).id();
            try {
                tx.executeWithoutResult(s -> store.savePortfolio(id, model, vector, clock.instant()));
                saved++;
            } catch (RuntimeException e) {
                failedPortfolio(id, model, "save: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return saved;
    }

    /** Whether the request's current inspiration photo (after any retake) has an embedding for the current model. */
    boolean hasInspiration(UUID requestId) {
        return queries.inspirationEmbedded(requestId, provider.model());
    }

    /**
     * Embeds a claimed request's inspiration photo (bytes from intake, metadata already stripped). Returns the tries so
     * far at embedding this photo if it failed, or 0 if it's embedded.
     */
    int embedInspiration(UUID requestId) {
        try {
            MatchRequests.Inspiration photo = requests.inspirationBytes(requestId)
                    .orElseThrow(() -> new IllegalStateException("no inspiration photo (not uploaded, or deleted)"));
            store.saveInspiration(requestId, provider.model(), provider.embed(photo.bytes(), photo.contentType()), photo.sha256(), clock.instant());
            return 0;
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            int attempts = store.failedRequest(requestId, EmbeddingStore.Step.EMBED, error, clock.instant());
            log.warn("Could not embed the inspiration photo of request {} (attempt {}): {}", requestId, attempts, error);
            return attempts;
        }
    }

    private void failedPortfolio(UUID id, String model, String error) {
        int attempts = store.failedPortfolio(id, model, error, settings.retryBackoff(), clock.instant());
        log.warn("Could not embed portfolio photo {} (attempt {}): {}", id, attempts, error);
    }

    int maxAttempts() {
        return settings.maxAttempts();
    }
}
