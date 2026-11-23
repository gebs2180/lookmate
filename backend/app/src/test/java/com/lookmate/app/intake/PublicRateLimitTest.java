package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitTest {

    private final PublicRateLimit limit = new PublicRateLimit(
            new IntakeProperties(Duration.ofDays(1), 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(5), Duration.ofSeconds(20),
                    new IntakeProperties.RateLimit(5, 3, 1, 60, 60)));

    @Test
    void aBucketEmptiesAndRefillsOverItsWindow() {
        long t = 1_000_000_000L;
        Duration hour = Duration.ofHours(1);
        assertThat(limit.take("a", 2, hour, t)).isZero();
        assertThat(limit.take("a", 2, hour, t)).isZero();
        assertThat(limit.take("a", 2, hour, t)).isEqualTo(1800);   // one token comes back every half hour
        assertThat(limit.take("b", 2, hour, t)).as("another address has its own bucket").isZero();
        assertThat(limit.take("a", 2, hour, t + Duration.ofMinutes(31).toNanos())).isZero();
    }

    private static MockHttpServletRequest request(String method, String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        return request;
    }

    private void handle(String method, String uri, String ip) {
        limit.preHandle(request(method, uri, ip), new MockHttpServletResponse(), new Object());
    }

    @Test
    void creationHasADailyLimitOnTopOfTheHourlyOne() {
        // 5 an hour but 3 a day: the fourth is refused by the daily bucket, with a wait of hours, not minutes.
        for (int i = 0; i < 3; i++) {
            handle("POST", "/api/public/match-requests", "10.0.0.1");
        }
        assertThatThrownBy(() -> handle("POST", "/api/public/match-requests", "10.0.0.1"))
                .isInstanceOf(PublicRateLimit.RateLimited.class)
                .satisfies(e -> assertThat(Long.parseLong(((PublicRateLimit.RateLimited) e).getHeaders().getFirst("Retry-After")))
                        .isGreaterThan(Duration.ofHours(7).toSeconds()));
        handle("POST", "/api/public/match-requests", "10.0.0.2");
    }

    @Test
    void addingAnEmailHasItsOwnBucket() {
        handle("POST", "/api/public/match-requests/abc/email", "10.0.0.3");
        assertThatThrownBy(() -> handle("POST", "/api/public/match-requests/abc/email", "10.0.0.3"))
                .isInstanceOf(PublicRateLimit.RateLimited.class);
        // Reading and creating from that address aren't affected.
        handle("GET", "/api/public/match-requests/abc", "10.0.0.3");
        handle("POST", "/api/public/match-requests", "10.0.0.3");
    }

    @Test
    void aRefusedRequestSpendsNoneOfItsOtherLimits() {
        // Review 2026-11-22, finding 16: refused creates used to drain the daily bucket.
        long t = 1_000_000_000L;
        List<PublicRateLimit.Limit> create = List.of(new PublicRateLimit.Limit("day", 3, Duration.ofDays(1)),
                new PublicRateLimit.Limit("hour", 1, Duration.ofHours(1)));
        assertThat(limit.take(create, t)).isZero();
        for (int i = 0; i < 10; i++) {
            assertThat(limit.take(create, t)).as("refused by the hourly bucket").isPositive();
        }
        assertThat(limit.take(create, t + Duration.ofMinutes(61).toNanos())).as("the day still has two left").isZero();
        assertThat(limit.take(create, t + Duration.ofMinutes(122).toNanos())).isZero();
        assertThat(limit.take(create, t + Duration.ofMinutes(183).toNanos())).as("now the day is spent").isGreaterThan(3600);
    }

    @Test
    void aDailyBucketIsntForgottenAfterAnIdleHour() {
        // Review 2026-11-22, finding 16: every bucket idle for an hour was dropped, so the daily limit reset hourly.
        long t = 1_000_000_000L;
        Duration day = Duration.ofDays(1);
        for (int i = 0; i < 3; i++) {
            assertThat(limit.take("create-day:x", 3, day, t)).isZero();
        }
        assertThat(limit.take("read:y", 60, Duration.ofMinutes(1), t)).isZero();
        long later = t + Duration.ofHours(2).toNanos();
        assertThat(limit.take("read:z", 60, Duration.ofMinutes(1), later)).isZero();   // sweeps
        assertThat(limit.size()).as("the idle minute bucket goes, the day bucket stays").isEqualTo(2);
        assertThat(limit.take("create-day:x", 3, day, later)).isPositive();
    }

    @Test
    void bookLinksFromAMatchShareTheDirectoryBucket() {
        // Review 2026-11-22, finding 15: /api/public/book/** had no limit of its own.
        for (int i = 0; i < 60; i++) {
            handle("GET", "/api/public/book/tok/" + i, "10.0.0.4");
        }
        assertThatThrownBy(() -> handle("GET", "/api/public/stylists", "10.0.0.4")).isInstanceOf(PublicRateLimit.RateLimited.class);
        handle("GET", "/api/public/match-requests/abc", "10.0.0.4");
    }
}
