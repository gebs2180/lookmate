package com.lookmate.app.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One pass of the matching pipeline: embed new portfolio photos, then match waiting requests. Safe on several instances. */
@Component
class MatchingJob {

    /** Batches per kind per pass, so one pass can't run forever while uploads keep coming. */
    private static final int MAX_BATCHES = 20;

    private static final Logger log = LoggerFactory.getLogger(MatchingJob.class);

    private final EmbeddingWorker worker;
    private final Ranker ranker;
    private final ReviewService review;

    MatchingJob(EmbeddingWorker worker, Ranker ranker, ReviewService review) {
        this.worker = worker;
        this.ranker = ranker;
        this.review = review;
    }

    void runOnce() {
        // Portfolio photos first, so a request is ranked against the newest work. Each stage runs even if the other
        // failed, so a provider outage on portfolio photos doesn't stop ranking against what's already embedded.
        stage("portfolio embedding", worker::portfolioBatch);
        stage("matching requests", () -> ranker.rankBatch(10));
        // Review switched off while lists were waiting for it: publish them as they stand (does nothing with it on).
        stage("publishing waiting reviews", () -> review.publishWaitingReviews(200));
    }

    private static void stage(String name, java.util.function.IntSupplier batch) {
        try {
            for (int i = 0; i < MAX_BATCHES && batch.getAsInt() > 0; i++) {
                // keep going while there is work
            }
        } catch (RuntimeException e) {
            log.error("Matching pass: {} failed", name, e);
        }
    }
}
