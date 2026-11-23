package com.lookmate.app.intake;

import com.lookmate.app.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limits on the public endpoints: new requests per hour and per day, emails added to requests per hour, the
 * stylist directory and the Book links (from a match or from the directory) per minute, and everything else per
 * minute. Clients don't have to give an email, so creation is limited by address, not by email (CLAUDE.md, 2026-11-20).
 * Token buckets in memory, so this is per instance: with more than one app container, move the limit to Caddy or Postgres
 * (docs/modules/intake.md). The IP is the request's remote address, which behind Caddy is the client's
 * ({@code server.forward-headers-strategy: framework}; Caddy ignores an X-Forwarded-For the client sends).
 */
@Component
class PublicRateLimit implements HandlerInterceptor {

    enum Code { RATE_LIMITED }

    /** Answered with Retry-After, in seconds. */
    static final class RateLimited extends ApiException {
        private final long retryAfterSeconds;

        RateLimited(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, Code.RATE_LIMITED, "Too many tries from your connection. Wait a little and try again.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            return headers;
        }
    }

    /**
     * The public stylist directory (stylists module; also {@code /api/public/match-requests/{token}/stylists}, under
     * intake's paths) and the tracked Book links (tracking), from a match and from the directory: their own per-minute
     * bucket.
     */
    static final String[] BROWSE_PATHS = {"/api/public/stylists", "/api/public/stylists/**", "/api/public/book/**",
        "/api/public/book-stylist/**"};

    /** One limit a request counts against. */
    record Limit(String key, int capacity, Duration window) {
    }

    /** A token bucket; it remembers its window, so it's only forgotten once it would be full again anyway. */
    private static final class Bucket {
        private final long windowNanos;
        private double tokens;
        private long at;

        Bucket(int capacity, Duration window, long now) {
            this.windowNanos = window.toNanos();
            this.tokens = capacity;
            this.at = now;
        }

        double tokensAt(int capacity, long now) {
            return Math.min(capacity, tokens + (now - at) * (capacity / (double) windowNanos));
        }
    }

    /** Idle buckets are swept at most this often, not on every request. */
    private static final long SWEEP_NANOS = Duration.ofMinutes(1).toNanos();

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final IntakeProperties.RateLimit limits;
    private long sweptAt;
    private boolean swept;

    PublicRateLimit(IntakeProperties properties) {
        this.limits = properties.rateLimit();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().replaceAll("/+$", "");
        boolean create = "POST".equals(request.getMethod()) && uri.endsWith("/api/public/match-requests");
        boolean browse = uri.contains("/api/public/stylists") || uri.contains("/api/public/book/") || uri.contains("/api/public/book-stylist/")
                || (uri.contains("/api/public/match-requests/") && uri.endsWith("/stylists"));
        boolean email = "POST".equals(request.getMethod()) && uri.contains("/api/public/match-requests/") && uri.endsWith("/email");
        String ip = request.getRemoteAddr();
        List<Limit> apply = create
                ? List.of(new Limit("create-day:" + ip, limits.createsPerDay(), Duration.ofDays(1)),
                        new Limit("create:" + ip, limits.createsPerHour(), Duration.ofHours(1)))
                : email
                ? List.of(new Limit("email:" + ip, limits.emailsPerHour(), Duration.ofHours(1)))
                : browse
                ? List.of(new Limit("browse:" + ip, limits.browsePerMinute(), Duration.ofMinutes(1)))
                : List.of(new Limit("read:" + ip, limits.readsPerMinute(), Duration.ofMinutes(1)));
        long wait = take(apply, System.nanoTime());
        if (wait > 0) {
            throw new RateLimited(wait);
        }
        return true;
    }

    /** Takes one token from one bucket; returns 0 when there was one, otherwise the seconds until there will be. */
    long take(String key, int capacity, Duration window, long now) {
        return take(List.of(new Limit(key, capacity, window)), now);
    }

    /**
     * Takes one token from every bucket when each has one; otherwise takes none (a refused request doesn't spend the
     * others, review 2026-11-22, finding 16) and returns the longest wait in seconds.
     */
    synchronized long take(List<Limit> apply, long now) {
        sweep(now);
        long wait = 0;
        for (Limit limit : apply) {
            Bucket b = buckets.get(limit.key());
            double tokens = b == null ? limit.capacity() : b.tokensAt(limit.capacity(), now);
            if (tokens < 1) {
                double perNano = limit.capacity() / (double) limit.window().toNanos();
                wait = Math.max(wait, Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1e9)));
            }
        }
        if (wait > 0) {
            return wait;
        }
        for (Limit limit : apply) {
            Bucket b = buckets.computeIfAbsent(limit.key(), k -> new Bucket(limit.capacity(), limit.window(), now));
            b.tokens = b.tokensAt(limit.capacity(), now) - 1;
            b.at = now;
        }
        return 0;
    }

    /** Forgets buckets idle for a whole window (they'd be full again), at most once a minute. */
    private void sweep(long now) {
        if (swept && now - sweptAt < SWEEP_NANOS) {
            return;
        }
        swept = true;
        sweptAt = now;
        buckets.values().removeIf(b -> now - b.at >= b.windowNanos);
    }

    /** How many addresses' buckets are held, for tests. */
    synchronized int size() {
        return buckets.size();
    }
}
