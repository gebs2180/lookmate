package com.lookmate.app.intake;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The public endpoints (intake, the stylist directory) trip a per-IP limit: 429 with Retry-After, and other addresses carry on. */
@SpringBootTest(properties = {
        "lookmate.intake.rate-limit.creates-per-hour=2",
        "lookmate.intake.rate-limit.reads-per-minute=3",
        "lookmate.intake.rate-limit.browse-per-minute=3",
        "lookmate.intake.max-open-requests-per-email-per-day=100",
        "lookmate.retention.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IntakeFlowTest.Fakes.class})
@Testcontainers(disabledWithoutDocker = true)
class IntakeRateLimitTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void creatingTooOftenFromOneAddressIsRefused() throws Exception {
        String quiz = IntakeFlowTest.quiz("nia@example.com", "Nia");
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/public/match-requests").with(from("203.0.113.7")).contentType(MediaType.APPLICATION_JSON).content(quiz))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/public/match-requests").with(from("203.0.113.7")).contentType(MediaType.APPLICATION_JSON).content(quiz))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "1800"));
        mvc.perform(post("/api/public/match-requests").with(from("203.0.113.8")).contentType(MediaType.APPLICATION_JSON).content(quiz))
                .andExpect(status().isCreated());
    }

    @Test
    void guessingLinksFromOneAddressIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.4"))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.4")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.5"))).andExpect(status().isNotFound());
    }

    @Test
    void browsingTheDirectoryFromOneAddressIsLimitedOnItsOwn() throws Exception {
        // The directory and its Book links share one bucket, separate from intake's.
        mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("192.0.2.9"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists").with(from("192.0.2.9"))).andExpect(status().isOk());
        mvc.perform(get("/api/public/stylists?page=1").with(from("192.0.2.9"))).andExpect(status().isOk());
        mvc.perform(get("/api/public/book-stylist/" + LinkTokens.newToken() + "/" + java.util.UUID.randomUUID()).with(from("192.0.2.9")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists").with(from("192.0.2.9")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/public/book-stylist/" + LinkTokens.newToken() + "/" + java.util.UUID.randomUUID()).with(from("192.0.2.9")))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/public/stylists").with(from("192.0.2.10"))).andExpect(status().isOk());
    }
}
