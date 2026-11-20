package com.lookmate.app.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** One pass of the matching pipeline: embed what's missing, then rank what's ready. Safe to run on several instances. */
@Component
class MatchingJob {

    /** Batches per kind per pass, so one pass can't run forever while uploads keep coming. */
    private static final int MAX_BATCHES = 20;

    private static final Logger log = LoggerFactory.getLogger(MatchingJob.class);

    private final EmbeddingWorker worker;
    private final Ranker ranker;

    MatchingJob(EmbeddingWorker worker, Ranker ranker) {
        this.worker = worker;
        this.ranker = ranker;
    }

    void runOnce() {
        // Inspiration photos first: a waiting client matters more than a new portfolio photo. Each stage runs even if
        // an earlier one failed, so a provider outage on portfolio photos doesn't stop ranking what's already embedded.
        stage("inspiration embedding", worker::inspirationBatch);
        stage("portfolio embedding", worker::portfolioBatch);
        stage("ranking", () -> ranker.rankBatch(10));
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
