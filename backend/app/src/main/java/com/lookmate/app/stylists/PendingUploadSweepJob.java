package com.lookmate.app.stylists;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs {@link PendingUploadSweep} hourly, with retention on (the default). Tests call the sweep directly. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("lookmate.retention.enabled")
class PendingUploadSweepJob {

    private static final Logger log = LoggerFactory.getLogger(PendingUploadSweepJob.class);

    private final PendingUploadSweep sweep;

    PendingUploadSweepJob(PendingUploadSweep sweep) {
        this.sweep = sweep;
    }

    @Scheduled(cron = "${lookmate.stylists.pending-sweep-cron:0 20 * * * *}", zone = "UTC")
    void run() {
        try {
            sweep.run(Instant.now());
        } catch (RuntimeException e) {
            log.error("Sweeping abandoned portfolio uploads failed", e);
        }
    }
}
