package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PublicRateLimitTest {

    private final PublicRateLimit limit = new PublicRateLimit(
            new IntakeProperties(Duration.ofDays(1), 3, Duration.ofMinutes(15), new IntakeProperties.RateLimit(2, 60)));

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
}
