package com.lookmate.app.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Matching end to end with the fake provider: the worker embeds, the ranker filters and ranks, the admin reviews and
 * publishes, and the client sees only what was published. The stylists' and intake's tables are seeded with SQL
 * (those modules are built in parallel); images come from an in-memory {@link ImageSource}.
 */
@SpringBootTest(properties = {
        "lookmate.matching.worker.enabled=false",
        "lookmate.matching.worker.max-attempts=2",
        "lookmate.matching.worker.retry-backoff=1h"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MatchingFlowTest.Images.class})
@Testcontainers(disabledWithoutDocker = true)
class MatchingFlowTest {

    /** Object key to bytes; a key that isn't here can't be read. */
    static final Map<String, byte[]> IMAGES = new ConcurrentHashMap<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Images {
        @Bean
        @Primary
        ImageSource testImages() {
            return key -> {
                byte[] b = IMAGES.get(key);
                if (b == null) {
                    throw new IllegalStateException("no such object");
                }
                return b;
            };
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
    @Autowired
    private CandidateQueries queries;
    @Autowired
    private EmbeddingProvider provider;
    @Autowired
    private TransactionTemplate tx;

    /** Each test has its own market: the tests share a database, and matching never crosses markets. */
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
    void workerRanksReviewerReordersAndTheClientSeesOnlyWhatWasPublished() throws Exception {
        UUID wavyExpert = stylist("Wavy Expert", market, "LIVE");
        photo(wavyExpert, "CUT", "WAVY", LOOK);
        photo(wavyExpert, "CUT", "WAVY", LOOK);
        photo(wavyExpert, "CUT", "WAVY", LOOK);
        photo(wavyExpert, "CUT", "WAVY", bytes("something else"));
        photo(wavyExpert, "COLOR", "WAVY", LOOK); // another service: not counted
        UUID oneLucky = stylist("One Lucky Photo", market, "LIVE");
        photo(oneLucky, "CUT", "WAVY", LOOK);
        photo(oneLucky, "CUT", "WAVY", bytes("b1"));
        photo(oneLucky, "CUT", "WAVY", bytes("b2"));
        UUID curly = stylist("Curl Neighbour", market, "LIVE");
        photo(curly, "CUT", "CURLY", LOOK);
        photo(curly, "CUT", "CURLY", LOOK);
        photo(curly, "CUT", "CURLY", LOOK);
        UUID coily = stylist("Coily Only", market, "LIVE");
        photo(coily, "CUT", "COILY", LOOK); // two types away from wavy: not a candidate
        UUID elsewhere = stylist("Other City", "cleveland-oh", "LIVE");
        photo(elsewhere, "CUT", "WAVY", LOOK);
        UUID draft = stylist("Not Live Yet", market, "DRAFT");
        photo(draft, "CUT", "WAVY", LOOK);

        String intakeToken = "intake-token-" + UUID.randomUUID();
        UUID request = request("WAVY", "CUT", LOOK, intakeToken, "client@example.com");

        job.runOnce();

        assertThat(statusOf(request)).isEqualTo("IN_REVIEW");
        var review = api.get("/api/admin/match-requests/" + request, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.hairType").value("WAVY"))
                .andExpect(jsonPath("$.inspirationUrl").isNotEmpty())
                .andExpect(jsonPath("$.matches.length()").value(3))
                .andExpect(jsonPath("$.matches[0].orgId").value(wavyExpert.toString()))
                .andExpect(jsonPath("$.matches[0].reason").value("3 similar cuts on wavy hair"))
                .andExpect(jsonPath("$.matches[0].systemRank").value(1))
                .andExpect(jsonPath("$.matches[0].evidence.length()").value(4))
                .andExpect(jsonPath("$.matches[0].evidence[0].url").isNotEmpty())
                .andExpect(jsonPath("$.matches[1].orgId").value(curly.toString()))
                .andExpect(jsonPath("$.matches[1].reason").value("3 similar cuts on curly hair"))
                .andExpect(jsonPath("$.matches[2].orgId").value(oneLucky.toString()));
        String expertMatch = TestApi.read(review, "$.matches[0].id");
        String curlyMatch = TestApi.read(review, "$.matches[1].id");
        String luckyMatch = TestApi.read(review, "$.matches[2].id");
        api.get("/api/admin/match-requests", api.admin())
                .andExpect(jsonPath("$[?(@.id=='%s')].candidates".formatted(request)).value(3));

        // Not yet published: the client's link works but shows nothing.
        mvc.perform(get("/api/public/match-requests/" + intakeToken + "/matches")).andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false)).andExpect(jsonPath("$.matches.length()").value(0));

        // Review: hide one, reorder, add one by hand; refuse a stylist from another market and a duplicate.
        mvc.perform(put("/api/admin/match-requests/" + request + "/matches/" + luckyMatch).with(api.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hidden\":true,\"note\":\"Portfolio is mostly color\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/match-requests/" + request + "/order").with(api.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchIds\":[\"%s\",\"%s\",\"%s\"]}".formatted(curlyMatch, expertMatch, luckyMatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].id").value(curlyMatch))
                .andExpect(jsonPath("$.matches[0].adminOverride").value(true));
        mvc.perform(put("/api/admin/match-requests/" + request + "/order").with(api.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"matchIds\":[\"%s\"]}".formatted(curlyMatch)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_ORDER"));
        api.get("/api/admin/match-requests/" + request + "/stylists", api.admin())
                .andExpect(jsonPath("$[?(@.orgId=='%s')].alreadyMatched".formatted(wavyExpert)).value(true))
                .andExpect(jsonPath("$[?(@.orgId=='%s')]".formatted(elsewhere)).isEmpty());
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\",\"note\":\"Great with texture\"}".formatted(coily),
                        api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matches[3].orgId").value(coily.toString()))
                .andExpect(jsonPath("$.matches[3].source").value("ADMIN"))
                .andExpect(jsonPath("$.matches[3].evidence.length()").value(1));
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(elsewhere), api.admin())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STYLIST_NOT_AVAILABLE"));
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(wavyExpert), api.admin())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MATCHED"));

        // Publish: ranks 1..n over the visible ones; the email is queued (stored as skipped: sending is off in tests).
        var published = api.postJson("/api/admin/match-requests/" + request + "/publish", "", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.review.request.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.emailQueued").value(true));
        String clientPath = TestApi.read(published, "$.clientPath");
        assertThat(jdbc.sql("select status from email_message where template = 'MATCHES_READY' and subject_id = ?").params(request)
                .query(String.class).single()).isEqualTo("SKIPPED");
        assertThat(jdbc.sql("select reviewed_by is not null and published_at is not null from match_request where id = ?").params(request)
                .query(Boolean.class).single()).isTrue();

        for (String token : List.of(intakeToken, clientPath.substring("/m/".length()))) {
            mvc.perform(get("/api/public/match-requests/" + token + "/matches")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.published").value(true))
                    .andExpect(jsonPath("$.matches.length()").value(3))
                    .andExpect(jsonPath("$.matches[0].orgId").value(curly.toString()))
                    .andExpect(jsonPath("$.matches[0].rank").value(1))
                    .andExpect(jsonPath("$.matches[1].orgId").value(wavyExpert.toString()))
                    .andExpect(jsonPath("$.matches[2].orgId").value(coily.toString()))
                    .andExpect(jsonPath("$.matches[0].photos[0].url").isNotEmpty())
                    .andExpect(jsonPath("$..adminNote").isEmpty())
                    .andExpect(jsonPath("$..score").isEmpty());
        }
        mvc.perform(get("/api/public/match-requests/not-a-real-token-at-all-0000/matches")).andExpect(status().isNotFound());

        // Published means done: further changes are refused.
        api.postJson("/api/admin/match-requests/" + request + "/publish", "", api.admin())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_IN_REVIEW"));

        api.get("/api/admin/match-reports/fairness?market=" + market, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value(market))
                .andExpect(jsonPath("$.rows[?(@.hairType=='WAVY')].requests").value(1))
                .andExpect(jsonPath("$.rows[?(@.hairType=='WAVY')].published").value(1))
                .andExpect(jsonPath("$.rows[?(@.hairType=='WAVY')].withAdminChanges").value(1))
                .andExpect(jsonPath("$.rows[?(@.hairType=='WAVY')].shownMatches").value(3));
    }

    @Test
    void onlyTheAdminReachesTheReviewEndpoints() throws Exception {
        UUID request = request("CURLY", "CUT", LOOK, "token-" + UUID.randomUUID(), null);
        String stylistOrg = api.stylistOrg("A Stylist", "auth0|matching-stylist");
        var stylist = api.user("auth0|matching-stylist", List.of());
        api.get("/api/admin/match-requests", stylist).andExpect(status().isForbidden());
        api.get("/api/admin/match-requests/" + request, stylist).andExpect(status().isForbidden());
        api.get("/api/admin/match-reports/fairness", stylist).andExpect(status().isForbidden());
        api.postJson("/api/admin/match-requests/" + request + "/publish", "", stylist).andExpect(status().isForbidden());
        api.postJson("/api/admin/match-requests/" + request + "/matches", "{\"orgId\":\"%s\"}".formatted(stylistOrg), stylist)
                .andExpect(status().isForbidden());
        // Signed in without MFA, an ADMIN role claim doesn't count.
        var noMfa = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(b -> b.subject("auth0|no-mfa").claim(security.rolesClaim(), List.of("ADMIN")))
                .authorities(new com.lookmate.app.identity.RoleClaimConverter(security)::convert);
        api.get("/api/admin/match-requests", noMfa).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/match-requests")).andExpect(status().isUnauthorized());
        api.get("/api/admin/match-requests/" + UUID.randomUUID(), api.admin()).andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithNoCandidatesGoesToReviewWithAReasonAndCantBePublishedEmpty() throws Exception {
        UUID straight = stylist("Straight Only", market, "LIVE");
        photo(straight, "CUT", "STRAIGHT", LOOK);
        UUID request = request("COILY", "CUT", LOOK, "token-" + UUID.randomUUID(), null);
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("IN_REVIEW");
        api.get("/api/admin/match-requests/" + request, api.admin())
                .andExpect(jsonPath("$.request.statusReason").value(Ranker.NO_CANDIDATES))
                .andExpect(jsonPath("$.matches.length()").value(0));
        api.postJson("/api/admin/match-requests/" + request + "/publish", "", api.admin())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_VISIBLE_MATCHES"));
        api.get("/api/admin/match-reports/fairness?market=" + market, api.admin())
                .andExpect(jsonPath("$.rows[?(@.hairType=='COILY')].noSystemMatches").value(1));
    }

    @Test
    void anInspirationPhotoThatCantBeReadFailsTheRequestAfterTheLastAttempt() {
        UUID request = request("WAVY", "CUT", null, "token-" + UUID.randomUUID(), null);
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("MATCHING");
        job.runOnce(); // backing off: not tried again yet
        assertThat(jdbc.sql("select attempts from embedding_attempt where subject_id = ?").params(request).query(Integer.class).single())
                .isEqualTo(1);
        jdbc.sql("update embedding_attempt set next_attempt_at = now() - interval '1 second' where subject_id = ?").params(request).update();
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("FAILED");
        assertThat(jdbc.sql("select attempts from embedding_attempt where subject_id = ?").params(request).query(Integer.class).single())
                .isEqualTo(2);
    }

    @Test
    void twoWorkersNeverClaimTheSamePhoto() throws Exception {
        UUID org = stylist("Busy Stylist", market, "LIVE");
        for (int i = 0; i < 4; i++) {
            photo(org, "CUT", "WAVY", bytes("p" + i + UUID.randomUUID()));
        }
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        CompletableFuture<List<UUID>> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            List<UUID> ids = ids(queries.claimPortfolioPhotosToEmbed(provider.model(), Instant.now(), 5, 2));
            firstHolds.countDown();
            await(secondDone);
            return ids;
        }));
        await(firstHolds);
        List<UUID> second = tx.execute(s -> ids(queries.claimPortfolioPhotosToEmbed(provider.model(), Instant.now(), 5, 10)));
        secondDone.countDown();
        List<UUID> firstIds = first.get(30, TimeUnit.SECONDS);
        assertThat(firstIds).hasSize(2);
        assertThat(second).doesNotContainAnyElementsOf(firstIds);
        assertThat(second).hasSizeGreaterThanOrEqualTo(2);
    }

    private static List<UUID> ids(List<CandidateQueries.PendingImage> images) {
        return images.stream().map(CandidateQueries.PendingImage::id).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID stylist(String name, String market, String status) {
        UUID org = UUID.randomUUID();
        jdbc.sql("insert into org (id, name, type, created_at) values (?, ?, 'STYLIST', now())").params(org, name).update();
        jdbc.sql("""
                        insert into stylist_profile (org_id, display_name, location_id, market, instagram, status, created_at, updated_at)
                        values (?, ?, ?, ?, 'handle', ?, now(), now())
                        """)
                .params(org, name, location, market, status).update();
        return org;
    }

    private void photo(UUID org, String service, String hairType, byte[] image) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/" + org + "/" + id + ".jpg";
        IMAGES.put(key, image);
        jdbc.sql("""
                        insert into portfolio_photo (id, org_id, object_key, content_type, size_bytes, status, service, hair_type, position,
                          uploaded_by, created_at, updated_at)
                        values (?, ?, ?, 'image/jpeg', ?, 'READY', ?, ?, ?, ?, ?, now())
                        """)
                .params(id, org, key, image.length, service, hairType, ++photos, uploader, Timestamp.from(Instant.now().plusMillis(photos)))
                .update();
    }

    /** A request in MATCHING, as intake leaves it once the inspiration photo is uploaded. Null image: unreadable. */
    private UUID request(String hairType, String service, byte[] image, String token, String email) {
        UUID client = UUID.randomUUID();
        jdbc.sql("insert into client (id, email, first_name, hair_type, market, created_at) values (?, ?, 'Sam', ?, ?, now())")
                .params(client, email, hairType, market).update();
        UUID id = UUID.randomUUID();
        String key = "inspiration/" + id + ".jpg";
        if (image != null) {
            IMAGES.put(key, image);
        }
        jdbc.sql("""
                        insert into match_request (id, client_id, market, service, inspiration_object_key, inspiration_content_type,
                          access_token_hash, status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, 'image/jpeg', ?, 'MATCHING', now(), now())
                        """)
                .params(id, client, market, service, key, MatchStore.hash(token)).update();
        return id;
    }

    private String statusOf(UUID request) {
        return jdbc.sql("select status from match_request where id = ?").params(request).query(String.class).single();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
