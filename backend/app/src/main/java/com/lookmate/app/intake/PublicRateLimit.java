package com.lookmate.app.intake;

import com.lookmate.app.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limits on the public endpoints: new requests per hour, the stylist directory and its Book links per minute,
 * and everything else per minute. Token buckets in
 * memory, so this is per instance: with more than one app container, move the limit to Caddy or Postgres
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

    /** The public stylist directory (stylists module) and its tracked Book links (tracking): their own per-minute bucket. */
    static final String[] BROWSE_PATHS = {"/api/public/stylists", "/api/public/stylists/**", "/api/public/book-stylist/**"};

    private record Bucket(double tokens, long at) {
    }

    /** Past this many tracked addresses, the idle ones are dropped. */
    private static final int MAX_KEYS = 50_000;
    private static final long IDLE_NANOS = Duration.ofHours(1).toNanos();

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final IntakeProperties.RateLimit limits;

    PublicRateLimit(IntakeProperties properties) {
        this.limits = properties.rateLimit();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().replaceAll("/+$", "");
        boolean create = "POST".equals(request.getMethod()) && uri.endsWith("/api/public/match-requests");
        boolean browse = uri.contains("/api/public/stylists") || uri.contains("/api/public/book-stylist/");
        String ip = request.getRemoteAddr();
        long wait = create
                ? take("create:" + ip, limits.createsPerHour(), Duration.ofHours(1), System.nanoTime())
                : browse
                ? take("browse:" + ip, limits.browsePerMinute(), Duration.ofMinutes(1), System.nanoTime())
                : take("read:" + ip, limits.readsPerMinute(), Duration.ofMinutes(1), System.nanoTime());
        if (wait > 0) {
            throw new RateLimited(wait);
        }
        return true;
    }

    /** Takes one token; returns 0 when there was one, otherwise the seconds until there will be. */
    long take(String key, int capacity, Duration window, long now) {
        if (buckets.size() > MAX_KEYS) {
            buckets.entrySet().removeIf(e -> now - e.getValue().at() > IDLE_NANOS);
        }
        double perNano = capacity / (double) window.toNanos();
        long[] wait = {0};
        buckets.compute(key, (k, b) -> {
            double tokens = b == null ? capacity : Math.min(capacity, b.tokens() + (now - b.at()) * perNano);
            if (tokens >= 1) {
                return new Bucket(tokens - 1, now);
            }
            wait[0] = Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1e9));
            return new Bucket(tokens, now);
        });
        return wait[0];
    }
}
