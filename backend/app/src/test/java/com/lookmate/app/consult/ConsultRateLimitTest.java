package com.lookmate.app.consult;

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

/** The finder's public endpoints trip a per-IP limit: 429 with Retry-After, and other addresses carry on. */
@SpringBootTest(properties = {
        "lookmate.consult.rate-limit.submits-per-hour=2",
        "lookmate.consult.rate-limit.reads-per-minute=3",
        "lookmate.retention.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ConsultRateLimitTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void submittingTooOftenFromOneAddressIsRefused() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/public/consult/sessions").with(from("203.0.113.40"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{}}")).andExpect(status().isCreated());
        }
        mvc.perform(post("/api/public/consult/sessions").with(from("203.0.113.40"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{}}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().string("Retry-After", "1800"));
        mvc.perform(post("/api/public/consult/sessions").with(from("203.0.113.41"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"answers\":{}}")).andExpect(status().isCreated());
    }

    @Test
    void readingTooOftenFromOneAddressIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/public/consult/finder").with(from("203.0.113.50"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/public/consult/finder").with(from("203.0.113.50"))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/public/consult/finder").with(from("203.0.113.51"))).andExpect(status().isOk());
    }
}
