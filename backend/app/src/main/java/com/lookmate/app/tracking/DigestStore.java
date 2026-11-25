package com.lookmate.app.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The weekly digest's tables (V14): the per-org-per-week queue, opt-outs and the email's links, plus the read-only
 * queries for what goes in a digest. Joins the caller's transaction.
 */
@Repository
class DigestStore {

    enum LinkKind { RECORD, UNSUBSCRIBE }

    enum OptOutSource { SETTINGS, UNSUBSCRIBE_LINK }

    record Pending(UUID id, UUID orgId, LocalDate weekStart) {
    }

    /**
     * A client who tapped Book on the org in the week. {@code matchId} is their match with the org when they tapped
     * from their list, null for a directory pick. {@code booked}: a booking with the org is recorded for the request.
     */
    record Tapped(UUID requestId, UUID matchId, String firstName, String service, boolean booked, Instant firstTap) {
    }

    record Link(UUID id, LinkKind kind, UUID digestId, UUID orgId, UUID userId, UUID requestId, UUID matchId,
                Instant expiresAt, Instant usedAt, UUID eventId, String clientFirstName, String service, LocalDate weekStart) {
    }

    private final JdbcClient jdbc;

    DigestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- The queue -------------------------------------------------------------------------------------------------

    /** Returns whether a digest was added; false if the org already has one for that week. */
    boolean plan(UUID orgId, LocalDate weekStart, Instant now) {
        return jdbc.sql("""
                        insert into stylist_digest (id, org_id, week_start, status, created_at, updated_at)
                        values (:id, :org, :week, 'PENDING', :now, :now)
                        on conflict (org_id, week_start) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("org", orgId)
                .param("week", weekStart)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /** The oldest PENDING digest, locked until the transaction ends; another instance skips it. */
    Optional<Pending> claimNext() {
        return jdbc.sql("""
                        select id, org_id, week_start from stylist_digest where status = 'PENDING'
                        order by created_at limit 1 for update skip locked
                        """)
                .query((rs, n) -> new Pending(uuid(rs, "id"), uuid(rs, "org_id"), rs.getObject("week_start", LocalDate.class)))
                .optional();
    }

    void done(UUID id, int recipients, Instant now) {
        jdbc.sql("update stylist_digest set status = 'DONE', recipients = :n, updated_at = :now where id = :id")
                .param("n", recipients).param("now", Timestamp.from(now)).param("id", id).update();
    }

    /** Counts a failed attempt; the digest is FAILED from {@code maxAttempts} on. */
    void failed(UUID id, String error, int maxAttempts, Instant now) {
        jdbc.sql("""
                        update stylist_digest
                        set attempts = attempts + 1, last_error = left(:error, 2000), updated_at = :now,
                            status = case when attempts + 1 >= :max then 'FAILED' else status end
                        where id = :id and status = 'PENDING'
                        """)
                .param("error", error).param("max", maxAttempts).param("now", Timestamp.from(now)).param("id", id).update();
    }

    // --- What goes in it -----------------------------------------------------------------------------------------

    /**
     * Distinct clients (requests) the org was shown to in the range: on a published list (by publish time), or picked
     * from the directory (by the tap).
     */
    long clientsShown(UUID orgId, TrackingQueries.Range range) {
        return jdbc.sql("""
                        select count(*) from (
                          select r.id from match m join match_request r on r.id = m.match_request_id
                          where m.org_id = :org and m.final_rank is not null and not m.hidden
                            and r.published_at >= :start and r.published_at < :end
                          union
                          select e.match_request_id from match_event_live e
                          where e.org_id = :org and e.match_id is null and e.type = 'CLICKED_BOOK'
                            and e.occurred_at >= :start and e.occurred_at < :end
                        ) shown
                        """)
                .param("org", orgId)
                .param("start", Timestamp.from(range.start()))
                .param("end", Timestamp.from(range.end()))
                .query(Long.class).single();
    }

    /** The clients who tapped Book on the org in the range, first tap first. */
    List<Tapped> tapped(UUID orgId, TrackingQueries.Range range) {
        return jdbc.sql("""
                        select e.match_request_id, c.first_name, r.service, min(e.occurred_at) as first_tap,
                               (array_agg(e.match_id order by e.occurred_at) filter (where e.match_id is not null))[1] as match_id,
                               exists (select 1 from match_event_live b where b.match_request_id = e.match_request_id
                                         and b.org_id = :org and b.type = 'BOOKED') as booked
                        from match_event_live e
                        join match_request r on r.id = e.match_request_id
                        join client c on c.id = r.client_id
                        where e.org_id = :org and e.type = 'CLICKED_BOOK'
                          and e.occurred_at >= :start and e.occurred_at < :end
                        group by e.match_request_id, c.first_name, r.service
                        order by first_tap
                        """)
                .param("org", orgId)
                .param("start", Timestamp.from(range.start()))
                .param("end", Timestamp.from(range.end()))
                .query((rs, n) -> new Tapped(uuid(rs, "match_request_id"), uuid(rs, "match_id"), rs.getString("first_name"),
                        rs.getString("service"), rs.getBoolean("booked"), rs.getTimestamp("first_tap").toInstant()))
                .list();
    }

    // --- Opt-outs ----------------------------------------------------------------------------------------------

    boolean optedOut(UUID orgId, UUID userId) {
        return jdbc.sql("select exists (select 1 from stylist_digest_opt_out where org_id = :org and user_id = :user)")
                .param("org", orgId).param("user", userId).query(Boolean.class).single();
    }

    void optOut(UUID orgId, UUID userId, OptOutSource source, Instant now) {
        jdbc.sql("""
                        insert into stylist_digest_opt_out (org_id, user_id, source, created_at)
                        values (:org, :user, :source, :now) on conflict do nothing
                        """)
                .param("org", orgId).param("user", userId).param("source", source.name()).param("now", Timestamp.from(now))
                .update();
    }

    void optIn(UUID orgId, UUID userId) {
        jdbc.sql("delete from stylist_digest_opt_out where org_id = :org and user_id = :user")
                .param("org", orgId).param("user", userId).update();
    }

    // --- Links -------------------------------------------------------------------------------------------------

    /** Stores a new link and returns its token (only the hash is kept). */
    String newLink(LinkKind kind, UUID digestId, UUID orgId, UUID userId, UUID requestId, UUID matchId, Instant now,
                   Instant expiresAt) {
        String token = DigestTokens.newToken();
        jdbc.sql("""
                        insert into stylist_digest_link (id, token_hash, kind, digest_id, org_id, user_id, match_request_id,
                          match_id, created_at, expires_at)
                        values (:id, :hash, :kind, :digest, :org, :user, :request, :match, :now, :expires)
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", DigestTokens.hash(token))
                .param("kind", kind.name())
                .param("digest", digestId)
                .param("org", orgId)
                .param("user", userId)
                .param("request", requestId)
                .param("match", matchId)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expiresAt))
                .update();
        return token;
    }

    /** The link for a token, with the client's first name and service; {@code forUpdate} locks it. */
    Optional<Link> link(String token, boolean forUpdate) {
        if (!DigestTokens.wellFormed(token)) {
            return Optional.empty();
        }
        String sql = """
                select l.id, l.kind, l.digest_id, l.org_id, l.user_id, l.match_request_id, l.match_id, l.expires_at,
                       l.used_at, l.event_id, c.first_name, r.service, d.week_start
                from stylist_digest_link l
                join stylist_digest d on d.id = l.digest_id
                left join match_request r on r.id = l.match_request_id
                left join client c on c.id = r.client_id
                where l.token_hash = :hash
                """;
        return jdbc.sql(forUpdate ? sql + " for update of l" : sql)
                .param("hash", DigestTokens.hash(token))
                .query((rs, n) -> new Link(uuid(rs, "id"), LinkKind.valueOf(rs.getString("kind")), uuid(rs, "digest_id"),
                        uuid(rs, "org_id"), uuid(rs, "user_id"), uuid(rs, "match_request_id"), uuid(rs, "match_id"),
                        rs.getTimestamp("expires_at").toInstant(), instant(rs, "used_at"), uuid(rs, "event_id"),
                        rs.getString("first_name"), rs.getString("service"), rs.getObject("week_start", LocalDate.class)))
                .optional();
    }

    void used(UUID linkId, UUID usedBy, UUID eventId, Instant now) {
        jdbc.sql("update stylist_digest_link set used_at = :now, used_by = :by, event_id = :event where id = :id")
                .param("now", Timestamp.from(now)).param("by", usedBy).param("event", eventId).param("id", linkId).update();
    }

    /** Deletes links that expired before {@code before}; returns how many. */
    int deleteExpiredLinks(Instant before) {
        return jdbc.sql("delete from stylist_digest_link where expires_at < :before")
                .param("before", Timestamp.from(before)).update();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
