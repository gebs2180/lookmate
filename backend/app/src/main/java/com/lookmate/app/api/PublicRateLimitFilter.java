package com.lookmate.app.api;

import com.lookmate.app.api.RateLimitProperties.Budget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Rate limits for the routes clients reach without signing in ({@code /api/public/**}): per-IP token buckets in memory,
 * with budgets from {@code lookmate.rate-limit} ({@link RateLimitProperties}). Over the limit is a 429 problem detail,
 * code {@code RATE_LIMITED}, with {@code Retry-After} in seconds. Signed-in routes never reach this filter.
 *
 * <p>The IP is the request's remote address. In production Tomcat sets it from {@code X-Forwarded-For} only when the
 * connection comes from the proxy ({@code server.forward-headers-strategy: native}, {@code server.tomcat.remoteip.
 * internal-proxies}), so a header a client sends itself changes nothing. IPv6 addresses count per /64, the block one
 * connection is usually given, so rotating through it doesn't buy a fresh budget.
 *
 * <p>Intake's create route also has a budget per email address: the email it triggers is what costs us (sending
 * reputation, someone's inbox). Only requests that succeed spend it, so failed tries from elsewhere can't use up a real
 * client's allowance. Addresses are kept as SHA-256 hashes, lower-cased and without a {@code +tag}.
 *
 * <p>Per instance: with a second app container the budgets multiply (docs/architecture.md, "How it scales").
 */
final class PublicRateLimitFilter extends OncePerRequestFilter {

    enum Code { RATE_LIMITED }

    static final String TOO_MANY = "Too many tries from your connection. Wait a little and try again.";
    static final String TOO_MANY_FOR_EMAIL = "Too many requests for this email address today. Try again tomorrow.";

    /** A quiz is a few kilobytes; a bigger body isn't read for the email, just passed on for the controller to refuse. */
    static final int MAX_BODY_READ = 64 * 1024;

    /** 429 with the seconds to wait, rendered by {@link ApiErrorHandler} like any other {@link ApiException}. */
    static final class RateLimited extends ApiException {
        private final long retryAfterSeconds;

        RateLimited(String message, long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, Code.RATE_LIMITED, message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            return headers;
        }
    }

    private record Route(String name, String method, PathPattern pattern, Budget budget) {
        boolean matches(String requestMethod, PathContainer path) {
            return (method == null || method.equals(requestMethod)) && pattern.matches(path);
        }
    }

    private static final PathPattern PUBLIC = PathPatternParser.defaultInstance.parse("/api/public/**");

    private final RateLimitProperties properties;
    private final Route createRequest;
    private final Route[] routes;
    private final Route other;
    private final TokenBuckets buckets;
    private final HandlerExceptionResolver errors;
    private final ObjectMapper json;
    private final LongSupplier clock;

    PublicRateLimitFilter(RateLimitProperties properties, HandlerExceptionResolver errors, ObjectMapper json, LongSupplier clock) {
        this.properties = properties;
        this.errors = errors;
        this.json = json;
        this.clock = clock;
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.createRequest = new Route("create", "POST", parser.parse("/api/public/match-requests"), properties.createRequest());
        this.routes = new Route[] {
                new Route("book", "GET", parser.parse("/api/public/book/**"), properties.book()),
                new Route("viewed", "POST", parser.parse("/api/public/match-requests/{token}/viewed"), properties.viewed()),
                createRequest};
        this.other = new Route("other", null, PUBLIC, properties.other());
        this.buckets = new TokenBuckets(properties.maxTrackedKeys(), clock.getAsLong());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PUBLIC.matches(path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathContainer path = path(request);
        Route route = other;
        for (Route r : routes) {
            if (r.matches(request.getMethod(), path)) {
                route = r;
                break;
            }
        }
        long now = clock.getAsLong();
        long wait = buckets.take(route.name() + "|" + clientKey(request.getRemoteAddr()), route.budget(), now);
        if (wait > 0) {
            refuse(request, response, new RateLimited(TOO_MANY, wait));
            return;
        }
        if (route != createRequest) {
            chain.doFilter(request, response);
            return;
        }

        BufferedBody body = BufferedBody.of(request);
        String email = body.complete() ? emailKey(body.bytes()) : null;
        if (email == null) {
            chain.doFilter(body, response);
            return;
        }
        String key = "email|" + email;
        Budget budget = properties.createRequestPerEmail();
        wait = buckets.take(key, budget, now);
        if (wait > 0) {
            refuse(request, response, new RateLimited(TOO_MANY_FOR_EMAIL, wait));
            return;
        }
        boolean counted = false;
        try {
            chain.doFilter(body, response);
            counted = response.getStatus() < 300;
        } finally {
            if (!counted) {
                buckets.refund(key, budget);
            }
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, RateLimited refusal) throws IOException {
        if (errors.resolveException(request, response, null, refusal) == null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, refusal.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), refusal.getReason());
        }
    }

    /** The path without the context path. Literal segments match their decoded value, so {@code %72} is an {@code r}. */
    private static PathContainer path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return PathContainer.parsePath(context.isEmpty() ? uri : uri.substring(context.length()));
    }

    /** IPv4 as is; IPv6 by its /64. Anything unparseable is used as it came. */
    static String clientKey(String remoteAddr) {
        if (remoteAddr == null) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.ofLiteral(remoteAddr);
            if (address instanceof Inet6Address) {
                byte[] bytes = address.getAddress();
                return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
            }
            return address.getHostAddress();
        } catch (IllegalArgumentException e) {
            return remoteAddr;
        }
    }

    /** The hashed, normalized {@code email} of a JSON body, or null when there isn't one to read. */
    String emailKey(byte[] body) {
        String email;
        try {
            JsonNode node = json.readTree(body);
            JsonNode field = node == null ? null : node.get("email");
            email = field != null && field.isString() ? field.asString() : null;
        } catch (RuntimeException e) {
            return null; // not JSON: the controller answers 400, and the IP budget already counted it
        }
        if (email == null || email.isBlank()) {
            return null;
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        if (at > 0) {
            int plus = normalized.indexOf('+');
            if (plus >= 0 && plus < at) {
                normalized = normalized.substring(0, plus) + normalized.substring(at);
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The request with its body read up front (up to {@link #MAX_BODY_READ}) and replayed to the controller. A longer
     * body is replayed as the bytes read plus the rest of the stream, unread by this filter.
     */
    static final class BufferedBody extends HttpServletRequestWrapper {
        private final byte[] bytes;
        private final boolean complete;
        private final InputStream rest;

        private BufferedBody(HttpServletRequest request, byte[] bytes, boolean complete, InputStream rest) {
            super(request);
            this.bytes = bytes;
            this.complete = complete;
            this.rest = rest;
        }

        static BufferedBody of(HttpServletRequest request) throws IOException {
            InputStream in = request.getInputStream();
            byte[] head = in.readNBytes(MAX_BODY_READ + 1);
            boolean complete = head.length <= MAX_BODY_READ;
            return new BufferedBody(request, head, complete, complete ? InputStream.nullInputStream() : in);
        }

        byte[] bytes() {
            return bytes;
        }

        boolean complete() {
            return complete;
        }

        private InputStream replay;

        @Override
        public ServletInputStream getInputStream() {
            if (replay == null) {
                replay = new SequenceInputStream(new ByteArrayInputStream(bytes), rest);
            }
            InputStream source = replay;
            return new ServletInputStream() {
                private boolean finished;

                @Override
                public int read() throws IOException {
                    int b = source.read();
                    finished = b < 0;
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = source.read(buffer, offset, length);
                    finished = n < 0;
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return finished;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Public routes read their bodies synchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }
}
