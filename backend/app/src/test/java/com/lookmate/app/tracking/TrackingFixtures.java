package com.lookmate.app.tracking;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds the V2 tables other modules own (stylists, intake, matching) with plain SQL, so tracking can be tested
 * before those modules land. Test-only: production code never writes these tables from tracking.
 */
class TrackingFixtures {

    private final JdbcClient jdbc;
    private final UUID uploader;

    TrackingFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.uploader = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, subject, email, created_at) values (:id, :sub, null, now())")
                .param("id", uploader).param("sub", "fixture|" + uploader).update();
    }

    UUID org(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into org (id, name, type, created_at) values (:id, :name, 'STYLIST', now())")
                .param("id", id).param("name", name).update();
        return id;
    }

    void profile(UUID orgId, String name, String market, String status, String bookingUrl) {
        jdbc.sql("""
                        insert into stylist_profile (org_id, display_name, market, booking_url, status, created_at, updated_at)
                        values (:org, :name, :market, :url, :status, now(), now())
                        """)
                .param("org", orgId).param("name", name).param("market", market).param("url", bookingUrl)
                .param("status", status).update();
    }

    void photo(UUID orgId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into portfolio_photo (id, org_id, object_key, content_type, size_bytes, status, uploaded_by,
                          created_at, updated_at)
                        values (:id, :org, :key, 'image/jpeg', 1000, :status, :by, now(), now())
                        """)
                .param("id", id).param("org", orgId).param("key", "portfolio/" + id).param("status", status)
                .param("by", uploader).update();
    }

    UUID client(String market, String hairType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into client (id, email, first_name, hair_type, market, created_at)
                        values (:id, :email, 'Sam', :hair, :market, now())
                        """)
                .param("id", id).param("email", "client-" + id + "@example.com").param("hair", hairType)
                .param("market", market).update();
        return id;
    }

    /** A request with the given link token; {@code publishedAt} null leaves it IN_REVIEW. */
    UUID request(UUID clientId, String market, String token, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        Instant created = (publishedAt == null ? Instant.now() : publishedAt).minusSeconds(3600);
        jdbc.sql("""
                        insert into match_request (id, client_id, market, service, access_token_hash, status, published_at,
                          created_at, updated_at)
                        values (:id, :client, :market, 'CUT', :hash, :status, :published, :created, :created)
                        """)
                .param("id", id).param("client", clientId).param("market", market)
                .param("hash", TrackingService.hash(token))
                .param("status", publishedAt == null ? "IN_REVIEW" : "PUBLISHED")
                .param("published", publishedAt == null ? null : Timestamp.from(publishedAt))
                .param("created", Timestamp.from(created)).update();
        return id;
    }

    /** A ranked, visible match unless {@code hidden}; a hidden one also has no final rank. */
    UUID match(UUID requestId, UUID orgId, int rank, boolean hidden) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into match (id, match_request_id, org_id, score, reason, system_rank, final_rank, hidden,
                          created_at, updated_at)
                        values (:id, :request, :org, 0.9, 'similar cuts', :rank, :finalRank, :hidden, now(), now())
                        """)
                .param("id", id).param("request", requestId).param("org", orgId).param("rank", rank)
                .param("finalRank", hidden ? null : rank).param("hidden", hidden).update();
        return id;
    }

    UUID event(UUID matchId, UUID requestId, UUID orgId, String type, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into match_event (id, match_id, match_request_id, org_id, type, source, occurred_at, created_at)
                        values (:id, :match, :request, :org, :type, :source, :at, now())
                        """)
                .param("id", id).param("match", matchId).param("request", requestId).param("org", orgId)
                .param("type", type).param("source", type.equals("BOOKED") || type.equals("REBOOKED") ? "ADMIN" : "CLIENT")
                .param("at", Timestamp.from(at)).update();
        return id;
    }

    /** A VOIDED row for {@code eventId}, as the admin would write it. */
    void voidEvent(UUID eventId, UUID matchId, UUID requestId, UUID orgId) {
        jdbc.sql("""
                        insert into match_event (id, match_id, match_request_id, org_id, type, source, note, occurred_at,
                          created_at, voids_event_id)
                        values (:id, :match, :request, :org, 'VOIDED', 'ADMIN', 'entered by mistake', now(), now(), :voids)
                        """)
                .param("id", UUID.randomUUID()).param("match", matchId).param("request", requestId).param("org", orgId)
                .param("voids", eventId).update();
    }

    long count(UUID requestId, String type) {
        return jdbc.sql("select count(*) from match_event_live where match_request_id = :r and type = :t")
                .param("r", requestId).param("t", type).query(Long.class).single();
    }
}
