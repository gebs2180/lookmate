package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.storage.PhotoDecodes;
import com.lookmate.app.storage.StorageProperties;
import com.lookmate.app.storage.TestPhotos;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Confirming waits a short while for a turn to decode the photo and says so when none comes up (review 2026-11-22,
 * finding 1): it used to wait for a turn holding a database connection and a row lock.
 */
@SpringBootTest(properties = {
        "lookmate.intake.confirm-wait=200ms",
        "lookmate.storage.photo-checks-at-once=1",
        "lookmate.retention.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IntakeFlowTest.Fakes.class})
@Testcontainers(disabledWithoutDocker = true)
class IntakeBusyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemoryBlobStore blobs;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private PhotoDecodes decodes;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void confirmingWhileEveryDecodeIsTakenAsksTheClientToTryAgain() throws Exception {
        String token = TestApi.read(mvc.perform(post("/api/public/match-requests").contentType(MediaType.APPLICATION_JSON)
                .content(IntakeFlowTest.quiz(null, ""))).andExpect(status().isCreated()), "$.token");
        UUID id = jdbc.sql("select match_request_id from match_request_link where token_hash = :h").param("h", LinkTokens.hash(token))
                .query(UUID.class).single();
        blobs.upload(storage.photoBucket(), IntakeService.objectKey(id), TestPhotos.jpegWithGps(), "image/jpeg");

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> decodes.withTurn(Duration.ofSeconds(5), () -> {
            holding.countDown();
            try {
                return release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }));
        try {
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/public/match-requests/" + token + "/inspiration/confirm"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("BUSY"));
        } finally {
            release.countDown();
            holder.join();
        }

        // Nothing was changed, so trying again once a turn is free works.
        mvc.perform(post("/api/public/match-requests/" + token + "/inspiration/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("RECEIVED"));
    }
}
