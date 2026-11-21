package com.lookmate.app.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import com.lookmate.app.storage.BlobStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The default since 2026-11-20 (review switch off): the ranker publishes its own list, the client's "matches ready"
 * email waits a few minutes and isn't sent once they've opened the list, and the admin acts after the fact (hiding a
 * match or a stylist) from the monitor. Sending is on here, with a send interval no test reaches, so the held email
 * stays PENDING where a test can see it. {@link MatchingFlowTest} covers the switch on.
 */
@SpringBootTest(properties = {
        "lookmate.matching.worker.enabled=false",
        "lookmate.intake.rate-limit.reads-per-minute=1000",
        "lookmate.intake.rate-limit.browse-per-minute=1000",
        "lookmate.intake.matches-ready-email-delay=5m",
        "lookmate.notify.email.enabled=true",
        "lookmate.notify.email.api-key=test-key",
        "lookmate.notify.email.base-url=http://127.0.0.1:9",
        "lookmate.notify.email.poll-interval=1h"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AutoPublishFlowTest.Images.class})
@Testcontainers(disabledWithoutDocker = true)
class AutoPublishFlowTest {

    static final MemoryBlobStore BLOBS = new MemoryBlobStore();

    @TestConfiguration(proxyBeanMethods = false)
    static class Images {
        @Bean
        @Primary
        BlobStore memoryBlobs() {
            return BLOBS;
        }
    }

    private static final byte[] LOOK = "the look".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SecurityProperties security;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private MatchingJob job;

