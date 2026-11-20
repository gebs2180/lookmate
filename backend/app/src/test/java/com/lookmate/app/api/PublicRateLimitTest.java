package com.lookmate.app.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The limiter on the public routes, against the real app: per-route budgets per IP, 429 problem details with
 * Retry-After, and nothing on the signed-in routes. Intake's create route has its own test (intake/IntakeRateLimitTest).
 */
@SpringBootTest(properties = {
        "lookmate.rate-limit.book.requests=3",
        "lookmate.rate-limit.book.per=1m",
        "lookmate.rate-limit.viewed.requests=2",
        "lookmate.rate-limit.viewed.per=1m",
        "lookmate.rate-limit.other.requests=3",
        "lookmate.rate-limit.other.per=1m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PublicRateLimitTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String book() {
        return "/api/public/book/tok_" + UUID.randomUUID().toString().replace("-", "") + "/" + UUID.randomUUID();
    }

    @Test
    void underTheLimitPassesAndOverItIs429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(book()).with(from("203.0.113.40"))).andExpect(status().isNotFound());
        }
        mvc.perform(get(book()).with(from("203.0.113.40")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"))   // 3 a minute: one back every 20 seconds
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value(PublicRateLimitFilter.TOO_MANY));
    }

    @Test
    void eachAddressHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(book()).with(from("203.0.113.41"))).andExpect(status().isNotFound());
        }
        mvc.perform(get(book()).with(from("203.0.113.41"))).andExpect(status().isTooManyRequests());
        mvc.perform(get(book()).with(from("203.0.113.42"))).andExpect(status().isNotFound());
        // IPv6 counts per /64: another address in the same block shares it, the next block doesn't.
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(book()).with(from("2001:db8:7:1::" + (i + 1)))).andExpect(status().isNotFound());
        }
        mvc.perform(get(book()).with(from("2001:db8:7:1:ffff::9"))).andExpect(status().isTooManyRequests());
        mvc.perform(get(book()).with(from("2001:db8:7:2::1"))).andExpect(status().isNotFound());
    }

    @Test
    void eachRouteHasItsOwnBudget() throws Exception {
        String viewed = "/api/public/match-requests/tok_" + UUID.randomUUID().toString().replace("-", "") + "/viewed";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(viewed).with(from("203.0.113.43"))).andExpect(status().isNotFound());
        }
        mvc.perform(post(viewed).with(from("203.0.113.43")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
        mvc.perform(get(book()).with(from("203.0.113.43"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/match-requests/tok_unknown").with(from("203.0.113.43"))).andExpect(status().isNotFound());
    }

    @Test
    void anEncodedPathIsStillTheSameRoute() throws Exception {
        // Spring matches /api/public/b%6Fok/... to the booking redirect, so the limiter must count it there too.
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(book()).with(from("203.0.113.44"))).andExpect(status().isNotFound());
        }
        mvc.perform(get(URI.create(book().replace("/book/", "/b%6Fok/"))).with(from("203.0.113.44"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void signedInRoutesAreUntouched() throws Exception {
        TestApi api = new TestApi(mvc, security);
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/admin/metrics").with(from("203.0.113.45"))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/metrics").with(from("203.0.113.45")).with(api.admin())).andExpect(status().isOk());
        }
    }
}
