package com.lookmate.app.matching;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code match} rows of a request. */
@Repository
class MatchStore {

    record MatchRow(UUID id, UUID requestId, UUID orgId, double score, String reason, List<UUID> evidence, int systemRank,
                    Integer finalRank, boolean hidden, boolean adminOverride, String adminNote, String source) {

        boolean visible() {
            return !hidden;
        }
    }

    private final JdbcClient jdbc;

    MatchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A system match. Its final rank starts as the system rank, so publishing untouched keeps the ranker's order. */
    void insertSystem(UUID requestId, UUID orgId, double score, String reason, List<UUID> evidence, int rank, Instant now) {
        insert(requestId, orgId, score, reason, evidence, rank, rank, false, null, "SYSTEM", now);
    }

    /** A stylist the reviewer added; it goes last. */
    UUID insertByAdmin(UUID requestId, UUID orgId, List<UUID> evidence, String note, Instant now) {
        int next = jdbc.sql("select coalesce(max(greatest(system_rank, coalesce(final_rank, 0))), 0) + 1 from match where match_request_id = :r")
                .param("r", requestId).query(Integer.class).single();
        return insert(requestId, orgId, 0, Reasons.ADDED_BY_REVIEWER, evidence, next, next, true, note, "ADMIN", now);
    }

    private UUID insert(UUID requestId, UUID orgId, double score, String reason, List<UUID> evidence, int systemRank, int finalRank,
                        boolean override, String note, String source, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into match (id, match_request_id, org_id, score, reason, evidence_photo_ids, system_rank, final_rank, hidden,
                          admin_override, admin_note, source, created_at, updated_at)
                        values (:id, :request, :org, :score, :reason, :evidence, :systemRank, :finalRank, false, :override, :note, :source, :now, :now)
                        on conflict (match_request_id, org_id) do nothing
                        """)
                .param("id", id).param("request", requestId).param("org", orgId).param("score", score).param("reason", reason)
                .param("evidence", evidence.toArray(UUID[]::new)).param("systemRank", systemRank).param("finalRank", finalRank)
                .param("override", override).param("note", note).param("source", source).param("now", Timestamp.from(now))
                .update();
        return id;
    }

    /** The request's matches in the reviewer's order (final rank, then system rank). */
    List<MatchRow> forRequest(UUID requestId) {
        return jdbc.sql("select * from match where match_request_id = :r order by final_rank nulls last, system_rank, id")
                .param("r", requestId).query(MatchStore::row).list();
    }

    /** What the client sees: ranked and not hidden. */
    List<MatchRow> published(UUID requestId) {
        return jdbc.sql("select * from match where match_request_id = :r and final_rank is not null and not hidden order by final_rank, id")
                .param("r", requestId).query(MatchStore::row).list();
    }

    void setFinalRank(UUID matchId, Integer rank, boolean override, Instant now) {
        jdbc.sql("update match set final_rank = :rank, admin_override = admin_override or :override, updated_at = :now where id = :id")
                .param("id", matchId).param("rank", rank).param("override", override).param("now", Timestamp.from(now)).update();
    }

    void update(UUID matchId, boolean hidden, String note, boolean override, Instant now) {
        jdbc.sql("""
                        update match set hidden = :hidden, admin_note = :note, admin_override = admin_override or :override, updated_at = :now
                        where id = :id
                        """)
                .param("id", matchId).param("hidden", hidden).param("note", note).param("override", override)
                .param("now", Timestamp.from(now)).update();
    }

    Optional<MatchRow> find(UUID requestId, UUID matchId) {
        return jdbc.sql("select * from match where id = :id and match_request_id = :r").param("id", matchId).param("r", requestId)
                .query(MatchStore::row).optional();
    }

    private static MatchRow row(ResultSet rs, int n) throws SQLException {
        return new MatchRow(rs.getObject("id", UUID.class), rs.getObject("match_request_id", UUID.class), rs.getObject("org_id", UUID.class),
                rs.getDouble("score"), rs.getString("reason"), uuids(rs.getArray("evidence_photo_ids")), rs.getInt("system_rank"),
                (Integer) rs.getObject("final_rank", Integer.class), rs.getBoolean("hidden"), rs.getBoolean("admin_override"),
                rs.getString("admin_note"), rs.getString("source"));
    }

    private static List<UUID> uuids(Array a) throws SQLException {
        return a == null ? List.of() : List.of((UUID[]) a.getArray());
    }
}
