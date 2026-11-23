package com.lookmate.app.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import com.lookmate.app.storage.BlobStore;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
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
 * Matching end to end with the fake provider and the review switch on ({@code review-before-publish}): the worker
 * embeds, the ranker filters and ranks, the admin reviews and publishes, and the client sees only what was published.
 * {@link AutoPublishFlowTest} covers the default, with the switch off. The stylists' and intake's tables are seeded with SQL
 * (stylists is built in parallel); requests go through intake's real {@code MatchRequests}. Photos are in memory.
 */
@SpringBootTest(properties = {
        "lookmate.matching.review-before-publish=true",
        "lookmate.matching.worker.enabled=false",
        "lookmate.matching.worker.max-attempts=2",
        "lookmate.matching.worker.retry-backoff=1h",
        "lookmate.intake.rate-limit.reads-per-minute=1000"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MatchingFlowTest.Images.class})
@Testcontainers(disabledWithoutDocker = true)
class MatchingFlowTest {

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
    @Autowired
    private CandidateQueries queries;
    @Autowired
    private EmbeddingProvider provider;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private EmbeddingWorker worker;
    @Autowired
    private EmbeddingStore store;

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

        String intakeToken = token();
        UUID request = request("WAVY", "CUT", LOOK, intakeToken, "client@example.com");

        job.runOnce();

