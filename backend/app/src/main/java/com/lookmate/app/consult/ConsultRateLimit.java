package com.lookmate.app.consult;

import com.lookmate.app.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Per-IP limits on {@code /api/public/consult/**}, like intake's: submitted answers per hour, everything else per
 * minute. Token buckets in memory, so per instance (docs/modules/intake.md says when to move them). Consult keeps its
 * own copy because it depends on no feature module. The IP is the remote address, the client's behind Caddy.
 */
@Component
class ConsultRateLimit implements HandlerInterceptor {

    enum Code { RATE_LIMITED }

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

    private static final class Bucket {
        private double tokens;
        private long at;
        private final long windowNanos;

        Bucket(int capacity, long windowNanos, long now) {
            this.tokens = capacity;
            this.at = now;
            this.windowNanos = windowNanos;
        }
    }

    private static final long SWEEP_NANOS = Duration.ofMinutes(1).toNanos();

    private final Map<String, Bucket> buckets = new HashMap<>();
    private final ConsultProperties.RateLimit limits;
    private long sweptAt;

    ConsultRateLimit(ConsultProperties properties) {
        this.limits = properties.rateLimit();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().replaceAll("/+$", "");
        boolean submit = "POST".equals(request.getMethod()) && uri.endsWith("/sessions");
        String ip = request.getRemoteAddr();
        long wait = submit
                ? take("submit:" + ip, limits.submitsPerHour(), Duration.ofHours(1), System.nanoTime())
                : take("read:" + ip, limits.readsPerMinute(), Duration.ofMinutes(1), System.nanoTime());
        if (wait > 0) {
            throw new RateLimited(wait);
        }
        return true;
    }

    /** Takes a token; returns 0 when there was one, otherwise the seconds until there will be. */
    synchronized long take(String key, int capacity, Duration window, long now) {
        if (now - sweptAt >= SWEEP_NANOS) {
            sweptAt = now;
            buckets.values().removeIf(b -> now - b.at >= b.windowNanos);
        }
        long windowNanos = window.toNanos();
        double perNano = capacity / (double) windowNanos;
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, windowNanos, now));
        double tokens = Math.min(capacity, b.tokens + (now - b.at) * perNano);
        if (tokens < 1) {
            return Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1e9));
        }
        b.tokens = tokens - 1;
        b.at = now;
        return 0;
    }
}
