package com.lookmate.app.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Client intake (docs/modules/intake.md).
 *
 * @param linkTtl                         how long a client's link works
 * @param maxEmailChangesPerRequest       how many times a client may set (or correct) the email on one request; past it, 409
 * @param maxLinkEmailsPerAddressPerDay   how many requests may have one email address set in 24 hours, so the email endpoint
 *                                        can't flood someone else's inbox from many connections; past it, 429
 * @param matchingStuckAfter              a MATCHING request untouched this long is offered to the matching worker again
 * @param matchesReadyEmailDelay          how long the "matches ready" email waits before it goes; it isn't sent if the client
 *                                        opens their list first (a client still on their page sees the results arrive)
 * @param confirmWait                     how long a client's photo confirm waits for a decode turn ({@code PhotoDecodes})
 *                                        before answering 503 {@code BUSY}
 * @param rateLimit                       per-IP limits on the public endpoints (in memory: per instance)
 */
@ConfigurationProperties("lookmate.intake")
record IntakeProperties(@DefaultValue("90d") Duration linkTtl,
                        @DefaultValue("3") int maxEmailChangesPerRequest,
                        @DefaultValue("5") int maxLinkEmailsPerAddressPerDay,
                        @DefaultValue("15m") Duration matchingStuckAfter,
                        @DefaultValue("5m") Duration matchesReadyEmailDelay,
                        @DefaultValue("20s") Duration confirmWait,
                        @DefaultValue RateLimit rateLimit) {

    /**
     * @param createsPerHour  new requests one IP may make an hour
     * @param createsPerDay   new requests one IP may make a day (on top of the hourly limit)
     * @param emailsPerHour   emails one IP may add to requests an hour ({@code POST /api/public/match-requests/{token}/email})
     * @param readsPerMinute  other calls (status, upload slots, confirms) one IP may make a minute
     * @param browsePerMinute the public stylist directory and the Book links (match and directory), per IP a minute
     */
    record RateLimit(@DefaultValue("10") int createsPerHour, @DefaultValue("20") int createsPerDay,
                     @DefaultValue("5") int emailsPerHour, @DefaultValue("60") int readsPerMinute,
                     @DefaultValue("60") int browsePerMinute) {
    }
}