    private String market;
    private TestApi api;
    private UUID uploader;
    private UUID location;
    private int photos;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        market = "test-" + UUID.randomUUID();
        uploader = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, subject, created_at) values (?, ?, now())").params(uploader, "seed|" + uploader).update();
        location = UUID.randomUUID();
        jdbc.sql("insert into salon_location (id, brand, name, market, created_at) values (?, 'SALON_LOFTS', 'Short North', ?, now())")
                .params(location, market).update();
    }

    @Test
    void theRankerPublishesItsOwnListAndTheEmailWaitsUntilTheClientHasntLooked() throws Exception {
        UUID expert = stylist("Wavy Expert", "LIVE");
        photo(expert, "CUT", "WAVY", LOOK);
        photo(expert, "CUT", "WAVY", LOOK);
        photo(expert, "CUT", "WAVY", LOOK);
        UUID neighbour = stylist("Curl Neighbour", "LIVE");
        photo(neighbour, "CUT", "CURLY", LOOK);
        photo(neighbour, "CUT", "CURLY", LOOK);
        String token = token();
        UUID request = request("WAVY", "CUT", token, "client@example.com");

        // While matching, the client's page says so and has no list.
        mvc.perform(get("/api/public/match-requests/" + token)).andExpect(jsonPath("$.stage").value("RECEIVED"));
        Instant before = Instant.now();
        job.runOnce();

        assertThat(statusOf(request)).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("select reviewed_by is null and published_at is not null from match_request where id = ?").params(request)
                .query(Boolean.class).single()).isTrue();
        // Nobody reviewed it: the final ranking is the system ranking.
        List<Map<String, Object>> rows = jdbc.sql("select system_rank, final_rank, admin_override from match where match_request_id = ? order by system_rank")
                .params(request).query().listOfRows();
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("final_rank")).isEqualTo(r.get("system_rank"));
            assertThat(r.get("admin_override")).isEqualTo(false);
        });

        var view = mvc.perform(get("/api/public/match-requests/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Wavy Expert", "Curl Neighbour")))
                .andExpect(jsonPath("$.matches[0].orgId").value(expert.toString()))
                .andExpect(jsonPath("$.matches[0].demo").value(false))
                .andExpect(jsonPath("$..score").isEmpty());
        String expertMatch = TestApi.read(view, "$.matches[0].matchId");

        // The email is queued but held for the delay.
        Map<String, Object> email = matchesReadyEmail(request);
        assertThat(email.get("status")).isEqualTo("PENDING");
        Instant due = ((Timestamp) email.get("next_attempt_at")).toInstant();
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(4)), Instant.now().plus(Duration.ofMinutes(5)));

        // The fresh link in that email opens the list and works for Book (tracking resolves it through intake).
        String fresh = ((String) email.get("text_body")).replaceAll("(?s).*/m/([A-Za-z0-9_-]{43}).*", "$1");
        assertThat(fresh).isNotEqualTo(token);
        mvc.perform(get("/api/public/book/" + fresh + "/" + expertMatch)).andExpect(status().isFound());

        // The client opens the list (the page posts the view): the held email is cancelled.
        mvc.perform(post("/api/public/match-requests/" + token + "/viewed")).andExpect(status().isNoContent());
        email = matchesReadyEmail(request);
        assertThat(email.get("status")).isEqualTo("CANCELLED");
        assertThat(email.get("status_reason")).isEqualTo("the client opened their matches before it went");

        // The monitor shows what the client saw and did.
        api.get("/api/admin/match-monitor?market=" + market, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewBeforePublish").value(false))
                .andExpect(jsonPath("$.requests[0].id").value(request.toString()))
                .andExpect(jsonPath("$.requests[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.requests[0].firstViewedAt").isNotEmpty())
                .andExpect(jsonPath("$.requests[0].adminChanged").value(false))
                .andExpect(jsonPath("$.requests[0].matches[0].stylistName").value("Wavy Expert"))
                .andExpect(jsonPath("$.requests[0].matches[0].finalRank").value(1))
                .andExpect(jsonPath("$.requests[0].matches[0].bookingClicks").value(1))
                .andExpect(jsonPath("$.requests[0].matches[1].bookingClicks").value(0));

        // With no review, the review endpoints that reshape a list are refused after publishing.
        api.postJson("/api/admin/match-requests/" + request + "/publish", "", api.admin())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_IN_REVIEW"));
    }

    @Test
    void anEmailIsNotCancelledByAViewOfSomeoneElsesList() throws Exception {
        UUID expert = stylist("Expert", "LIVE");
        photo(expert, "CUT", "WAVY", LOOK);
        String mine = token();
        String theirs = token();
        UUID myRequest = request("WAVY", "CUT", mine, "me@example.com");
        UUID theirRequest = request("WAVY", "CUT", theirs, "them@example.com");
        job.runOnce();
        mvc.perform(post("/api/public/match-requests/" + theirs + "/viewed")).andExpect(status().isNoContent());
        assertThat(matchesReadyEmail(myRequest).get("status")).isEqualTo("PENDING");
        assertThat(matchesReadyEmail(theirRequest).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    void theAdminHidesAMatchOrAStylistAfterPublishing() throws Exception {
        UUID first = stylist("First", "LIVE");
        photo(first, "CUT", "WAVY", LOOK);
        photo(first, "CUT", "WAVY", LOOK);
        UUID second = stylist("Second", "LIVE");
        photo(second, "CUT", "WAVY", LOOK);
        UUID third = stylist("Third", "LIVE");
        photo(third, "CUT", "CURLY", LOOK);
        String token = token();
        UUID request = request("WAVY", "CUT", token, null);
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("PUBLISHED");
        var review = api.get("/api/admin/match-requests/" + request, api.admin()).andExpect(status().isOk());
        String firstMatch = TestApi.read(review, "$.matches[0].id");

        // Hiding a published match takes it off the client's list at once; the rest are renumbered.
        mvc.perform(put("/api/admin/match-requests/" + request + "/matches/" + firstMatch).with(api.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true,\"note\":\"Not a fit\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Second", "Third")))
                .andExpect(jsonPath("$.matches[0].rank").value(1));
        mvc.perform(get("/api/public/book/" + token + "/" + firstMatch)).andExpect(status().isNotFound());
        // Reordering stays a review-only action.
        mvc.perform(put("/api/admin/match-requests/" + request + "/order").with(api.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchIds\":[\"%s\"]}".formatted(firstMatch)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_IN_REVIEW"));
        // Shown again, it comes back.
        mvc.perform(put("/api/admin/match-requests/" + request + "/matches/" + firstMatch).with(api.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("First", "Second", "Third")));

        // Hiding the stylist takes them out of every result: this client's list and the directory.
        api.postJson("/api/admin/stylists/" + second + "/hide", "", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("First", "Third")));
        mvc.perform(get("/api/public/stylists?market=" + market))
                .andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("First", "Third")));
        api.postJson("/api/admin/stylists/" + third + "/hide", "", api.user("auth0|someone", List.of()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRequestWithNoCandidatesIsPublishedEmptyAndTheAdminCanAddPicksAfterwards() throws Exception {
        UUID straight = stylist("Straight Only", "LIVE");
        photo(straight, "CUT", "STRAIGHT", LOOK);
        String token = token();
        UUID request = request("COILY", "CUT", token, "c@example.com");
        job.runOnce();

        // Published at once with nothing on it: the client's page stops waiting.
        assertThat(statusOf(request)).isEqualTo("PUBLISHED");
        mvc.perform(get("/api/public/match-requests/" + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches").isEmpty());

        // The "no close match yet" email, held like the usual one, links to the directory through a fresh private link.
        Map<String, Object> email = matchesReadyEmail(request);
        assertThat(email.get("status")).isEqualTo("PENDING");
        String text = (String) email.get("text_body");
        assertThat(text).contains("didn't find a close match", "Browse all stylists").doesNotContain("We've picked stylists");
        String fresh = text.replaceAll("(?s).*/m/([A-Za-z0-9_-]{43})/stylists.*", "$1");
        assertThat(fresh).hasSize(43).isNotEqualTo(token);
        mvc.perform(get("/api/public/match-requests/" + fresh + "/stylists")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // The monitor keeps the reason, so the admin knows to look.
        api.get("/api/admin/match-monitor?market=" + market, api.admin())
                .andExpect(jsonPath("$.requests[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.requests[0].statusReason").value(Ranker.NO_CANDIDATES))
                .andExpect(jsonPath("$.requests[0].matches").isEmpty());

        // The admin adds a pick afterwards: it's on the client's list at once, and the reason is cleared.
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(straight), api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.request.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.request.statusReason").value(Matchers.nullValue()));
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Straight Only")))
                .andExpect(jsonPath("$.matches[0].rank").value(1));
        // Still one "matches ready" email, plus one saying picks were added, with a fresh link to the list.
        assertThat(jdbc.sql("select count(*) from email_message where template = 'MATCHES_READY' and subject_id = ?").params(request)
                .query(Integer.class).single()).isEqualTo(1);
        String picks = jdbc.sql("select text_body from email_message where template = 'PICKS_ADDED' and subject_id = ?").params(request)
                .query(String.class).single();
        String picksLink = picks.replaceAll("(?s).*/m/([A-Za-z0-9_-]{43}).*", "$1");
        assertThat(picksLink).hasSize(43).isNotEqualTo(token);
        mvc.perform(get("/api/public/match-requests/" + picksLink))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Straight Only")));
        // A second pick doesn't email again.
        UUID another = stylist("Another", "LIVE");
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(another), api.admin())
                .andExpect(status().isCreated());
        assertThat(jdbc.sql("select count(*) from email_message where template = 'PICKS_ADDED' and subject_id = ?").params(request)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aClientWithNoEmailIsntEmailedWhenTheirListIsPublishedOrPicksAreAdded() throws Exception {
        UUID straight = stylist("Straight Only", "LIVE");
        photo(straight, "CUT", "STRAIGHT", LOOK);
        String token = token();
        UUID request = request("COILY", "CUT", token, null);
        job.runOnce();

        assertThat(statusOf(request)).isEqualTo("PUBLISHED");
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches").isEmpty())
                .andExpect(jsonPath("$.emailHint").value(Matchers.nullValue()));
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(straight), api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.request.statusReason").value(Matchers.nullValue()));
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Straight Only")));
        // Nothing queued and no extra links: the client sees the picks through the link they kept.
        assertThat(jdbc.sql("select count(*) from email_message where subject_id = ?").params(request).query(Integer.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from match_request_link where match_request_id = ? and purpose = 'MATCHES_READY'")
                .params(request).query(Integer.class).single()).isZero();
    }

    @Test
    void listsLeftInReviewWhenTheSwitchWasOnArePublishedAsTheyStandOnTheNextPass() throws Exception {
        UUID a = stylist("Ana", "LIVE");
        UUID b = stylist("Bo", "LIVE");
        UUID c = stylist("Cy", "LIVE");
        String token = token();
        UUID request = request("WAVY", "CUT", token, "wait@example.com");
        jdbc.sql("update match_request set status = 'IN_REVIEW' where id = ?").params(request).update();
        // The admin's edits while the switch was on: Bo moved up, Ana hidden, Cy added by hand.
        match(request, a, 1, null, true, "SYSTEM");
        match(request, b, 2, 1, false, "SYSTEM");
        match(request, c, 3, 2, false, "ADMIN");
        UUID empty = request("WAVY", "CUT", token(), "empty@example.com");
        jdbc.sql("update match_request set status = 'IN_REVIEW' where id = ?").params(empty).update();
        UUID allHidden = request("WAVY", "CUT", token(), "hidden@example.com");
        jdbc.sql("update match_request set status = 'IN_REVIEW' where id = ?").params(allHidden).update();
        match(allHidden, a, 1, null, true, "SYSTEM");

        job.runOnce();

        assertThat(statusOf(request)).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("select reviewed_by is null from match_request where id = ?").params(request).query(Boolean.class).single())
                .isTrue();
        mvc.perform(get("/api/public/match-requests/" + token))
                .andExpect(jsonPath("$.matches[*].stylistName").value(Matchers.contains("Bo", "Cy")));
        assertThat(matchesReadyEmail(request).get("status")).isEqualTo("PENDING");
        // Nothing found and nothing added: published empty, as the ranker does now.
        assertThat(statusOf(empty)).isEqualTo("PUBLISHED");
        assertThat((String) matchesReadyEmail(empty).get("text_body")).contains("didn't find a close match");
        // Everything hidden by the admin: it stays for them.
        assertThat(statusOf(allHidden)).isEqualTo("IN_REVIEW");
    }

    private void match(UUID request, UUID org, int systemRank, Integer finalRank, boolean hidden, String source) {
        jdbc.sql("""
                        insert into match (id, match_request_id, org_id, score, reason, system_rank, final_rank, hidden, admin_override,
                          source, created_at, updated_at)
                        values (?, ?, ?, 0.9, 'similar cuts', ?, ?, ?, true, ?, now(), now())
                        """)
                .params(UUID.randomUUID(), request, org, systemRank, finalRank, hidden, source).update();
    }

    private Map<String, Object> matchesReadyEmail(UUID request) {
        return jdbc.sql("""
                        select status, status_reason, next_attempt_at, text_body from email_message
                        where template = 'MATCHES_READY' and subject_id = ?
                        """)
                .params(request).query().singleRow();
    }

    private UUID stylist(String name, String status) {
        UUID org = UUID.randomUUID();
        jdbc.sql("insert into org (id, name, type, created_at) values (?, ?, 'STYLIST', now())").params(org, name).update();
        jdbc.sql("""
                        insert into stylist_profile (org_id, display_name, location_id, market, instagram, booking_url, status, created_at,
                          updated_at)
                        values (?, ?, ?, ?, 'handle', 'https://booksy.com/x', ?, now(), now())
                        """)
                .params(org, name, location, market, status).update();
        return org;
    }

    private void photo(UUID org, String service, String hairType, byte[] image) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/" + org + "/" + id + ".jpg";
        BLOBS.objects.put(key, image);
        jdbc.sql("""
                        insert into portfolio_photo (id, org_id, object_key, content_type, size_bytes, status, service, hair_type, position,
                          uploaded_by, created_at, updated_at)
                        values (?, ?, ?, 'image/jpeg', ?, 'READY', ?, ?, ?, ?, ?, now())
                        """)
                .params(id, org, key, image.length, service, hairType, ++photos, uploader, Timestamp.from(Instant.now().plusMillis(photos)))
                .update();
    }

    /** A request as intake leaves it once the inspiration photo is confirmed: NEW, with a link for the token. */
    private UUID request(String hairType, String service, String token, String email) {
        UUID client = UUID.randomUUID();
        jdbc.sql("insert into client (id, email, first_name, hair_type, market, created_at) values (?, ?, 'Sam', ?, ?, now())")
                .params(client, email, hairType, market).update();
        UUID id = UUID.randomUUID();
        String key = "inspiration/" + id + ".jpg";
        BLOBS.objects.put(key, LOOK);
        jdbc.sql("""
                        insert into match_request (id, client_id, market, service, inspiration_object_key, inspiration_content_type,
                          inspiration_sha256, access_token_hash, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'image/jpeg', 'confirmed', ?, 'NEW', now(), now())
                        """)
                .params(id, client, market, service, key, sha256(token)).update();
        jdbc.sql("""
                        insert into match_request_link (token_hash, match_request_id, purpose, created_at, expires_at)
                        values (?, ?, 'CREATED', now(), now() + interval '1 day')
                        """)
                .params(sha256(token), id).update();
        return id;
    }

    private static String token() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(UUID request) {
        return jdbc.sql("select status from match_request where id = ?").params(request).query(String.class).single();
    }
}
