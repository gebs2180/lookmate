package com.lookmate.app.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The weekly stylist digest: planning, the email, its one-tap links and the off switches. */
@SpringBootTest(properties = {"lookmate.tracking.digest.enabled=false", "lookmate.retention.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WeeklyDigestTest {

    private static final String MARKET = "columbus-oh";
    /** Monday 2026-11-23, 09:00 in Columbus: the digest for the week of Monday 2026-11-16 goes out. */
    private static final Instant MONDAY = Instant.parse("2026-11-23T14:00:00Z");
    private static final Instant TUESDAY_IN_WEEK = Instant.parse("2026-11-17T15:00:00Z");
    private static final Pattern RECORD_LINK = Pattern.compile("/digest/([A-Za-z0-9_-]{43})\\?type=REBOOKED");
    private static final Pattern UNSUBSCRIBE_LINK = Pattern.compile("/digest/unsubscribe/([A-Za-z0-9_-]{43})");
    private static final Pattern NEW_CLIENTS_LINK = Pattern.compile("/digest/new-clients/([A-Za-z0-9_-]{43})");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WeeklyDigests digests;

    private TestApi api;
    private TrackingFixtures fx;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
        fx = new TrackingFixtures(jdbc);
    }

    /** A LIVE stylist org whose member has an email, with last week's activity. */
    private record Week(UUID orgId, String member, UUID matchedRequest, UUID matchId, UUID directoryRequest, UUID shownOnly) {
    }

    private Week seedWeek(String name) throws Exception {
        String member = "auth0|" + name;
        UUID org = UUID.fromString(api.stylistOrg(name, member));
        jdbc.sql("update app_user set email = :email where subject = :sub").param("email", name + "@example.com")
                .param("sub", member).update();
        fx.profile(org, "Rae", MARKET, "LIVE", "https://booksy.com/" + name);

        // Sam: on their list, tapped Book from it.
        UUID sam = fx.client(MARKET, "CURLY");
        UUID samRequest = fx.request(sam, MARKET, TrackingFixtures.token(), TUESDAY_IN_WEEK);
        UUID samMatch = fx.match(samRequest, org, 1, false);
        fx.event(samMatch, samRequest, org, "CLICKED_BOOK", TUESDAY_IN_WEEK.plusSeconds(3600));
        // Dana: picked them from the directory.
        UUID dana = fx.client(MARKET, "COILY");
        jdbc.sql("update client set first_name = 'Dana' where id = :id").param("id", dana).update();
        UUID danaRequest = fx.request(dana, MARKET, TrackingFixtures.token(), null);
        fx.event(null, danaRequest, org, "CLICKED_BOOK", TUESDAY_IN_WEEK.plusSeconds(86_400));
        // Someone who saw them and didn't tap.
        UUID quiet = fx.client(MARKET, "WAVY");
        UUID quietRequest = fx.request(quiet, MARKET, TrackingFixtures.token(), TUESDAY_IN_WEEK.plusSeconds(7200));
        fx.match(quietRequest, org, 2, false);
        // A tap the week before doesn't count.
        fx.event(samMatch, samRequest, org, "CLICKED_BOOK", Instant.parse("2026-11-15T15:00:00Z"));
        return new Week(org, member, samRequest, samMatch, danaRequest, quietRequest);
    }

    private UUID digestOf(UUID org, LocalDate week) {
        return jdbc.sql("select id from stylist_digest where org_id = :org and week_start = :week")
                .param("org", org).param("week", week).query(UUID.class).single();
    }

    private List<String[]> emails(UUID digestId) {
        return jdbc.sql("select subject, text_body, html_body from email_message where subject_id = :id and template = 'STYLIST_DIGEST'")
                .param("id", digestId)
                .query((rs, n) -> new String[] {rs.getString("subject"), rs.getString("text_body"), rs.getString("html_body")})
                .list();
    }

    private static List<String> all(Pattern pattern, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void oneDigestPerStylistPerWeekWithFirstNamesOnly() throws Exception {
        Week w = seedWeek("digest-once");

        assertThat(digests.plan(MONDAY.minusSeconds(3 * 3600))).isZero(); // before 08:00 on Monday
        assertThat(digests.plan(MONDAY.plusSeconds(86_400))).isZero(); // Tuesday
        digests.plan(MONDAY);
        digests.plan(MONDAY.plusSeconds(600));
        UUID digest = digestOf(w.orgId(), LocalDate.parse("2026-11-16"));
        digests.sendPending(MONDAY, 1000);
        digests.sendPending(MONDAY.plusSeconds(600), 1000);

        List<String[]> sent = emails(digest);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()[0]).isEqualTo("Your week on Combpatible: 3 clients, 2 tapped Book");
        String text = sent.getFirst()[1];
        assertThat(text).contains("Hi Rae,")
                .contains("Last week Combpatible showed you to 3 clients; 2 tapped Book: Sam and Dana.")
                .contains("Last 30 days:")
                .doesNotContain("@example.com");
        assertThat(all(RECORD_LINK, text)).hasSize(2);
        assertThat(all(UNSUBSCRIBE_LINK, text)).hasSize(1);
        assertThat(text).doesNotContain("Still taking new clients?"); // the status was confirmed just now
        assertThat(sent.getFirst()[2]).contains(">Booked</a>").contains(">Rebooked</a>");
        assertThat(jdbc.sql("select status from stylist_digest where id = :id").param("id", digest).query(String.class).single())
                .isEqualTo("DONE");
    }

    @Test
    void aLinkRecordsOnceForAMemberOfTheOrgOnly() throws Exception {
        Week w = seedWeek("digest-links");
        String other = api.stylistOrg("digest-other", "auth0|digest-other");
        digests.plan(MONDAY);
        digests.sendPending(MONDAY, 1000);
        List<String> tokens = all(RECORD_LINK, emails(digestOf(w.orgId(), LocalDate.parse("2026-11-16"))).getFirst()[1]);
        String sam = tokens.get(0);
        String dana = tokens.get(1);
        var stylist = api.user(w.member(), List.of());
        var stranger = api.user("auth0|digest-other", List.of());

        // Not signed in: 401. Signed in to another org (a forwarded email): 404, nothing recorded.
        mvc.perform(get("/api/digest-links/" + sam)).andExpect(status().isUnauthorized());
        api.get("/api/digest-links/" + sam, stranger).andExpect(status().isNotFound());
        api.postJson("/api/digest-links/" + sam, "{\"type\":\"BOOKED\"}", stranger).andExpect(status().isNotFound());
        api.get("/api/digest-links/not-a-token", stylist).andExpect(status().isNotFound());
        assertThat(fx.count(w.matchedRequest(), "BOOKED")).isZero();
        assertThat(other).isNotBlank();

        api.get("/api/digest-links/" + sam, stylist)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientFirstName").value("Sam"))
                .andExpect(jsonPath("$.orgId").value(w.orgId().toString()))
                .andExpect(jsonPath("$.directoryPick").value(false))
                .andExpect(jsonPath("$.used").value(false))
                .andExpect(jsonPath("$.clientEmail").doesNotExist());

        // The match: once. The second tap says the link was used.
        api.postJson("/api/digest-links/" + sam, "{\"type\":\"BOOKED\"}", stylist)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchId").value(w.matchId().toString()))
                .andExpect(jsonPath("$.source").value("STYLIST"));
        api.postJson("/api/digest-links/" + sam, "{\"type\":\"REBOOKED\"}", stylist)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINK_USED"));
        assertThat(fx.count(w.matchedRequest(), "BOOKED")).isEqualTo(1);

        // A directory pick: a REBOOKED with no booking is refused and leaves the link usable.
        api.postJson("/api/digest-links/" + dana, "{\"type\":\"REBOOKED\"}", stylist)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REBOOK_WITHOUT_BOOKING"));
        String eventId = TestApi.read(api.postJson("/api/digest-links/" + dana, "{\"type\":\"BOOKED\"}", stylist)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchId").doesNotExist()), "$.id");
        assertThat(fx.count(w.directoryRequest(), "BOOKED")).isEqualTo(1);
        // And the stylist can undo it.
        api.postJson("/api/orgs/" + w.orgId() + "/events/" + eventId + "/void", "{\"reason\":\"Tapped by mistake\"}", stylist)
                .andExpect(status().isOk());
        api.get("/api/digest-links/" + dana, stylist)
                .andExpect(jsonPath("$.used").value(true))
                .andExpect(jsonPath("$.eventId").value(eventId));
    }

    @Test
    void anExpiredLinkRecordsNothing() throws Exception {
        Week w = seedWeek("digest-expired");
        digests.plan(MONDAY);
        digests.sendPending(MONDAY, 1000);
        String sam = all(RECORD_LINK, emails(digestOf(w.orgId(), LocalDate.parse("2026-11-16"))).getFirst()[1]).getFirst();
        jdbc.sql("update stylist_digest_link set expires_at = :past where org_id = :org")
                .param("past", Timestamp.from(Instant.now().minusSeconds(60))).param("org", w.orgId()).update();

        api.postJson("/api/digest-links/" + sam, "{\"type\":\"BOOKED\"}", api.user(w.member(), List.of()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        assertThat(fx.count(w.matchedRequest(), "BOOKED")).isZero();
        // Expired more than 30 days ago: deleted.
        assertThat(digests.cleanUp(Instant.now().plusSeconds(31L * 86_400))).isGreaterThanOrEqualTo(3);
    }

    @Test
    void aStatusOlderThan30DaysGetsAConfirmLinkThatWorksWithoutSignIn() throws Exception {
        Week w = seedWeek("digest-new-clients");
        Instant old = Instant.parse("2026-10-01T12:00:00Z");
        jdbc.sql("update stylist_profile set new_clients_confirmed_at = :at where org_id = :org")
                .param("at", Timestamp.from(old)).param("org", w.orgId()).update();
        digests.plan(MONDAY);
        digests.sendPending(MONDAY, 1000);
        String[] email = emails(digestOf(w.orgId(), LocalDate.parse("2026-11-16"))).getFirst();
        assertThat(email[1]).contains("Still taking new clients?");
        List<String> links = all(NEW_CLIENTS_LINK, email[1]);
        assertThat(links).hasSize(1);
        String unsubscribe = all(UNSUBSCRIBE_LINK, email[1]).getFirst();

        // No sign-in, POST only; it confirms the status as it stands. Another kind of link, or none, is a 404.
        mvc.perform(post("/api/public/digest/new-clients/" + unsubscribe)).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/digest/new-clients/" + TrackingFixtures.token())).andExpect(status().isNotFound());
        mvc.perform(post("/api/public/digest/new-clients/" + links.getFirst()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newClients.status").value("TAKING"));
        Instant confirmed = jdbc.sql("select new_clients_confirmed_at from stylist_profile where org_id = :org")
                .param("org", w.orgId()).query(Timestamp.class).single().toInstant();
        assertThat(confirmed).isAfter(old.plusSeconds(86_400));
        // Repeats are fine.
        mvc.perform(post("/api/public/digest/new-clients/" + links.getFirst())).andExpect(status().isOk());
    }

    @Test
    void theSwitchAndTheUnsubscribeLinkTurnItOff() throws Exception {
        Week w = seedWeek("digest-off");
        var stylist = api.user(w.member(), List.of());
        digests.plan(MONDAY);
        digests.sendPending(MONDAY, 1000);
        String unsubscribe = all(UNSUBSCRIBE_LINK, emails(digestOf(w.orgId(), LocalDate.parse("2026-11-16"))).getFirst()[1]).getFirst();

        api.get("/api/orgs/" + w.orgId() + "/digest", stylist).andExpect(jsonPath("$.weeklyDigest").value(true));
        // The unsubscribe link needs no sign-in, and only works by POST.
        mvc.perform(post("/api/public/digest/unsubscribe/" + unsubscribe)).andExpect(status().isNoContent());
        mvc.perform(post("/api/public/digest/unsubscribe/" + TrackingFixtures.token())).andExpect(status().isNotFound());
        api.get("/api/orgs/" + w.orgId() + "/digest", stylist).andExpect(jsonPath("$.weeklyDigest").value(false));

        // Next week: activity, but no email for them.
        Instant nextMonday = MONDAY.plusSeconds(7L * 86_400);
        fx.event(w.matchId(), w.matchedRequest(), w.orgId(), "CLICKED_BOOK", nextMonday.minusSeconds(86_400));
        digests.plan(nextMonday);
        digests.sendPending(nextMonday, 1000);
        UUID next = digestOf(w.orgId(), LocalDate.parse("2026-11-23"));
        assertThat(emails(next)).isEmpty();

        // On again from the settings switch; another org's switch is a 404.
        mvc.perform(put("/api/orgs/" + w.orgId() + "/digest").with(stylist).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weeklyDigest\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weeklyDigest").value(true));
        mvc.perform(put("/api/orgs/" + w.orgId() + "/digest").with(api.user("auth0|digest-nobody", List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"weeklyDigest\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStylistRecordsADirectoryPickOnlyForAClientWhoPickedThem() throws Exception {
        Week w = seedWeek("digest-directory");
        var stylist = api.user(w.member(), List.of());

        api.get("/api/orgs/" + w.orgId() + "/tracking/directory-picks", stylist)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(w.directoryRequest().toString()))
                .andExpect(jsonPath("$[0].clientFirstName").value("Dana"))
                .andExpect(jsonPath("$[0].clientEmail").doesNotExist());
        // A request where the client didn't pick them from the directory: 404.
        api.postJson("/api/orgs/" + w.orgId() + "/match-requests/" + w.shownOnly() + "/events", "{\"type\":\"BOOKED\"}", stylist)
                .andExpect(status().isNotFound());
        api.postJson("/api/orgs/" + w.orgId() + "/match-requests/" + w.directoryRequest() + "/events", "{\"type\":\"BOOKED\"}", stylist)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("STYLIST"));
        api.postJson("/api/orgs/" + w.orgId() + "/match-requests/" + w.directoryRequest() + "/events", "{\"type\":\"BOOKED\"}", stylist)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));
    }
}
