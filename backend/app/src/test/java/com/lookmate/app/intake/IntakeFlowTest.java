package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.GpsDirectory;
import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Client intake end to end against Postgres, with object storage in memory (the real-storage path is
 * {@link InspirationStorageTest}): the quiz, the link, the upload check, the matching queue and retention.
 */
@SpringBootTest(properties = "lookmate.retention.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IntakeFlowTest.Fakes.class})
@Testcontainers(disabledWithoutDocker = true)
class IntakeFlowTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        InMemoryBlobStore inMemoryBlobStore() {
            return new InMemoryBlobStore();
        }

        @Bean
        @Primary
        ClientMatchList testMatchList() {
            return id -> List.of(new ClientMatch(UUID.randomUUID(), 1, "Curl Studio", "Short North", "12 similar cuts on curly hair",
                    List.of(), "/api/public/book/x", "curlstudio"));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemoryBlobStore blobs;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MatchRequests requests;

    @Autowired
    private InspirationRetention retention;

    static String quiz(String email, String firstName) {
        return """
                {"email":"%s","firstName":"%s","hairType":"CURLY","texture":"THICK","length":"MEDIUM","budgetTier":2,
                 "neighborhood":"Clintonville","service":"CUT","pastIssues":["DIDNT_LISTEN","NO_EXPERIENCE_WITH_MY_HAIR_TYPE"],
                 "pastIssuesNote":"They thinned it out without asking","inspiration":{"contentType":"image/jpeg","sizeBytes":5000}}"""
                .formatted(email, firstName);
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/public/match-requests").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String newRequest(String email, String firstName) throws Exception {
        return TestApi.read(create(quiz(email, firstName)).andExpect(status().isCreated()), "$.token");
    }

    private UUID idOf(String token) {
        return jdbc.sql("select match_request_id from match_request_link where token_hash = :h").param("h", LinkTokens.hash(token))
                .query(UUID.class).single();
    }

    private ResultActions view(String token) throws Exception {
        return mvc.perform(get("/api/public/match-requests/" + token));
    }

    private ResultActions confirm(String token) throws Exception {
        return mvc.perform(post("/api/public/match-requests/" + token + "/inspiration/confirm"));
    }

    private void upload(String token, byte[] bytes) {
        blobs.upload(storage.photoBucket(), IntakeService.objectKey(idOf(token)), bytes, "image/jpeg");
    }

    @Test
    void theQuizCreatesARequestWithAnUploadSlotAndEmailsTheLink() throws Exception {
        ResultActions created = create(quiz("ada@example.com", "Ada")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.headers.If-None-Match").value("*"));
        String token = TestApi.read(created, "$.token");
        UUID id = idOf(token);
        assertThat(TestApi.read(created, "$.statusPath")).isEqualTo("/m/" + token);
        assertThat(TestApi.read(created, "$.upload.url")).contains("intake/" + id + "/inspiration");

        var row = jdbc.sql("select r.status, r.market, r.service, r.access_token_hash, c.hair_type, c.past_issues::text as issues, c.notes "
                + "from match_request r join client c on c.id = r.client_id where r.id = :id").param("id", id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("market")).isEqualTo("columbus-oh");
        assertThat(row.get("service")).isEqualTo("CUT");
        assertThat(row.get("hair_type")).isEqualTo("CURLY");
        assertThat(row.get("issues")).isEqualTo("{DIDNT_LISTEN,NO_EXPERIENCE_WITH_MY_HAIR_TYPE}");
        assertThat(row.get("notes")).isEqualTo("They thinned it out without asking");
        assertThat(row.get("access_token_hash")).as("only the hash is stored").isEqualTo(LinkTokens.hash(token)).isNotEqualTo(token);

        var email = jdbc.sql("select template, recipient_email, text_body from email_message where subject_id = :id").param("id", id)
                .query().singleRow();
        assertThat(email.get("template")).isEqualTo("MATCH_REQUEST_RECEIVED");
        assertThat(email.get("recipient_email")).isEqualTo("ada@example.com");
        assertThat((String) email.get("text_body")).contains("/m/" + token).contains("Hi Ada");

        view(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("PHOTO_NEEDED"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.photoUploaded").value(false))
                .andExpect(jsonPath("$.matches").isEmpty())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void theQuizIsValidated() throws Exception {
        create(quiz("not-an-email", "Ada")).andExpect(status().isBadRequest());
        create(quiz("ada@example.com", "Ada").replace("\"CURLY\"", "\"FRIZZY\"")).andExpect(status().isBadRequest());
        create(quiz("ada@example.com", "Ada").replace("image/jpeg", "image/heic")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PHOTO"));
        create(quiz("ada@example.com", "Ada").replace("5000", "999999999")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aWrongOrExpiredTokenIsNotFound() throws Exception {
        String token = newRequest("bo@example.com", "Bo");
        view(LinkTokens.newToken()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        view("short").andExpect(status().isNotFound());
        confirm(LinkTokens.newToken()).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/match-requests/" + LinkTokens.newToken() + "/inspiration").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/jpeg\",\"sizeBytes\":10}")).andExpect(status().isNotFound());

        view(token).andExpect(status().isOk());
        jdbc.sql("update match_request_link set expires_at = now() - interval '1 second' where token_hash = :h")
                .param("h", LinkTokens.hash(token)).update();
        view(token).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        confirm(token).andExpect(status().isNotFound());
    }

    @Test
    void oneClientsTokenNeverReadsAnothersRequest() throws Exception {
        String cleo = newRequest("cleo@example.com", "Cleo");
        String dev = newRequest("dev@example.com", "Dev");
        upload(dev, TestPhotos.jpegWithGps());

        confirm(cleo).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_UPLOADED"));
        view(cleo).andExpect(jsonPath("$.firstName").value("Cleo")).andExpect(jsonPath("$.photoUploaded").value(false));
        view(dev).andExpect(jsonPath("$.firstName").value("Dev")).andExpect(jsonPath("$.photoUploaded").value(false));
        assertThat(requests.view(idOf(dev)).orElseThrow().inspirationReady()).isFalse();
    }

    @Test
    void confirmingKeepsThePhotoWithoutItsLocation() throws Exception {
        String token = newRequest("eve@example.com", "Eve");
        UUID id = idOf(token);
        byte[] original = TestPhotos.jpegWithGps();
        upload(token, original);

        confirm(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("RECEIVED"))
                .andExpect(jsonPath("$.photoUploaded").value(true))
                .andExpect(jsonPath("$.photoUrl").isNotEmpty())
                .andExpect(jsonPath("$.photoDeletesAt").isNotEmpty());
        byte[] kept = blobs.bytes(storage.photoBucket(), IntakeService.objectKey(id)).orElseThrow();
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(kept)).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        String sha = jdbc.sql("select inspiration_sha256 from match_request where id = :id").param("id", id).query(String.class).single();
        assertThat(sha).isEqualTo(LinkTokens.sha256(kept));

        confirm(token).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("RECEIVED"));
        mvc.perform(post("/api/public/match-requests/" + token + "/inspiration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"sizeBytes\":10}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHOTO_ALREADY_CONFIRMED"));

        MatchRequests.Inspiration inspiration = requests.inspirationBytes(id).orElseThrow();
        assertThat(inspiration.bytes()).isEqualTo(kept);
        assertThat(inspiration.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void aFileThatIsntAPhotoIsRefusedAndDeleted() throws Exception {
        String token = newRequest("fay@example.com", "Fay");
        upload(token, "<html>not a photo</html>".getBytes());

        confirm(token).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("IMAGE_REJECTED"));
        assertThat(blobs.head(storage.photoBucket(), IntakeService.objectKey(idOf(token)))).isEmpty();
        mvc.perform(post("/api/public/match-requests/" + token + "/inspiration").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"sizeBytes\":1234}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.method").value("PUT"));
    }

    @Test
    void oneAddressMayHaveOnlyAFewOpenRequestsADay() throws Exception {
        newRequest("gus@example.com", "Gus");
        newRequest("Gus@Example.com", "Gus");
        newRequest("gUs@example.COM", "Gus");
        create(quiz("GUS@example.com", "Gus")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_OPEN_REQUESTS"));
        newRequest("someone-else@example.com", "Hal");
    }

    @Test
    void matchingClaimsConfirmedRequestsOnceAndPublishingEmailsAFreshLink() throws Exception {
        String waiting = newRequest("ida@example.com", "Ida");
        String ready = newRequest("jo@example.com", "Jo");
        upload(ready, TestPhotos.jpegWithGps());
        confirm(ready).andExpect(status().isOk());
        UUID id = idOf(ready);

        List<MatchRequests.Request> claimed = requests.claimNew(50);
        assertThat(claimed).extracting(MatchRequests.Request::id).contains(id).doesNotContain(idOf(waiting));
        MatchRequests.Request request = claimed.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
        assertThat(request.status()).isEqualTo(MatchRequests.Status.MATCHING);
        assertThat(request.hairType()).isEqualTo("CURLY");
        assertThat(request.pastIssues()).containsExactly("DIDNT_LISTEN", "NO_EXPERIENCE_WITH_MY_HAIR_TYPE");
        assertThat(requests.claimNew(50)).extracting(MatchRequests.Request::id).doesNotContain(id);
        view(ready).andExpect(jsonPath("$.stage").value("MATCHING"));

        assertThatThrownBy(() -> requests.publish(id, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requests.markStatus(id, MatchRequests.Status.PUBLISHED, null)).isInstanceOf(IllegalStateException.class);
        requests.markStatus(id, MatchRequests.Status.IN_REVIEW, "3 stylists found");
        assertThat(requests.list(MatchRequests.Status.IN_REVIEW, 10)).extracting(MatchRequests.Request::id).contains(id);
        view(ready).andExpect(jsonPath("$.stage").value("MATCHING")).andExpect(jsonPath("$.matches").isEmpty());

        requests.publish(id, null);
        view(ready).andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches[0].stylistName").value("Curl Studio"));
        String body = jdbc.sql("select text_body from email_message where subject_id = :id and template = 'MATCHES_READY'").param("id", id)
                .query(String.class).single();
        String fresh = body.replaceAll("(?s).*/m/([A-Za-z0-9_-]{43}).*", "$1");
        assertThat(fresh).isNotEqualTo(ready);
        view(fresh).andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Jo"));
        view(ready).andExpect(status().isOk()); // the first link keeps working
    }

    @Test
    void aStuckClaimIsOfferedAgain() throws Exception {
        String token = newRequest("kai@example.com", "Kai");
        upload(token, TestPhotos.jpegWithGps());
        confirm(token).andExpect(status().isOk());
        UUID id = idOf(token);
        assertThat(requests.claimNew(50)).extracting(MatchRequests.Request::id).contains(id);
        jdbc.sql("update match_request set updated_at = now() - interval '1 hour' where id = :id").param("id", id).update();
        assertThat(requests.claimNew(50)).extracting(MatchRequests.Request::id).contains(id);
    }

    @Test
    void retentionDeletesOldInspirationPhotos() throws Exception {
        String old = newRequest("lea@example.com", "Lea");
        String recent = newRequest("max@example.com", "Max");
        upload(old, TestPhotos.jpegWithGps());
        confirm(old).andExpect(status().isOk());
        upload(recent, TestPhotos.jpegWithGps());
        confirm(recent).andExpect(status().isOk());
        UUID oldId = idOf(old);
        jdbc.sql("update match_request set created_at = now() - interval '31 days' where id = :id").param("id", oldId).update();

        assertThat(retention.run(Instant.now())).isEqualTo(1);

        assertThat(blobs.head(storage.photoBucket(), IntakeService.objectKey(oldId))).isEmpty();
        assertThat(blobs.head(storage.photoBucket(), IntakeService.objectKey(idOf(recent)))).isPresent();
        view(old).andExpect(jsonPath("$.photoDeleted").value(true)).andExpect(jsonPath("$.photoUrl").value(nullValue()));
        assertThat(requests.inspirationBytes(oldId)).isEmpty();
        assertThat(requests.view(oldId).orElseThrow().inspirationDeleted()).isTrue();
        assertThat(requests.claimNew(50)).extracting(MatchRequests.Request::id).doesNotContain(oldId);
        assertThat(retention.run(Instant.now())).as("nothing left to do").isZero();
        assertThat(retention.run(Instant.now().plus(Duration.ofDays(31)))).isGreaterThanOrEqualTo(1);
        assertThat(blobs.head(storage.photoBucket(), IntakeService.objectKey(idOf(recent)))).isEmpty();
    }
}
