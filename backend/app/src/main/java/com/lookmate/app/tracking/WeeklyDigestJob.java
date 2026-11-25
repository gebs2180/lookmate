package com.lookmate.app.tracking;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Plans and sends the weekly digests every few minutes (they only go out on Mondays), with the digest on (the
 * default). Safe on several instances: planning is idempotent and sending claims rows with SKIP LOCKED. Tests call
 * {@link WeeklyDigests} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "lookmate.tracking.digest.enabled", matchIfMissing = true)
class WeeklyDigestJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestJob.class);

    /** At most this many digests per run; the rest go on the next. */
    private static final int BATCH = 200;

    private final WeeklyDigests digests;

    WeeklyDigestJob(WeeklyDigests digests) {
        this.digests = digests;
    }

    @Scheduled(fixedDelayString = "${lookmate.tracking.digest.poll-interval:10m}",
            initialDelayString = "${lookmate.tracking.digest.poll-interval:10m}")
    void run() {
        Instant now = Instant.now();
        try {
            int planned = digests.plan(now);
            int sent = digests.sendPending(now, BATCH);
            int deleted = digests.cleanUp(now);
            if (planned + sent + deleted > 0) {
                log.info("Stylist digests: {} planned, {} handled, {} expired links deleted", planned, sent, deleted);
            }
        } catch (RuntimeException e) {
            log.error("The stylist digest job failed", e);
        }
    }
}
