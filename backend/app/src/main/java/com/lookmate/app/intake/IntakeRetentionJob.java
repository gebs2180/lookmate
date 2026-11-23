package com.lookmate.app.intake;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Intake's daily retention, with retention on (the default): inspiration photos, then client contact details. Each runs
 * on its own, so one failing doesn't stop the other. Tests call the jobs' {@code run} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty("lookmate.retention.enabled")
class IntakeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(IntakeRetentionJob.class);

    private final InspirationRetention photos;
    private final ClientContactRetention contacts;

    IntakeRetentionJob(InspirationRetention photos, ClientContactRetention contacts) {
        this.photos = photos;
        this.contacts = contacts;
    }

    @Scheduled(cron = "${lookmate.retention.cron:0 15 4 * * *}", zone = "UTC")
    void run() {
        Instant now = Instant.now();
        try {
            photos.run(now);
        } catch (RuntimeException e) {
            log.error("Inspiration photo retention failed", e);
        }
        try {
            contacts.run(now);
        } catch (RuntimeException e) {
            log.error("Client contact retention failed", e);
        }
    }
}
