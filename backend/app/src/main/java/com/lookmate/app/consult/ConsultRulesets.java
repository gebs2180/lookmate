package com.lookmate.app.consult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for {@code consult_ruleset}. Versions are only ever inserted (a trigger refuses updates), so a parsed document is
 * cached by id for good.
 */
@Repository
class ConsultRulesets {

    /** The pilot's one ruleset, behind both the general button and the partner's link. */
    static final String DEFAULT_KEY = "default";

    /** A published version. */
    record Version(UUID id, int version, String credit, String source, Instant publishedAt, UUID publishedBy) {
    }

    private static final String COLUMNS = "id, version, credit, source, published_at, published_by";

    private final JdbcClient jdbc;
    private final Map<UUID, Ruleset> parsed = new ConcurrentHashMap<>();

    ConsultRulesets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Version> latest(String key) {
        return jdbc.sql("select " + COLUMNS + " from consult_ruleset where ruleset_key = :key order by version desc limit 1")
                .param("key", key).query(ConsultRulesets::version).optional();
    }

    List<Version> list(String key) {
        return jdbc.sql("select " + COLUMNS + " from consult_ruleset where ruleset_key = :key order by version desc")
                .param("key", key).query(ConsultRulesets::version).list();
    }

    /** The version's ruleset, parsed once. */
    Ruleset ruleset(UUID id) {
        return parsed.computeIfAbsent(id, k -> RulesetJson.read(jdbc.sql("select document::text from consult_ruleset where id = :id")
                .param("id", k).query(String.class).single()));
    }

    /**
     * Inserts the next version. Two publishes at once can pick the same number; the unique constraint refuses the
     * second, which the API answers with 409 (try again).
     */
    Version publish(String key, Ruleset r, String source, UUID by, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into consult_ruleset (id, ruleset_key, version, credit, document, source, published_at, published_by)
                        select :id, :key, coalesce(max(version), 0) + 1, :credit, cast(:doc as jsonb), :source, :now, :by
                        from consult_ruleset where ruleset_key = :key""")
                .param("id", id).param("key", key).param("credit", r.credit()).param("doc", RulesetJson.write(r))
                .param("source", source).param("now", Timestamp.from(now)).param("by", by)
                .update();
        parsed.put(id, r);
        return jdbc.sql("select " + COLUMNS + " from consult_ruleset where id = :id")
                .param("id", id).query(ConsultRulesets::version).single();
    }

    private static Version version(ResultSet rs, int row) throws SQLException {
        return new Version(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("credit"),
                rs.getString("source"), rs.getTimestamp("published_at").toInstant(), rs.getObject("published_by", UUID.class));
    }
}
