package com.lookmate.app.matching;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the embedding tables (matching's, though V2 created them) and the worker's bookkeeping: claim leases and
 * failures for portfolio photos ({@code embedding_attempt}), and a request's tries ({@code match_request_attempt}, V11).
 */
@Repository
class EmbeddingStore {

    /** What a request's try was for; each counts up to {@code max-attempts} on its own. */
    enum Step { EMBED, RANK }

    private final JdbcClient jdbc;

    EmbeddingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void savePortfolio(UUID photoId, String model, float[] vector, Instant now) {
        jdbc.sql("insert into portfolio_photo_embedding (photo_id, model, embedding, created_at) values (?, ?, ?::vector, ?) on conflict do nothing")
                .params(photoId, model, literal(vector), Timestamp.from(now)).update();
        jdbc.sql("delete from embedding_attempt where subject_type = 'PORTFOLIO_PHOTO' and subject_id = ? and model = ?")
                .params(photoId, model).update();
    }

    /**
     * Holds a claimed photo until {@code until}, so no other pass claims it while the provider call runs outside the
     * claiming transaction (review #8). Keeps the attempts so far; a success deletes the row, a failure replaces the
     * lease with the backoff, and a crash lets the lease run out.
     */
    void lease(UUID photoId, String model, Instant until, Instant now) {
        jdbc.sql("""
                        insert into embedding_attempt (subject_type, subject_id, model, attempts, last_error, next_attempt_at, updated_at)
                        values ('PORTFOLIO_PHOTO', :id, :model, 0, null, :until, :now)
                        on conflict (subject_type, subject_id, model) do update set
                          next_attempt_at = excluded.next_attempt_at, updated_at = excluded.updated_at
                        """)
                .param("id", photoId).param("model", model).param("until", Timestamp.from(until)).param("now", Timestamp.from(now))
                .update();
    }

    /** Records a portfolio photo's failed try and when to try again (backoff times attempts). Returns the attempts so far. */
    int failedPortfolio(UUID photoId, String model, String error, Duration backoff, Instant now) {
        return jdbc.sql("""
                        insert into embedding_attempt (subject_type, subject_id, model, attempts, last_error, next_attempt_at, updated_at)
                        values ('PORTFOLIO_PHOTO', :id, :model, 1, :error, :next, :now)
                        on conflict (subject_type, subject_id, model) do update set
                          attempts = embedding_attempt.attempts + 1, last_error = excluded.last_error,
                          next_attempt_at = cast(:now as timestamptz) + (embedding_attempt.attempts + 1) * cast(:backoff as interval),
                          updated_at = excluded.updated_at
                        returning attempts
                        """)
                .param("id", photoId).param("model", model).param("error", truncate(error))
                .param("next", Timestamp.from(now.plus(backoff))).param("now", Timestamp.from(now))
                .param("backoff", backoff.toSeconds() + " seconds")
                .query(Integer.class).single();
    }

    /**
     * Saves the inspiration embedding with the hash of the photo it was made from, replacing an older one: after a
     * retake the stored vector belongs to the previous photo.
     */
    void saveInspiration(UUID requestId, String model, float[] vector, String sha256, Instant now) {
        jdbc.sql("""
                        insert into match_request_embedding (match_request_id, model, embedding, inspiration_sha256, created_at)
                        values (?, ?, ?::vector, ?, ?)
                        on conflict (match_request_id, model) do update set
                          embedding = excluded.embedding, inspiration_sha256 = excluded.inspiration_sha256, created_at = excluded.created_at
                        """)
                .params(requestId, model, literal(vector), sha256, Timestamp.from(now)).update();
    }

    /**
     * Counts a request's failed try at {@code step} and returns that step's tries so far. The counts belong to the
     * request's current photo (read in the same statement): after a retake they start again (review #23, group A).
     */
    int failedRequest(UUID requestId, Step step, String error, Instant now) {
        return jdbc.sql("""
                        insert into match_request_attempt (match_request_id, inspiration_sha256, embed_attempts, rank_attempts, last_error, updated_at)
                        select r.id, r.inspiration_sha256, :embed, :rank, :error, :now from match_request r where r.id = :id
                        on conflict (match_request_id) do update set
                          embed_attempts = excluded.embed_attempts + case when match_request_attempt.inspiration_sha256
                            is not distinct from excluded.inspiration_sha256 then match_request_attempt.embed_attempts else 0 end,
                          rank_attempts = excluded.rank_attempts + case when match_request_attempt.inspiration_sha256
                            is not distinct from excluded.inspiration_sha256 then match_request_attempt.rank_attempts else 0 end,
                          inspiration_sha256 = excluded.inspiration_sha256, last_error = excluded.last_error, updated_at = excluded.updated_at
                        returning embed_attempts, rank_attempts
                        """)
                .param("id", requestId).param("embed", step == Step.EMBED ? 1 : 0).param("rank", step == Step.RANK ? 1 : 0)
                .param("error", truncate(error)).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getInt(step == Step.EMBED ? "embed_attempts" : "rank_attempts")).single();
    }

    /** Forgets a request's tries once it is ranked. */
    void clearRequest(UUID requestId) {
        jdbc.sql("delete from match_request_attempt where match_request_id = ?").params(requestId).update();
    }

    private static String truncate(String error) {
        return error == null ? null : error.substring(0, Math.min(error.length(), 500));
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
