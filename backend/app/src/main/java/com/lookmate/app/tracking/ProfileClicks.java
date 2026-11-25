package com.lookmate.app.tracking;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Book clicks from a stylist's own page ({@code /s/<slug>}): tracking source PROFILE, kept apart from recommended and
 * directory clicks (table {@code profile_click}, V15). These clients came through the stylist's own link, so they are
 * never charged for (docs/tracking.md). No client, no request, no token: just which stylist, when, and how the page
 * was reached.
 */
@Component
class ProfileClicks {

    /** How the visitor reached the page: the link itself, the printed QR card, or a link in the stylist's bio. */
    enum Via {
        LINK, QR, BIO;

        /** {@code qr} or {@code bio} from the page's {@code ?via=}; anything else is LINK. */
        static Via of(String raw) {
            if ("qr".equalsIgnoreCase(raw)) {
                return QR;
            }
            if ("bio".equalsIgnoreCase(raw)) {
                return BIO;
            }
            return LINK;
        }
    }

    private final JdbcClient jdbc;

    ProfileClicks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(UUID orgId, String market, Via via) {
        jdbc.sql("insert into profile_click (id, org_id, market, via, occurred_at) values (:id, :org, :market, :via, :at)")
                .param("id", UUID.randomUUID()).param("org", orgId).param("market", market).param("via", via.name())
                .param("at", java.sql.Timestamp.from(Instant.now()))
                .update();
    }

    /** Clicks in {@code range}, in {@code market} (null: every market). */
    long count(String market, TrackingQueries.Range range) {
        return jdbc.sql("""
                        select count(*) from profile_click
                        where (cast(:market as text) is null or market = :market)
                          and occurred_at >= :start and occurred_at < :end
                        """)
                .param("market", market)
                .param("start", java.sql.Timestamp.from(range.start())).param("end", java.sql.Timestamp.from(range.end()))
                .query(Long.class).single();
    }
}
