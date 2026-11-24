package com.lookmate.app.consult;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes finder sessions after the client-contact period ({@code lookmate.retention.client-contact-days}, 365 days):
 * they hold no contact details, but they are a client's answers about their hair, so they don't outlive intake's.
 */
@Component
class ConsultRetention {

    private static final Logger log = LoggerFactory.getLogger(ConsultRetention.class);

    private final ConsultSessions sessions;
    private final Duration keep;

    ConsultRetention(ConsultSessions sessions, @Value("${lookmate.retention.client-contact-days:365}") int days) {
        this.sessions = sessions;
        this.keep = Duration.ofDays(days);
    }

    int run(Instant now) {
        int deleted = sessions.deleteBefore(now.minus(keep));
        if (deleted > 0) {
            log.info("Deleted {} service finder session(s) older than {} days", deleted, keep.toDays());
        }
        return deleted;
    }

    /** Daily, with retention on (the default), at intake's time. Tests call {@link #run} directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("lookmate.retention.enabled")
    static class Job {

        private final ConsultRetention retention;

        Job(ConsultRetention retention) {
            this.retention = retention;
        }

        @Scheduled(cron = "${lookmate.retention.cron:0 15 4 * * *}", zone = "UTC")
        void run() {
            try {
                retention.run(Instant.now());
            } catch (RuntimeException e) {
                log.error("Service finder session retention failed", e);
            }
        }
    }
}
