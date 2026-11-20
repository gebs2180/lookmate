package com.lookmate.app.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Budgets for the public, sign-in-free routes ({@code /api/public/**}), per client IP unless named otherwise
 * ({@link PublicRateLimitFilter}). In memory, so per app instance (docs/architecture.md, "How it scales").
 *
 * @param enabled               off only in tests that call the public routes many times from one address
 * @param book                  the booking redirect, {@code GET /api/public/book/**}
 * @param viewed                {@code POST /api/public/match-requests/{token}/viewed}
 * @param createRequest         intake's quiz, {@code POST /api/public/match-requests}
 * @param createRequestPerEmail the same route, per email address in the body; only requests that succeed count
 * @param other                 every other public route, one shared budget per IP
 * @param maxTrackedKeys        buckets kept at most; past this, new addresses go unlimited until idle ones are dropped
 */
@ConfigurationProperties("lookmate.rate-limit")
record RateLimitProperties(@DefaultValue("true") boolean enabled,
                                  Budget book,
                                  Budget viewed,
                                  Budget createRequest,
                                  Budget createRequestPerEmail,
                                  Budget other,
                                  @DefaultValue("100000") int maxTrackedKeys) {

    /** The agreed starting budgets (2026-11-20), for any not set in application.yml. */
    RateLimitProperties {
        book = book != null ? book : new Budget(60, Duration.ofMinutes(1));
        viewed = viewed != null ? viewed : new Budget(30, Duration.ofMinutes(1));
        createRequest = createRequest != null ? createRequest : new Budget(5, Duration.ofHours(1));
        createRequestPerEmail = createRequestPerEmail != null ? createRequestPerEmail : new Budget(20, Duration.ofDays(1));
        other = other != null ? other : new Budget(60, Duration.ofMinutes(1));
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("lookmate.rate-limit.max-tracked-keys must be at least 1");
        }
    }

    /**
     * A token bucket: up to {@code requests} at once, refilled evenly over {@code per} (5 per hour is one back every 12
     * minutes).
     */
    public record Budget(int requests, Duration per) {
        public Budget {
            if (requests < 1 || per == null || per.isZero() || per.isNegative()) {
                throw new IllegalArgumentException("A rate-limit budget needs at least 1 request over a positive period");
            }
        }
    }
}
