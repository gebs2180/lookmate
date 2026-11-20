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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Intake's public routes under the shared limiter (api/PublicRateLimitFilter): new requests per IP and per email
 * address, and everything else per IP. 429 with Retry-After; other addresses carry on.
 */
@SpringBootTest(properties = {
        "lookmate.rate-limit.create-request.requests=2",
        "lookmate.rate-limit.create-request.per=1h",
        "lookmate.rate-limit.create-request-per-email.requests=2",
        "lookmate.rate-limit.create-request-per-email.per=1d",
        "lookmate.rate-limit.other.requests=3",
        "lookmate.rate-limit.other.per=1m",
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

    private ResultActions create(String ip, String json) throws Exception {
        return mvc.perform(post("/api/public/match-requests").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void creatingTooOftenFromOneAddressIsRefused() throws Exception {
        create("203.0.113.7", IntakeFlowTest.quiz("ada@example.com", "Ada")).andExpect(status().isCreated());
        create("203.0.113.7", IntakeFlowTest.quiz("bea@example.com", "Bea")).andExpect(status().isCreated());
        create("203.0.113.7", IntakeFlowTest.quiz("cy@example.com", "Cy"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "1800"));   // 2 an hour: one back every half hour
        create("203.0.113.8", IntakeFlowTest.quiz("cy@example.com", "Cy")).andExpect(status().isCreated());
    }

    @Test
    void oneEmailAddressGetsALimitedNumberOfRequestsFromAnyAddress() throws Exception {
        create("198.51.100.21", IntakeFlowTest.quiz("Zoe@Example.com", "Zoe")).andExpect(status().isCreated());
        create("198.51.100.22", IntakeFlowTest.quiz("zoe+hair@example.com", "Zoe")).andExpect(status().isCreated());
        create("198.51.100.23", IntakeFlowTest.quiz(" zoe@example.com ", "Zoe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value("Too many requests for this email address today. Try again tomorrow."))
                .andExpect(header().string("Retry-After", "43200"));   // 2 a day: one back every 12 hours
        create("198.51.100.23", IntakeFlowTest.quiz("yan@example.com", "Yan")).andExpect(status().isCreated());
    }

    @Test
    void refusedRequestsDoNotSpendTheEmailBudget() throws Exception {
        // Someone else trying an address (here, without the required hair type) mustn't use up the real client's tries.
        String invalid = IntakeFlowTest.quiz("kai@example.com", "Kai").replace("\"hairType\":\"CURLY\",", "");
        for (String ip : new String[] {"192.0.2.31", "192.0.2.32", "192.0.2.33"}) {
            create(ip, invalid).andExpect(status().isBadRequest());
        }
        create("192.0.2.34", IntakeFlowTest.quiz("kai@example.com", "Kai")).andExpect(status().isCreated());
        create("192.0.2.35", IntakeFlowTest.quiz("kai@example.com", "Kai")).andExpect(status().isCreated());
    }

    @Test
    void guessingLinksFromOneAddressIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.4"))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.4")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"));
        mvc.perform(get("/api/public/match-requests/" + LinkTokens.newToken()).with(from("198.51.100.5"))).andExpect(status().isNotFound());
    }
}
