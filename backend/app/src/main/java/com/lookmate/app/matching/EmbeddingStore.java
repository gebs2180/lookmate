package com.lookmate.app.matching;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes the embedding tables (matching's, though V2 created them) and the worker's failure bookkeeping. */
@Repository
class EmbeddingStore {

    enum Subject { PORTFOLIO_PHOTO, MATCH_REQUEST }

    private final JdbcClient jdbc;

    EmbeddingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void save(Subject subject, UUID id, String model, float[] vector, Instant now) {
        String sql = subject == Subject.PORTFOLIO_PHOTO
                ? "insert into portfolio_photo_embedding (photo_id, model, embedding, created_at) values (?, ?, ?::vector, ?) on conflict do nothing"
                : "insert into match_request_embedding (match_request_id, model, embedding, created_at) values (?, ?, ?::vector, ?) on conflict do nothing";
        jdbc.sql(sql).params(id, model, literal(vector), Timestamp.from(now)).update();
        jdbc.sql("delete from embedding_attempt where subject_type = ? and subject_id = ? and model = ?")
                .params(subject.name(), id, model).update();
    }

    boolean has(Subject subject, UUID id, String model) {
        String sql = subject == Subject.PORTFOLIO_PHOTO
                ? "select exists (select 1 from portfolio_photo_embedding where photo_id = ? and model = ?)"
                : "select exists (select 1 from match_request_embedding where match_request_id = ? and model = ?)";
        return Boolean.TRUE.equals(jdbc.sql(sql).params(id, model).query(Boolean.class).single());
    }

    /** Records a failed try and when to try again (backoff times attempts). Returns the attempts so far. */
    int failed(Subject subject, UUID id, String model, String error, java.time.Duration backoff, Instant now) {
        return jdbc.sql("""
                        insert into embedding_attempt (subject_type, subject_id, model, attempts, last_error, next_attempt_at, updated_at)
                        values (:type, :id, :model, 1, :error, :next, :now)
                        on conflict (subject_type, subject_id, model) do update set
                          attempts = embedding_attempt.attempts + 1, last_error = excluded.last_error,
                          next_attempt_at = cast(:now as timestamptz) + (embedding_attempt.attempts + 1) * cast(:backoff as interval),
                          updated_at = excluded.updated_at
                        returning attempts
                        """)
                .param("type", subject.name()).param("id", id).param("model", model)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .param("next", Timestamp.from(now.plus(backoff))).param("now", Timestamp.from(now))
                .param("backoff", backoff.toSeconds() + " seconds")
                .query(Integer.class).single();
    }

    /** pgvector's text form, e.g. {@code [0.1,-0.2]}. */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("embedding has a non-finite value");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
