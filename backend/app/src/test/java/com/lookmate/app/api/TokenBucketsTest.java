package com.lookmate.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookmate.app.api.RateLimitProperties.Budget;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketsTest {

    private static final long T = 1_000_000_000L;
    private static final Budget TWO_AN_HOUR = new Budget(2, Duration.ofHours(1));

    @Test
    void aBucketEmptiesAndRefillsOverItsWindow() {
        TokenBuckets buckets = new TokenBuckets(100, T);
        assertThat(buckets.take("a", TWO_AN_HOUR, T)).isZero();
        assertThat(buckets.take("a", TWO_AN_HOUR, T)).isZero();
        assertThat(buckets.take("a", TWO_AN_HOUR, T)).isEqualTo(1800);   // one token comes back every half hour
        assertThat(buckets.take("b", TWO_AN_HOUR, T)).as("another key has its own bucket").isZero();
        assertThat(buckets.take("a", TWO_AN_HOUR, T + Duration.ofMinutes(20).toNanos())).isBetween(600L, 601L);
        assertThat(buckets.take("a", TWO_AN_HOUR, T + Duration.ofMinutes(31).toNanos())).isZero();
    }

    @Test
    void aRefundGivesATokenBack() {
        TokenBuckets buckets = new TokenBuckets(100, T);
        buckets.take("a", TWO_AN_HOUR, T);
        buckets.take("a", TWO_AN_HOUR, T);
        buckets.refund("a", TWO_AN_HOUR);
        assertThat(buckets.take("a", TWO_AN_HOUR, T)).isZero();
        assertThat(buckets.take("a", TWO_AN_HOUR, T)).isPositive();
    }

    @Test
    void bucketsThatHaveRefilledAreDropped() {
        TokenBuckets buckets = new TokenBuckets(100, T);
        buckets.take("short", new Budget(10, Duration.ofMinutes(1)), T);
        buckets.take("long", TWO_AN_HOUR, T);
        assertThat(buckets.size()).isEqualTo(2);

        // The next request after a minute sweeps: the one-minute bucket is full again (the same as none), the other isn't.
        buckets.take("other", TWO_AN_HOUR, T + Duration.ofSeconds(61).toNanos());
        assertThat(buckets.size()).isEqualTo(2);
        buckets.sweep(T + Duration.ofMinutes(32).toNanos());
        assertThat(buckets.size()).as("both hourly buckets refilled their one token").isZero();
    }

    @Test
    void whenFullNewKeysPassUntilIdleOnesExpire() {
        TokenBuckets buckets = new TokenBuckets(2, T);
        Budget one = new Budget(1, Duration.ofHours(1));
        buckets.take("a", one, T);
        buckets.take("b", one, T);
        assertThat(buckets.take("c", one, T)).isZero();
        assertThat(buckets.take("c", one, T)).as("not tracked, so not limited").isZero();
        assertThat(buckets.size()).isEqualTo(2);
        assertThat(buckets.take("a", one, T)).as("keys already tracked are still limited").isPositive();

        long later = T + Duration.ofHours(1).toNanos();
        assertThat(buckets.take("c", one, later)).isZero();
        assertThat(buckets.take("c", one, later)).isPositive();
    }
}
