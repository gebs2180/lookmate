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

    MatchingJob(EmbeddingWorker worker, Ranker ranker) {
        this.worker = worker;
        this.ranker = ranker;
    }

    void runOnce() {
        // Portfolio photos first, so a request is ranked against the newest work. Each stage runs even if the other
        // failed, so a provider outage on portfolio photos doesn't stop ranking against what's already embedded.
        stage("portfolio embedding", worker::portfolioBatch);
        stage("matching requests", () -> ranker.rankBatch(10));
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
