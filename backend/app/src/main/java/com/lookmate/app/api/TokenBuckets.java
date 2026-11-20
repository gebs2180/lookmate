package com.lookmate.app.api;

import com.lookmate.app.api.RateLimitProperties.Budget;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets in memory, keyed by any string. A bucket that has refilled to full is the same as no bucket, so the
 * sweep (at most once a minute, on the request path) drops those: memory follows the keys active within their window,
 * and {@code maxTrackedKeys} bounds it outright. Times are {@link System#nanoTime()} values, passed in for tests.
 */
final class TokenBuckets {

    private static final Logger log = LoggerFactory.getLogger(TokenBuckets.class);
    private static final long SWEEP_EVERY = Duration.ofMinutes(1).toNanos();

    /** {@code fullAt}: when it will have refilled completely, so it can be dropped. */
    private record Bucket(double tokens, long at, long fullAt) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final AtomicLong lastSweep;
    private final AtomicLong lastFullWarning;

    TokenBuckets(int maxKeys, long now) {
        this.maxKeys = maxKeys;
        this.lastSweep = new AtomicLong(now);
        this.lastFullWarning = new AtomicLong(now - SWEEP_EVERY);
    }

    /** Takes one token: 0 when there was one, otherwise the whole seconds until there will be (at least 1). */
    long take(String key, Budget budget, long now) {
        sweepIfDue(now);
        if (buckets.size() >= maxKeys && !buckets.containsKey(key)) {
            sweep(now);
            if (buckets.size() >= maxKeys) {
                // Refusing would lock out every new visitor during a flood from many addresses; the per-route
                // checks behind this (intake's open-request cap, tracking's click cap) still hold.
                if (lastFullWarning.getAndUpdate(t -> now - t >= SWEEP_EVERY ? now : t) <= now - SWEEP_EVERY) {
                    log.warn("Rate limiter full ({} keys); new addresses are not limited until idle ones expire", maxKeys);
                }
                return 0;
            }
        }
        double perNano = perNano(budget);
        long[] wait = {0};
        buckets.compute(key, (k, b) -> {
            double tokens = b == null ? budget.requests() : Math.min(budget.requests(), b.tokens() + (now - b.at()) * perNano);
            if (tokens >= 1) {
                tokens -= 1;
            } else {
                wait[0] = Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1e9));
            }
            return bucket(tokens, now, budget);
        });
        return wait[0];
    }

    /** Gives back a token taken for a request that turned out not to count. */
    void refund(String key, Budget budget) {
        buckets.computeIfPresent(key, (k, b) -> bucket(Math.min(budget.requests(), b.tokens() + 1), b.at(), budget));
    }

    int size() {
        return buckets.size();
    }

    void sweep(long now) {
        lastSweep.set(now);
        buckets.values().removeIf(b -> now - b.fullAt() >= 0);
    }

    private void sweepIfDue(long now) {
        long last = lastSweep.get();
        if (now - last >= SWEEP_EVERY && lastSweep.compareAndSet(last, now)) {
            sweep(now);
        }
    }

    private static Bucket bucket(double tokens, long at, Budget budget) {
        return new Bucket(tokens, at, at + (long) Math.ceil((budget.requests() - tokens) / perNano(budget)));
    }

    private static double perNano(Budget budget) {
        return budget.requests() / (double) budget.per().toNanos();
    }
}
