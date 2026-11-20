package com.lookmate.app.tracking;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The match_event table, which this module owns. Append-only (a trigger refuses updates): a mistake is undone by a
 * VOIDED row pointing at it, never an edit. Reads go through the match_event_live view (V6), which leaves out VOIDED
 * rows and the rows they void. Joins the caller's transaction.
 */
@Repository
class MatchEvents {

    enum Type { VIEWED, CLICKED_BOOK, BOOKED, REBOOKED, VOIDED }

    enum Source { CLIENT, ADMIN, STYLIST }

    record New(UUID matchId, UUID matchRequestId, UUID orgId, Type type, Source source, UUID recordedBy, String note,
               Instant occurredAt, UUID voidsEventId) {

        New(UUID matchId, UUID matchRequestId, UUID orgId, Type type, Source source, UUID recordedBy, String note,
            Instant occurredAt) {
            this(matchId, matchRequestId, orgId, type, source, recordedBy, note, occurredAt, null);
        }
    }

    /** A recorded event, as the admin and the stylist see it. {@code voided} is set once a VOIDED row points at it. */
    record Event(UUID id, UUID matchId, UUID matchRequestId, UUID orgId, Type type, Source source, String note,
                 Instant occurredAt, Instant createdAt, boolean voided, String voidReason) {
    }

    private final JdbcClient jdbc;

    MatchEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID append(New e) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into match_event (id, match_id, match_request_id, org_id, type, source, recorded_by, note,
                          occurred_at, created_at, voids_event_id)
                        values (:id, :match, :request, :org, :type, :source, :by, :note, :occurredAt, :createdAt, :voids)
                        """)
                .param("voids", e.voidsEventId())
                .param("id", id)
                .param("match", e.matchId())
                .param("request", e.matchRequestId())
                .param("org", e.orgId())
                .param("type", e.type().name())
                .param("source", e.source().name())
                .param("by", e.recordedBy())
                .param("note", e.note())
                .param("occurredAt", Timestamp.from(e.occurredAt()))
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return id;
    }

    /**
     * Serialises writers on one key (a match, a request) until the transaction ends, so "is there one already?" and
     * the insert that follows can't interleave with another request doing the same.
     */
    void lock(UUID key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 7316))").param("key", key.toString()).query().singleValue();
    }

    boolean clickedSince(UUID matchId, Instant since) {
        return jdbc.sql("""
                        select exists (select 1 from match_event_live
                                       where match_id = :match and type = 'CLICKED_BOOK' and occurred_at > :since)
                        """)
                .param("match", matchId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    long clicksOnRequestSince(UUID requestId, Instant since) {
        return jdbc.sql("""
                        select count(*) from match_event_live
                        where match_request_id = :request and type = 'CLICKED_BOOK' and occurred_at >= :since
                        """)
                .param("request", requestId)
                .param("since", Timestamp.from(since))
                .query(Long.class).single();
    }

    boolean viewedSince(UUID requestId, Instant since) {
        return jdbc.sql("""
                        select exists (select 1 from match_event_live
                                       where match_request_id = :request and match_id is null and type = 'VIEWED'
                                         and occurred_at >= :since)
                        """)
                .param("request", requestId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    /** A directory click (no match) on this stylist from this request since {@code since}: the per-stylist dedupe. */
    boolean directoryClickedSince(UUID requestId, UUID orgId, Instant since) {
        return jdbc.sql("""
                        select exists (select 1 from match_event_live
                                       where match_request_id = :request and org_id = :org and match_id is null
                                         and type = 'CLICKED_BOOK' and occurred_at > :since)
                        """)
                .param("request", requestId)
                .param("org", orgId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    /** Whether a directory event (no match) of the type is recorded for this request and stylist. */
    boolean directoryExists(UUID requestId, UUID orgId, Type type) {
        return jdbc.sql("""
                        select exists (select 1 from match_event_live
                                       where match_request_id = :request and org_id = :org and match_id is null and type = :type)
                        """)
                .param("request", requestId)
                .param("org", orgId)
                .param("type", type.name())
                .query(Boolean.class).single();
    }

    boolean exists(UUID matchId, Type type) {
        return jdbc.sql("select exists (select 1 from match_event_live where match_id = :match and type = :type)")
                .param("match", matchId)
                .param("type", type.name())
                .query(Boolean.class).single();
    }

    /** Every event on a match except VOIDED rows themselves, newest first, each marked if it was voided. */
    java.util.List<Event> forMatch(UUID matchId) {
        return jdbc.sql(SELECT_EVENT + " where e.match_id = :match and e.type <> 'VOIDED' order by e.occurred_at desc, e.created_at desc")
                .param("match", matchId)
                .query((rs, n) -> event(rs))
                .list();
    }

    java.util.Optional<Event> find(UUID eventId) {
        return jdbc.sql(SELECT_EVENT + " where e.id = :id")
                .param("id", eventId)
                .query((rs, n) -> event(rs))
                .optional();
    }

    private static final String SELECT_EVENT = """
            select e.id, e.match_id, e.match_request_id, e.org_id, e.type, e.source, e.note, e.occurred_at, e.created_at,
                   v.id is not null as voided, v.note as void_reason
            from match_event e left join match_event v on v.voids_event_id = e.id
            """;

    private static Event event(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Event(rs.getObject("id", UUID.class), rs.getObject("match_id", UUID.class),
                rs.getObject("match_request_id", UUID.class), rs.getObject("org_id", UUID.class),
                Type.valueOf(rs.getString("type")), Source.valueOf(rs.getString("source")), rs.getString("note"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("voided"), rs.getString("void_reason"));
    }
}
