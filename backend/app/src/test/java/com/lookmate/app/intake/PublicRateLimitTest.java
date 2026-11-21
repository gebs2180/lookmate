package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitTest {

    private final PublicRateLimit limit = new PublicRateLimit(
            new IntakeProperties(Duration.ofDays(1), 3, 5, Duration.ofMinutes(15), Duration.ofMinutes(5),
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
}
