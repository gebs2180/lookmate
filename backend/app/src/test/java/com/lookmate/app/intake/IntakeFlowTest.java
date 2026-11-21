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
import com.lookmate.app.storage.TestPhotos;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
@SpringBootTest(properties = {
        "lookmate.intake.rate-limit.creates-per-hour=1000",
        "lookmate.intake.rate-limit.creates-per-day=1000",
        "lookmate.intake.rate-limit.emails-per-hour=1000",
        "lookmate.intake.rate-limit.reads-per-minute=1000",
        "lookmate.retention.enabled=false"})
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
            return id -> List.of(new ClientMatch(UUID.randomUUID(), UUID.randomUUID(), 1, "Curl Studio", "Short North",
                    "12 similar cuts on curly hair", List.of(), "curlstudio", false));
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

    @Autowired
    private ClientContactRetention contactRetention;

    /** The quiz's answers; {@code email} null leaves it out, as the quiz does now. */
    static String quiz(String email, String firstName) {
        return """
                {%s"firstName":"%s","hairType":"CURLY","texture":"THICK","length":"MEDIUM","budgetTier":2,
                 "neighborhood":"Clintonville","service":"CUT","pastIssues":["DIDNT_LISTEN","NO_EXPERIENCE_WITH_MY_HAIR_TYPE"],
                 "pastIssuesNote":"They thinned it out without asking","inspiration":{"contentType":"image/jpeg","sizeBytes":5000}}"""
                .formatted(email == null ? "" : "\"email\":\"%s\",".formatted(email), firstName);
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/public/match-requests").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String newRequest(String email, String firstName) throws Exception {
        return TestApi.read(create(quiz(email, firstName)).andExpect(status().isCreated()), "$.token");
    }

    private ResultActions setEmail(String token, String email) throws Exception {
        return mvc.perform(post("/api/public/match-requests/" + token + "/email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)));
    }

    private List<Map<String, Object>> emailsFor(UUID id) {
        return jdbc.sql("select template, recipient_email, text_body from email_message where subject_id = :id order by created_at")
                .param("id", id).query().listOfRows();
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
    void theQuizCreatesARequestWithoutAnEmail() throws Exception {
        ResultActions created = create(quiz(null, "Ada")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.headers.If-None-Match").value("*"));
        String token = TestApi.read(created, "$.token");
        UUID id = idOf(token);
        assertThat(TestApi.read(created, "$.statusPath")).isEqualTo("/m/" + token);
        assertThat(TestApi.read(created, "$.upload.url")).contains("intake/" + id + "/inspiration");

        var row = jdbc.sql("select r.status, r.market, r.service, r.access_token_hash, c.email, c.email_changes, c.hair_type, "
                + "c.past_issues::text as issues, c.notes from match_request r join client c on c.id = r.client_id where r.id = :id")
                .param("id", id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("market")).isEqualTo("columbus-oh");
        assertThat(row.get("service")).isEqualTo("CUT");
        assertThat(row.get("email")).isNull();
        assertThat(((Number) row.get("email_changes")).intValue()).isZero();
        assertThat(row.get("hair_type")).isEqualTo("CURLY");
        assertThat(row.get("issues")).isEqualTo("{DIDNT_LISTEN,NO_EXPERIENCE_WITH_MY_HAIR_TYPE}");
        assertThat(row.get("notes")).isEqualTo("They thinned it out without asking");
        assertThat(row.get("access_token_hash")).as("only the hash is stored").isEqualTo(LinkTokens.hash(token)).isNotEqualTo(token);
        assertThat(emailsFor(id)).as("no address, so nothing is queued").isEmpty();

        view(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("PHOTO_NEEDED"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.photoUploaded").value(false))
                .andExpect(jsonPath("$.photoKeptDays").value(30))
                .andExpect(jsonPath("$.matches").isEmpty())
                .andExpect(jsonPath("$.emailHint").value(nullValue()))
                .andExpect(jsonPath("$.emailChangeAllowed").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
        // No name either.
        create(quiz(null, "").replace("\"firstName\":\"\",", "")).andExpect(status().isCreated());
    }

    @Test
    void anEmailGivenWithTheQuizStillGetsTheLink() throws Exception {
        String token = newRequest("ada@example.com", "Ada");
        var emails = emailsFor(idOf(token));
        assertThat(emails).hasSize(1);
        assertThat(emails.getFirst().get("template")).isEqualTo("MATCH_LIST_LINK");
        assertThat(emails.getFirst().get("recipient_email")).isEqualTo("ada@example.com");
        assertThat((String) emails.getFirst().get("text_body")).contains("/m/" + token).contains("Hi Ada");
        view(token).andExpect(jsonPath("$.emailHint").value("a\u2022\u2022\u2022@example.com"));
    }

    @Test
    void theClientAddsAnEmailOnTheirPageAndGetsTheLink() throws Exception {
        String token = newRequest(null, "Bea");
        UUID id = idOf(token);

        setEmail(token, "bea@example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.emailHint").value("b\u2022\u2022\u2022@example.com"))
                .andExpect(jsonPath("$.emailChangeAllowed").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
        var emails = emailsFor(id);
        assertThat(emails).hasSize(1);
        assertThat(emails.getFirst().get("template")).isEqualTo("MATCH_LIST_LINK");
        assertThat(emails.getFirst().get("recipient_email")).isEqualTo("bea@example.com");
        assertThat((String) emails.getFirst().get("text_body")).contains("/m/" + token).contains("Hi Bea");
        view(token).andExpect(jsonPath("$.emailHint").value("b\u2022\u2022\u2022@example.com"));

        // Correcting a typo replaces it and sends the link to the new address, up to the per-request cap; then 409.
        setEmail(token, "bea2@example.com").andExpect(status().isOk());
        setEmail(token, "bea3@example.com").andExpect(status().isOk()).andExpect(jsonPath("$.emailChangeAllowed").value(false));
        setEmail(token, "bea4@example.com").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_CHANGE_LIMIT"));
        assertThat(emailsFor(id)).extracting(e -> e.get("recipient_email"))
                .containsExactly("bea@example.com", "bea2@example.com", "bea3@example.com");
        assertThat(jdbc.sql("select c.email from client c join match_request r on r.client_id = c.id where r.id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("bea3@example.com");

        setEmail(token, "not-an-email").andExpect(status().isBadRequest());
        setEmail(LinkTokens.newToken(), "bea@example.com").andExpect(status().isNotFound());
    }

    @Test
    void oneAddressCantBeGivenToManyRequestsADay() throws Exception {
        for (int i = 0; i < 5; i++) {
            setEmail(newRequest(null, "Gus"), i % 2 == 0 ? "gus@example.com" : "Gus@Example.com").andExpect(status().isOk());
        }
        setEmail(newRequest(null, "Gus"), "GUS@example.com").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_FOR_ADDRESS"));
        create(quiz("gUs@example.COM", "Gus")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_FOR_ADDRESS"));
        setEmail(newRequest(null, "Hal"), "someone-else@example.com").andExpect(status().isOk());
    }

    @Test
    void anEmailAddedAfterPublishingLinksToTheList() throws Exception {
        String token = newRequest(null, "Ivy");
        upload(token, TestPhotos.jpegWithGps());
        confirm(token).andExpect(status().isOk());
        UUID id = idOf(token);
        requests.claimNew(50);
        requests.publish(id, null);
        assertThat(emailsFor(id)).as("no address when it was published: nothing queued").isEmpty();

        setEmail(token, "ivy@example.com").andExpect(status().isOk());
        var emails = emailsFor(id);
        assertThat(emails).hasSize(1);
        assertThat((String) emails.getFirst().get("text_body")).contains("/m/" + token).contains("We may ask how your appointment went.");
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

        // A request still waiting for its photo can't be published; one being matched can (the ranker publishing on its own).
        assertThatThrownBy(() -> requests.publish(idOf(waiting), null)).isInstanceOf(IllegalStateException.class);
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

        assertThat(retention.run(Instant.now())).isGreaterThanOrEqualTo(1);

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

    @Test
    void theQuizIsToldHowLongPhotosAreKept() throws Exception {
        mvc.perform(get("/api/public/intake/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.photoKeptDays").value(30))
                .andExpect(jsonPath("$.maxPhotoBytes").value(storage.maxUploadBytes()));
    }

    @Test
    void contactDetailsGoAYearAfterTheLatestRequest() throws Exception {
        String old = newRequest("pam@example.com", "Pam");
        String oldButReturning = newRequest("quin@example.com", "Quin");
        String returning = newRequest("Quin@example.com", "Quin");
        // No email: the request's own age decides.
        String anonymous = newRequest(null, "Rae");
        String anonymousRecent = newRequest(null, "Sol");
        List<UUID> aged = List.of(idOf(old), idOf(oldButReturning), idOf(anonymous));
        jdbc.sql("update match_request set created_at = now() - interval '400 days' where id in (:ids)").param("ids", aged).update();
        jdbc.sql("update client set created_at = now() - interval '400 days' where id in "
                + "(select client_id from match_request where id in (:ids))").param("ids", aged).update();

        assertThat(contactRetention.run(Instant.now())).isEqualTo(2);

        var pam = jdbc.sql("select c.email, c.first_name, c.neighborhood, c.notes, c.hair_type, c.anonymised_at from client c "
                + "join match_request r on r.client_id = c.id where r.id = :id").param("id", idOf(old)).query().singleRow();
        assertThat(pam.get("email")).isNull();
        assertThat(pam.get("first_name")).isNull();
        assertThat(pam.get("neighborhood")).isNull();
        assertThat(pam.get("notes")).isNull();
        assertThat(pam.get("hair_type")).as("kept for the fairness metrics").isEqualTo("CURLY");
        assertThat(pam.get("anonymised_at")).isNotNull();
        view(old).andExpect(jsonPath("$.firstName").value(nullValue()));
        view(oldButReturning).andExpect(jsonPath("$.firstName").value("Quin"));
        view(returning).andExpect(jsonPath("$.firstName").value("Quin"));
        view(anonymous).andExpect(jsonPath("$.firstName").value(nullValue()));
        view(anonymousRecent).andExpect(jsonPath("$.firstName").value("Sol"));
        assertThat(contactRetention.run(Instant.now())).as("nothing left to do").isZero();
    }
}
