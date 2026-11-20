package com.lookmate.app.tracking;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The match_event table, which this module owns. Append-only (a trigger refuses updates): a correction is a new row,
 * never an edit. Joins the caller's transaction.
 */
@Repository
class MatchEvents {

    enum Type { VIEWED, CLICKED_BOOK, BOOKED, REBOOKED }

    enum Source { CLIENT, ADMIN, STYLIST }

    record New(UUID matchId, UUID matchRequestId, UUID orgId, Type type, Source source, UUID recordedBy, String note,
               Instant occurredAt) {
    }

    private final JdbcClient jdbc;

    MatchEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID append(New e) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into match_event (id, match_id, match_request_id, org_id, type, source, recorded_by, note,
                          occurred_at, created_at)
                        values (:id, :match, :request, :org, :type, :source, :by, :note, :occurredAt, :createdAt)
                        """)
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
                        select exists (select 1 from match_event
                                       where match_id = :match and type = 'CLICKED_BOOK' and occurred_at > :since)
                        """)
                .param("match", matchId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    boolean viewedSince(UUID requestId, Instant since) {
        return jdbc.sql("""
                        select exists (select 1 from match_event
                                       where match_request_id = :request and match_id is null and type = 'VIEWED'
                                         and occurred_at >= :since)
                        """)
                .param("request", requestId)
                .param("since", Timestamp.from(since))
                .query(Boolean.class).single();
    }

    boolean exists(UUID matchId, Type type) {
        return jdbc.sql("select exists (select 1 from match_event where match_id = :match and type = :type)")
                .param("match", matchId)
                .param("type", type.name())
                .query(Boolean.class).single();
    }
}
