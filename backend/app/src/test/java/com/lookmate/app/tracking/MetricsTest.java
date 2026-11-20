package com.lookmate.app.tracking;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The pilot table on a small, hand-counted fixture in a market of its own (so other tests' rows don't count). The
 * range is October 2026 in America/New_York; every time is noon UTC (8am in Columbus), away from day edges.
 *
 * <pre>
 * Stylists  A LIVE, 2 READY photos   B LIVE, only a PENDING photo   C DRAFT, 1 READY   D LIVE, 1 READY
 * Requests  r1 c1 CURLY    published 10-05  matches A(1) D(2)
 *           r2 c2 COILY    published 10-12  match   A(1)
 *           r3 c3 STRAIGHT published 10-20  matches D(1), B hidden
 *           r4 c4 WAVY     published 09-20 (before the range)  match A(1)
 *           r5 c1          in review, never published          match A(1)
 * Events    CLICKED_BOOK  r1/A 10-05, r1/D 10-05, r2/A 10-13, r3/D 10-21, r4/A 09-21 (before)
 *           BOOKED        r1/A 10-06, r2/A 10-14, r4/A 10-02
 *           REBOOKED      r1/A 10-27, r4/A 11-10 (after the range)
 *           VIEWED        r1 10-05 (not in the table)
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MetricsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcClient jdbc;

    private static Instant at(String date) {
        return Instant.parse(date + "T12:00:00Z");
    }

    @Test
    void thePilotTableOnASeededFixture() throws Exception {
        TrackingFixtures fx = new TrackingFixtures(jdbc);
        String market = "test-" + UUID.randomUUID().toString().substring(0, 8);

        UUID a = fx.org("A");
        UUID b = fx.org("B");
        UUID c = fx.org("C");
        UUID d = fx.org("D");
        fx.profile(a, "Avery", market, "LIVE", "https://booksy.com/a");
        fx.profile(b, "Blake", market, "LIVE", "https://booksy.com/b");
        fx.profile(c, "Casey", market, "DRAFT", "https://booksy.com/c");
        fx.profile(d, "Devon", market, "LIVE", "https://booksy.com/d");
        fx.photo(a, "READY");
        fx.photo(a, "READY");
        fx.photo(b, "PENDING_UPLOAD");
        fx.photo(c, "READY");
        fx.photo(d, "READY");

        UUID c1 = fx.client(market, "CURLY");
        UUID c2 = fx.client(market, "COILY");
        UUID c3 = fx.client(market, "STRAIGHT");
        UUID c4 = fx.client(market, "WAVY");
        UUID r1 = fx.request(c1, market, "t1-" + market, at("2026-10-05"));
        UUID r2 = fx.request(c2, market, "t2-" + market, at("2026-10-12"));
        UUID r3 = fx.request(c3, market, "t3-" + market, at("2026-10-20"));
        UUID r4 = fx.request(c4, market, "t4-" + market, at("2026-09-20"));
        UUID r5 = fx.request(c1, market, "t5-" + market, null);
        UUID m1a = fx.match(r1, a, 1, false);
        UUID m1d = fx.match(r1, d, 2, false);
        UUID m2a = fx.match(r2, a, 1, false);
        UUID m3d = fx.match(r3, d, 1, false);
        fx.match(r3, b, 2, true);
        UUID m4a = fx.match(r4, a, 1, false);
        fx.match(r5, a, 1, false);

        fx.event(m1a, r1, a, "CLICKED_BOOK", at("2026-10-05"));
        fx.event(m1d, r1, d, "CLICKED_BOOK", at("2026-10-05"));
        fx.event(m2a, r2, a, "CLICKED_BOOK", at("2026-10-13"));
        fx.event(m3d, r3, d, "CLICKED_BOOK", at("2026-10-21"));
        fx.event(m4a, r4, a, "CLICKED_BOOK", at("2026-09-21"));
        fx.event(m1a, r1, a, "BOOKED", at("2026-10-06"));
        fx.event(m2a, r2, a, "BOOKED", at("2026-10-14"));
        fx.event(m4a, r4, a, "BOOKED", at("2026-10-02"));
        fx.event(m1a, r1, a, "REBOOKED", at("2026-10-27"));
        fx.event(m4a, r4, a, "REBOOKED", at("2026-11-10"));
        fx.event(null, r1, null, "VIEWED", at("2026-10-05"));

        TestApi api = new TestApi(mvc, security);
        api.get("/api/admin/metrics?market=" + market + "&from=2026-10-01&to=2026-10-31", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value(market))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.markets", Matchers.hasItem(market)))
                .andExpect(jsonPath("$.totals.stylistsLive").value(3))
                .andExpect(jsonPath("$.totals.stylistsActive").value(2))
                .andExpect(jsonPath("$.totals.requestsPublished").value(3))
                .andExpect(jsonPath("$.totals.clientsMatched").value(3))
                .andExpect(jsonPath("$.totals.bookingClicks").value(4))
                .andExpect(jsonPath("$.totals.clientsClicked").value(3))
                .andExpect(jsonPath("$.totals.bookings").value(3))
                .andExpect(jsonPath("$.totals.clientsBooked").value(3))
                .andExpect(jsonPath("$.totals.rebooks").value(1))
                .andExpect(jsonPath("$.totals.clientsRebooked").value(1))
                // c1, c2, c4 booked in October; c1 (10-27) and c4 (11-10) rebooked with the same stylist afterwards.
                .andExpect(jsonPath("$.totals.rebookCohort").value(3))
                .andExpect(jsonPath("$.totals.rebookCohortRebooked").value(2))
                .andExpect(jsonPath("$.totals.rebookRate").value(Matchers.closeTo(2.0 / 3.0, 1e-9)))

                .andExpect(jsonPath("$.weeks.length()").value(5))
                .andExpect(jsonPath("$.weeks[*].weekStart").value(Matchers.contains(
                        "2026-09-28", "2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26")))
                .andExpect(jsonPath("$.weeks[*].requestsPublished").value(Matchers.contains(0, 1, 1, 1, 0)))
                .andExpect(jsonPath("$.weeks[*].bookingClicks").value(Matchers.contains(0, 2, 1, 1, 0)))
                .andExpect(jsonPath("$.weeks[*].bookings").value(Matchers.contains(1, 1, 1, 0, 0)))
                .andExpect(jsonPath("$.weeks[*].rebooks").value(Matchers.contains(0, 0, 0, 0, 1)))

                .andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Avery", "Devon", "Blake")))
                .andExpect(jsonPath("$.stylists[0].readyPhotos").value(2))
                .andExpect(jsonPath("$.stylists[0].matchesShown").value(2))
                .andExpect(jsonPath("$.stylists[0].bookingClicks").value(2))
                .andExpect(jsonPath("$.stylists[0].bookings").value(3))
                .andExpect(jsonPath("$.stylists[0].rebooks").value(1))
                .andExpect(jsonPath("$.stylists[0].clientsBooked").value(3))
                .andExpect(jsonPath("$.stylists[1].matchesShown").value(2))
                .andExpect(jsonPath("$.stylists[1].bookingClicks").value(2))
                .andExpect(jsonPath("$.stylists[1].bookings").value(0))
                .andExpect(jsonPath("$.stylists[2].matchesShown").value(0))
                .andExpect(jsonPath("$.stylists[2].readyPhotos").value(0))

                .andExpect(jsonPath("$.hairTypes[*].hairType").value(Matchers.contains("STRAIGHT", "CURLY", "COILY")))
                .andExpect(jsonPath("$.hairTypes[*].clientsMatched").value(Matchers.contains(1, 1, 1)))
                .andExpect(jsonPath("$.hairTypes[*].clientsClicked").value(Matchers.contains(1, 1, 1)))
                .andExpect(jsonPath("$.hairTypes[*].clientsBooked").value(Matchers.contains(0, 1, 1)));

        // An empty range has no rebook rate rather than 0%.
        api.get("/api/admin/metrics?market=" + market + "&from=2025-01-01&to=2025-01-31", api.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.bookings").value(0))
                .andExpect(jsonPath("$.totals.rebookRate").value(Matchers.nullValue()));
        api.get("/api/admin/metrics?from=2026-10-31&to=2026-10-01", api.admin()).andExpect(status().isBadRequest());
    }
}