        // The switch is on: the list waits for the admin, and the monitor says so (the web app shows the queue).
        assertThat(statusOf(request)).isEqualTo("IN_REVIEW");
        api.get("/api/admin/match-monitor?market=" + market, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewBeforePublish").value(true))
                .andExpect(jsonPath("$.requests[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.requests[0].matches[0].finalRank").value(org.hamcrest.Matchers.nullValue()));
        var review = api.get("/api/admin/match-requests/" + request, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.hairType").value("WAVY"))
                .andExpect(jsonPath("$.inspirationUrl").value("https://storage.test/photos/inspiration/" + request + ".jpg?signed"))
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

        // Not yet published: the client's page (intake's) shows no matches.
        mvc.perform(get("/api/public/match-requests/" + intakeToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("MATCHING")).andExpect(jsonPath("$.matches.length()").value(0));

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

        // Publish: ranks 1..n over the visible ones; intake emails the client (stored as skipped: sending is off in tests).
        api.postJson("/api/admin/match-requests/" + request + "/publish", "", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.matches[?(@.hidden==false)].finalRank").value(org.hamcrest.Matchers.contains(1, 2, 3)));
        assertThat(jdbc.sql("select count(*) from email_message where template = 'MATCHES_READY' and subject_id = ?").params(request)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select reviewed_by is not null and published_at is not null from match_request where id = ?").params(request)
                .query(Boolean.class).single()).isTrue();

        mvc.perform(get("/api/public/match-requests/" + intakeToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY"))
                .andExpect(jsonPath("$.matches.length()").value(3))
                .andExpect(jsonPath("$.matches[0].stylistName").value("Curl Neighbour"))
                .andExpect(jsonPath("$.matches[0].rank").value(1))
                .andExpect(jsonPath("$.matches[0].reason").value("3 similar cuts on curly hair"))
                .andExpect(jsonPath("$.matches[0].photoUrls[0]").isNotEmpty())
                .andExpect(jsonPath("$.matches[1].stylistName").value("Wavy Expert"))
                .andExpect(jsonPath("$.matches[2].stylistName").value("Coily Only"))
                .andExpect(jsonPath("$..adminNote").isEmpty())
                .andExpect(jsonPath("$..score").isEmpty());

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
        UUID request = request("CURLY", "CUT", LOOK, token(), null);
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
        UUID request = request("COILY", "CUT", LOOK, token(), null);
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
    void anInspirationPhotoThatCantBeReadIsRetriedWhenIntakeHandsItBackThenFails() {
        UUID request = request("WAVY", "CUT", null, token(), null);
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("MATCHING");
        job.runOnce(); // still MATCHING and recently touched: intake doesn't hand it back yet
        assertThat(attempts(request)).isEqualTo(1);
        jdbc.sql("update match_request set updated_at = now() - interval '1 hour' where id = ?").params(request).update();
        job.runOnce();
        assertThat(attempts(request)).isEqualTo(2);
        assertThat(statusOf(request)).isEqualTo("FAILED");
    }

    private int attempts(UUID request) {
        return jdbc.sql("select embed_attempts from match_request_attempt where match_request_id = ?").params(request)
                .query(Integer.class).single();
    }

    /**
     * Review #23 and group A's retakes: a ranking that throws is counted and retried when intake hands the request back,
     * then FAILED; a retake (new photo, new hash) is embedded again and ranked, and its tries start over.
     */
    @Test
    void aRankingThatFailsIsRetriedThenFailsAndARetakeIsEmbeddedAgain() {
        UUID org = stylist("Wavy Hands", market, "LIVE");
        photo(org, "CUT", "WAVY", LOOK);
        UUID request = request("WAVY", "CUT", bytes("first photo"), token(), null);
        String trigger = "fail_rank_" + request.toString().replace("-", "");
        jdbc.sql(("create function %s() returns trigger language plpgsql as $$ begin "
                + "if new.match_request_id = '%s' then raise exception 'ranking broke'; end if; return new; end $$").formatted(trigger, request))
                .update();
        jdbc.sql("create trigger %s before insert on match for each row execute function %s()".formatted(trigger, trigger)).update();
        try {
            job.runOnce();
            assertThat(statusOf(request)).isEqualTo("MATCHING");
            assertThat(reasonOf(request)).isEqualTo(Ranker.NOT_RANKED + " (attempt 1); trying again later");
            assertThat(rankAttempts(request)).isEqualTo(1);
            jdbc.sql("update match_request set updated_at = now() - interval '1 hour' where id = ?").params(request).update();
            job.runOnce();
            assertThat(statusOf(request)).isEqualTo("FAILED");
            assertThat(reasonOf(request)).isEqualTo(Ranker.NOT_RANKED);
        } finally {
            jdbc.sql("drop trigger %s on match".formatted(trigger)).update();
            jdbc.sql("drop function %s()".formatted(trigger)).update();
        }
        String before = inspirationVector(request);

        // A retake as intake leaves it: NEW, a new photo with a new hash.
        BLOBS.objects.put("inspiration/" + request + ".jpg", bytes("second photo"));
        jdbc.sql("update match_request set status = 'NEW', status_reason = null, inspiration_sha256 = 'retake' where id = ?")
                .params(request).update();
        job.runOnce();
        assertThat(statusOf(request)).isEqualTo("IN_REVIEW");
        assertThat(inspirationVector(request)).isNotEqualTo(before);
        assertThat(jdbc.sql("select inspiration_sha256 from match_request_embedding where match_request_id = ?").params(request)
                .query(String.class).single()).isEqualTo("retake");
        assertThat(jdbc.sql("select count(*) from match_request_attempt where match_request_id = ?").params(request)
                .query(Integer.class).single()).isZero();
    }

    /**
     * Review #8: the provider call runs outside the claiming transaction, each photo is saved on its own, and a photo
     * that can't be saved backs off without undoing the others or being claimed again at once. A claimed photo's lease
     * keeps other passes off it.
     */
    @Test
    void aPortfolioPhotoThatCantBeSavedBacksOffWithoutHoldingUpTheRest() {
        UUID org = stylist("Batch Stylist", market, "LIVE");
        for (int i = 0; i < 3; i++) {
            photo(org, "CUT", "WAVY", bytes("good " + i + UUID.randomUUID()));
        }
        UUID bad = photo(org, "CUT", "WAVY", bytes("bad " + UUID.randomUUID()));
        String trigger = "fail_save_" + bad.toString().replace("-", "");
        jdbc.sql(("create function %s() returns trigger language plpgsql as $$ begin "
                + "if new.photo_id = '%s' then raise exception 'disk full'; end if; return new; end $$").formatted(trigger, bad)).update();
        jdbc.sql("create trigger %s before insert on portfolio_photo_embedding for each row execute function %s()".formatted(trigger, trigger))
                .update();
        try {
            while (worker.portfolioBatch() > 0) {
                // until nothing is left to embed
            }
        } finally {
            jdbc.sql("drop trigger %s on portfolio_photo_embedding".formatted(trigger)).update();
            jdbc.sql("drop function %s()".formatted(trigger)).update();
        }
        assertThat(jdbc.sql("""
                        select count(*) from portfolio_photo p join portfolio_photo_embedding e on e.photo_id = p.id
                        where p.org_id = ?
                        """).params(org).query(Integer.class).single()).isEqualTo(3);
        Map<String, Object> attempt = jdbc.sql("select attempts, last_error from embedding_attempt where subject_id = ?").params(bad)
                .query().singleRow();
        assertThat(attempt.get("attempts")).isEqualTo(1);
        assertThat((String) attempt.get("last_error")).startsWith("save:").contains("disk full");
        assertThat(ids(tx.execute(s -> queries.claimPortfolioPhotosToEmbed(provider.model(), Instant.now(), 5, 1000)))).doesNotContain(bad);

        UUID leased = photo(org, "CUT", "WAVY", bytes("leased " + UUID.randomUUID()));
        store.lease(leased, provider.model(), Instant.now().plusSeconds(600), Instant.now());
        assertThat(ids(tx.execute(s -> queries.claimPortfolioPhotosToEmbed(provider.model(), Instant.now(), 5, 1000)))).doesNotContain(leased);
    }

    private int rankAttempts(UUID request) {
        return jdbc.sql("select rank_attempts from match_request_attempt where match_request_id = ?").params(request)
                .query(Integer.class).single();
    }

    private String reasonOf(UUID request) {
        return jdbc.sql("select status_reason from match_request where id = ?").params(request).query(String.class).single();
    }

    private String inspirationVector(UUID request) {
        return jdbc.sql("select embedding::text from match_request_embedding where match_request_id = ?").params(request)
                .query(String.class).single();
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

    private UUID photo(UUID org, String service, String hairType, byte[] image) {
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
        return id;
    }

    /** A request as intake leaves it once the inspiration photo is confirmed: NEW. Null image: unreadable. */
    private UUID request(String hairType, String service, byte[] image, String token, String email) {
        UUID client = UUID.randomUUID();
        jdbc.sql("insert into client (id, email, first_name, hair_type, market, created_at) values (?, ?, 'Sam', ?, ?, now())")
                .params(client, email, hairType, market).update();
        UUID id = UUID.randomUUID();
        String key = "inspiration/" + id + ".jpg";
        if (image != null) {
            BLOBS.objects.put(key, image);
        }
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

    /** A client link token in intake's shape: 32 random bytes, base64url. */
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

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
