package com.lookmate.app.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client intake (docs/modules/intake.md).
 *
 * @param linkTtl                       how long a client's link works
 * @param maxOpenRequestsPerEmailPerDay requests one email address may have open (not yet matched) from the last 24 hours
 * @param matchingStuckAfter            a MATCHING request untouched this long is offered to the matching worker again
 * @param rateLimit                     per-IP limits on the public endpoints (in memory: per instance)
 */
@ConfigurationProperties("lookmate.intake")
record IntakeProperties(@DefaultValue("120d") Duration linkTtl,
                        @DefaultValue("3") int maxOpenRequestsPerEmailPerDay,
                        @DefaultValue("15m") Duration matchingStuckAfter,
                        @DefaultValue RateLimit rateLimit) {

    /**
     * @param createsPerHour new requests one IP may make an hour
     * @param readsPerMinute other calls (status, upload slots, confirms) one IP may make a minute
     */
    record RateLimit(@DefaultValue("10") int createsPerHour, @DefaultValue("60") int readsPerMinute) {
    }
}
