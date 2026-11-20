package com.lookmate.app.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The booking handoff and manual entry, end to end over HTTP. The stylist, request and match rows are seeded with SQL
 * (TrackingFixtures) until the stylists, intake and matching modules land.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TrackingFlowTest {

    private static final String MARKET = "columbus-oh";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;
    private TrackingFixtures fx;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        fx = new TrackingFixtures(jdbc);
    }

    /** A published request with one visible match to a LIVE stylist with the given booking link. */
    private record Seeded(String token, UUID orgId, UUID requestId, UUID matchId, UUID clientId) {
    }

    private Seeded seed(UUID orgId, String bookingUrl) {
        if (orgId == null) {
            orgId = fx.org("Stylist " + UUID.randomUUID());
        }
        fx.profile(orgId, "Curl Studio", MARKET, "LIVE", bookingUrl);
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        UUID client = fx.client(MARKET, "CURLY");
        UUID request = fx.request(client, MARKET, token, Instant.now().minusSeconds(600));
        UUID match = fx.match(request, orgId, 1, false);
        return new Seeded(token, orgId, request, match, client);
    }

    // --- Redirect -----------------------------------------------------------------------------------------------

    @Test
    void bookRedirectsToTheTaggedLinkAndRecordsOneClick() throws Exception {
        Seeded s = seed(null, "https://booksy.com/en-us/123?staff=7#top");

        String expected = "https://booksy.com/en-us/123?staff=7&utm_source=lookmate&utm_medium=referral"
                + "&utm_campaign=columbus-oh&utm_content=" + s.matchId() + "#top";
        mvc.perform(get("/api/public/book/" + s.token() + "/" + s.matchId()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", expected))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
        // A double click within the window counts once, and still redirects.
        mvc.perform(get("/api/public/book/" + s.token() + "/" + s.matchId()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", expected));

        assertThat(fx.count(s.requestId(), "CLICKED_BOOK")).isEqualTo(1);
        var row = jdbc.sql("select match_id, org_id, source, recorded_by from match_event where match_request_id = :r")
                .param("r", s.requestId()).query().singleRow();
        assertThat(row.get("match_id")).isEqualTo(s.matchId());
        assertThat(row.get("org_id")).isEqualTo(s.orgId());
        assertThat(row.get("source")).isEqualTo("CLIENT");
        assertThat(row.get("recorded_by")).isNull();
    }

    @Test
    void aNonHttpsBookingLinkIsNotFollowed() throws Exception {
        for (String url : List.of("http://booksy.com/a", "javascript:alert(1)", "//evil.example", "https://u:p@evil.example/")) {
            Seeded s = seed(null, url);
            mvc.perform(get("/api/public/book/" + s.token() + "/" + s.matchId()))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("Location"));
            assertThat(fx.count(s.requestId(), "CLICKED_BOOK")).isZero();
        }
    }

    @Test
    void aWrongTokenOrSomeoneElsesMatchIsNotFound() throws Exception {
        Seeded mine = seed(null, "https://booksy.com/a");
        Seeded theirs = seed(null, "https://booksy.com/b");

        mvc.perform(get("/api/public/book/tok_wrong0000000000000000/" + mine.matchId())).andExpect(status().isNotFound());
        // My token, another request's match.
        mvc.perform(get("/api/public/book/" + mine.token() + "/" + theirs.matchId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/book/" + mine.token() + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(fx.count(mine.requestId(), "CLICKED_BOOK") + fx.count(theirs.requestId(), "CLICKED_BOOK")).isZero();
    }

    @Test
    void aHiddenMatchOrUnpublishedRequestIsNotFound() throws Exception {
        UUID org = fx.org("Hidden Suite");
        fx.profile(org, "Hidden Suite", MARKET, "LIVE", "https://booksy.com/h");
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        UUID request = fx.request(fx.client(MARKET, "COILY"), MARKET, token, Instant.now().minusSeconds(60));
        UUID hidden = fx.match(request, org, 1, true);
        mvc.perform(get("/api/public/book/" + token + "/" + hidden)).andExpect(status().isNotFound());

        UUID org2 = fx.org("Review Suite");
        fx.profile(org2, "Review Suite", MARKET, "LIVE", "https://booksy.com/r");
        String token2 = "tok_" + UUID.randomUUID().toString().replace("-", "");
        UUID inReview = fx.request(fx.client(MARKET, "WAVY"), MARKET, token2, null);
        UUID unpublished = fx.match(inReview, org2, 1, false);
        mvc.perform(get("/api/public/book/" + token2 + "/" + unpublished)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/match-requests/" + token2 + "/viewed")).andExpect(status().isNotFound());

        // A stylist who is no longer LIVE isn't linked to either.
        Seeded s = seed(null, "https://booksy.com/gone");
        jdbc.sql("update stylist_profile set status = 'HIDDEN' where org_id = :o").param("o", s.orgId()).update();
        mvc.perform(get("/api/public/book/" + s.token() + "/" + s.matchId())).andExpect(status().isNotFound());

        assertThat(fx.count(request, "CLICKED_BOOK") + fx.count(inReview, "CLICKED_BOOK")).isZero();
    }

    // --- Viewed -------------------------------------------------------------------------------------------------

    @Test
    void viewedIsRecordedOncePerDay() throws Exception {
        Seeded s = seed(null, "https://booksy.com/v");
        mvc.perform(post("/api/public/match-requests/" + s.token() + "/viewed")).andExpect(status().isNoContent());
        mvc.perform(post("/api/public/match-requests/" + s.token() + "/viewed")).andExpect(status().isNoContent());
        assertThat(fx.count(s.requestId(), "VIEWED")).isEqualTo(1);
        mvc.perform(post("/api/public/match-requests/tok_nope00000000000000000/viewed")).andExpect(status().isNotFound());
    }

    // --- Manual entry -------------------------------------------------------------------------------------------

    @Test
    void aStylistRecordsBookingsForTheirOwnMatchesOnly() throws Exception {
        String orgA = api.stylistOrg("Entry A", "auth0|entry-a");
        String orgB = api.stylistOrg("Entry B", "auth0|entry-b");
        Seeded a = seed(UUID.fromString(orgA), "https://booksy.com/a");
        Seeded b = seed(UUID.fromString(orgB), "https://booksy.com/b");
        var stylistA = api.user("auth0|entry-a", List.of());

        api.postJson("/api/orgs/" + orgA + "/matches/" + a.matchId() + "/events", "{\"type\":\"BOOKED\",\"note\":\" Tues 3pm \"}", stylistA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BOOKED"))
                .andExpect(jsonPath("$.source").value("STYLIST"))
                .andExpect(jsonPath("$.note").value("Tues 3pm"));

        // B's match through A's org: 404. B's org path as A: 404 (OrgAccess).
        api.postJson("/api/orgs/" + orgA + "/matches/" + b.matchId() + "/events", "{\"type\":\"BOOKED\"}", stylistA)
                .andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + orgB + "/matches/" + b.matchId() + "/events", "{\"type\":\"BOOKED\"}", stylistA)
                .andExpect(status().isNotFound());
        api.get("/api/orgs/" + orgB + "/tracking/stats", stylistA).andExpect(status().isNotFound());
        assertThat(fx.count(b.requestId(), "BOOKED")).isZero();

        // A second BOOKED on the same match is refused; a REBOOKED after it is fine.
        api.postJson("/api/orgs/" + orgA + "/matches/" + a.matchId() + "/events", "{\"type\":\"BOOKED\"}", stylistA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));
        api.postJson("/api/orgs/" + orgA + "/matches/" + a.matchId() + "/events", "{\"type\":\"REBOOKED\"}", stylistA)
                .andExpect(status().isCreated());
        // Clients can't be entered as VIEWED or CLICKED_BOOK by hand.
        api.postJson("/api/orgs/" + orgA + "/matches/" + a.matchId() + "/events", "{\"type\":\"CLICKED_BOOK\"}", stylistA)
                .andExpect(status().isBadRequest());

        api.get("/api/orgs/" + orgA + "/tracking/stats", stylistA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last30Days.matchesShown").value(1))
                .andExpect(jsonPath("$.last30Days.bookings").value(1))
                .andExpect(jsonPath("$.last30Days.rebooks").value(1))
                .andExpect(jsonPath("$.allTime.bookingClicks").value(0));

        // Admin-only routes stay closed to stylists.
        api.postJson("/api/admin/matches/" + b.matchId() + "/events", "{\"type\":\"BOOKED\"}", stylistA)
                .andExpect(status().isForbidden());
        api.get("/api/admin/metrics", stylistA).andExpect(status().isForbidden());
    }

    @Test
    void theAdminRecordsBookingsAndRebooks() throws Exception {
        Seeded s = seed(null, "https://booksy.com/admin");
        String url = "/api/admin/matches/" + s.matchId() + "/events";

        api.postJson(url, "{\"type\":\"REBOOKED\"}", api.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REBOOK_WITHOUT_BOOKING"));
        api.postJson(url, "{\"type\":\"BOOKED\",\"occurredAt\":\"2999-01-01T00:00:00Z\"}", api.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OCCURRED_IN_FUTURE"));
        api.postJson(url, "{\"type\":\"BOOKED\",\"occurredAt\":\"2020-01-01T00:00:00Z\"}", api.admin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OCCURRED_BEFORE_REQUEST"));
        api.postJson(url, "{\"type\":\"BOOKED\"}", api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("ADMIN"));
        api.postJson(url, "{\"type\":\"REBOOKED\"}", api.admin()).andExpect(status().isCreated());
        api.postJson("/api/admin/matches/" + UUID.randomUUID() + "/events", "{\"type\":\"BOOKED\"}", api.admin())
                .andExpect(status().isNotFound());

        api.get("/api/admin/tracking/matches?q=curl", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.matchId == '" + s.matchId() + "')].bookings").value(1));
    }

    @Test
    void matchEventsAreAppendOnly() {
        Seeded s = seed(null, "https://booksy.com/x");
        fx.event(s.matchId(), s.requestId(), s.orgId(), "CLICKED_BOOK", Instant.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update match_event set note = 'x' where match_request_id = :r")
                        .param("r", s.requestId()).update())
                .hasMessageContaining("append-only");
    }

    // --- Voiding ------------------------------------------------------------------------------------------------

    @Test
    void aStylistVoidsAMistakenBookingOnTheirOwnMatch() throws Exception {
        String orgA = api.stylistOrg("Void A", "auth0|void-a");
        String orgB = api.stylistOrg("Void B", "auth0|void-b");
        Seeded a = seed(UUID.fromString(orgA), "https://booksy.com/va");
        Seeded b = seed(UUID.fromString(orgB), "https://booksy.com/vb");
        var stylistA = api.user("auth0|void-a", List.of());
        String events = "/api/orgs/" + orgA + "/matches/" + a.matchId() + "/events";

        String booked = TestApi.read(api.postJson(events, "{\"type\":\"BOOKED\"}", stylistA).andExpect(status().isCreated()), "$.id");
        String rebooked = TestApi.read(api.postJson(events, "{\"type\":\"REBOOKED\"}", stylistA).andExpect(status().isCreated()), "$.id");

        // The rebook counts on the booking: void the rebook first.
        api.postJson("/api/orgs/" + orgA + "/events/" + booked + "/void", "{\"reason\":\"wrong client\"}", stylistA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_HAS_REBOOKS"));
        api.postJson("/api/orgs/" + orgA + "/events/" + rebooked + "/void", "{\"reason\":\"wrong client\"}", stylistA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided").value(true))
                .andExpect(jsonPath("$.voidReason").value("wrong client"));
        api.postJson("/api/orgs/" + orgA + "/events/" + booked + "/void", "{\"reason\":\"wrong client\"}", stylistA)
                .andExpect(status().isOk());
        api.postJson("/api/orgs/" + orgA + "/events/" + booked + "/void", "{\"reason\":\"again\"}", stylistA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VOIDED"));
        api.postJson("/api/orgs/" + orgA + "/events/" + booked + "/void", "{\"reason\":\" \"}", stylistA)
                .andExpect(status().isBadRequest());

        // Voided events still show, marked; they count nowhere; the booking can be recorded again.
        api.get(events, stylistA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].voided").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
        api.get("/api/orgs/" + orgA + "/tracking/stats", stylistA)
                .andExpect(jsonPath("$.allTime.bookings").value(0))
                .andExpect(jsonPath("$.allTime.rebooks").value(0));
        api.postJson(events, "{\"type\":\"BOOKED\"}", stylistA).andExpect(status().isCreated());
        assertThat(fx.count(a.requestId(), "BOOKED")).isEqualTo(1);

        // Not another org's events, and not the client's own clicks.
        String bBooked = TestApi.read(api.postJson("/api/admin/matches/" + b.matchId() + "/events", "{\"type\":\"BOOKED\"}", api.admin()), "$.id");
        api.postJson("/api/orgs/" + orgA + "/events/" + bBooked + "/void", "{\"reason\":\"x\"}", stylistA)
                .andExpect(status().isNotFound());
        api.get("/api/orgs/" + orgA + "/matches/" + b.matchId() + "/events", stylistA).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/book/" + a.token() + "/" + a.matchId())).andExpect(status().isFound());
        String click = jdbc.sql("select id::text from match_event where match_id = :m and type = 'CLICKED_BOOK'")
                .param("m", a.matchId()).query(String.class).single();
        api.postJson("/api/orgs/" + orgA + "/events/" + click + "/void", "{\"reason\":\"x\"}", stylistA)
                .andExpect(status().isNotFound());
        // The admin can void a click (a test click, say).
        api.postJson("/api/admin/events/" + click + "/void", "{\"reason\":\"my own test click\"}", api.admin())
                .andExpect(status().isOk());
        assertThat(fx.count(a.requestId(), "CLICKED_BOOK")).isZero();
    }

    @Test
    void aStylistSeesTheirMatchesWithTheClientsFirstNameOnly() throws Exception {
        String org = api.stylistOrg("List Suite", "auth0|list-a");
        Seeded s = seed(UUID.fromString(org), "https://booksy.com/list");
        api.stylistOrg("Other List", "auth0|list-b");

        api.get("/api/orgs/" + org + "/tracking/matches", api.user("auth0|list-a", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].matchId").value(s.matchId().toString()))
                .andExpect(jsonPath("$[0].clientFirstName").value("Sam"))
                .andExpect(jsonPath("$[0].clientEmail").doesNotExist());
        api.get("/api/orgs/" + org + "/tracking/matches", api.user("auth0|list-b", List.of()))
                .andExpect(status().isNotFound());
    }
}
