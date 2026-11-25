package com.lookmate.app.tracking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The weekly stylist digest (docs/tracking.md, "Weekly digest").
 *
 * @param enabled         runs the scheduled job; tests call {@link WeeklyDigests} directly
 * @param sendHour        on Mondays, digests go out from this hour (tracking time zone) on
 * @param pollInterval    how often the job looks for digests to plan and send
 * @param linkTtl         how long a Booked/Rebooked link works
 * @param unsubscribeTtl  how long the unsubscribe link works
 * @param maxAttempts     a digest that fails this many times is marked FAILED and left for the logs
 * @param maxClients      at most this many clients get buttons in one email (the count still says how many tapped)
 * @param newClientsConfirmAfter  the email asks "Still taking new clients? Confirm" once the stylist's status is older
 *                        than this (the same 30 days as {@code lookmate.stylists.new-clients-confirm-after})
 */
@ConfigurationProperties("lookmate.tracking.digest")
record DigestProperties(Boolean enabled, Integer sendHour, Duration pollInterval, Duration linkTtl, Duration unsubscribeTtl,
                        Integer maxAttempts, Integer maxClients, Duration newClientsConfirmAfter) {

    DigestProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (sendHour == null) {
            sendHour = 8;
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofMinutes(10);
        }
        if (linkTtl == null) {
            linkTtl = Duration.ofDays(14);
        }
        if (unsubscribeTtl == null) {
            unsubscribeTtl = Duration.ofDays(90);
        }
        if (maxAttempts == null) {
            maxAttempts = 3;
        }
        if (maxClients == null) {
            maxClients = 20;
        }
        if (newClientsConfirmAfter == null) {
            newClientsConfirmAfter = Duration.ofDays(30);
        }
    }
}
