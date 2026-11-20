package com.lookmate.app.matching;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The match request's status from MATCHING on: matching moves it to IN_REVIEW, PUBLISHED or FAILED. These are the only
 * columns of {@code match_request} (an intake table) that matching writes: status, status_reason, reviewed_by,
 * published_at and updated_at (docs/modules/matching.md). Each update names the status it expects, so a request
 * someone else moved on is left alone.
 */
@Repository
class RequestTransitions {

    private final JdbcClient jdbc;

    RequestTransitions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the request row for an admin change; returns its status, or null if there is no such request. */
    String lock(UUID id) {
        return jdbc.sql("select status from match_request where id = :id for update").param("id", id).query(String.class).optional().orElse(null);
    }

    boolean toReview(UUID id, String reason, Instant now) {
        return move(id, "MATCHING", "IN_REVIEW", reason, now);
    }

    boolean failed(UUID id, String reason, Instant now) {
        return move(id, "MATCHING", "FAILED", reason, now);
    }

    boolean published(UUID id, UUID reviewer, Instant now) {
        return jdbc.sql("""
                        update match_request set status = 'PUBLISHED', status_reason = null, reviewed_by = :reviewer,
                          published_at = :now, updated_at = :now
                        where id = :id and status = 'IN_REVIEW'
                        """)
                .param("id", id).param("reviewer", reviewer).param("now", Timestamp.from(now)).update() == 1;
    }

    private boolean move(UUID id, String from, String to, String reason, Instant now) {
        return jdbc.sql("update match_request set status = :to, status_reason = :reason, updated_at = :now where id = :id and status = :from")
                .param("id", id).param("from", from).param("to", to).param("reason", reason).param("now", Timestamp.from(now))
                .update() == 1;
    }
}
