package com.lookmate.app.consult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code consult_session}. */
@Repository
class ConsultSessions {

    enum Channel { GENERAL, PARTNER }

    record Session(UUID id, UUID rulesetId, int rulesetVersion, String market, Channel channel, String outcomeId,
                   int ruleIndex, Instant createdAt, int bookClicks, Instant bookedAt, boolean shareWithStylist,
                   Instant findStylistAt) {
    }

    private final JdbcClient jdbc;

    ConsultSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code answers} must hold stored questions only ({@link Ruleset#storable}). */
    UUID insert(UUID rulesetId, int version, String market, Channel channel, Map<String, List<String>> answers,
                String outcomeId, int ruleIndex, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into consult_session (id, ruleset_id, ruleset_version, market, channel, answers, outcome_id,
                          rule_index, created_at)
                        values (:id, :ruleset, :version, :market, :channel, cast(:answers as jsonb), :outcome, :rule, :now)""")
                .param("id", id).param("ruleset", rulesetId).param("version", version).param("market", market)
                .param("channel", channel.name()).param("answers", RulesetJson.write(answers))
                .param("outcome", outcomeId).param("rule", ruleIndex).param("now", Timestamp.from(now))
                .update();
        return id;
    }

    Optional<Session> find(UUID id) {
        return jdbc.sql("""
                        select id, ruleset_id, ruleset_version, market, channel, outcome_id, rule_index, created_at,
                          book_clicks, booked_at, share_with_stylist, find_stylist_at
                        from consult_session where id = :id""")
                .param("id", id)
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class), rs.getObject("ruleset_id", UUID.class),
                        rs.getInt("ruleset_version"), rs.getString("market"), Channel.valueOf(rs.getString("channel")),
                        rs.getString("outcome_id"), rs.getInt("rule_index"), instant(rs.getTimestamp("created_at")),
                        rs.getInt("book_clicks"), instant(rs.getTimestamp("booked_at")), rs.getBoolean("share_with_stylist"),
                        instant(rs.getTimestamp("find_stylist_at"))))
                .optional();
    }

    /** The stored answers, as JSON. */
    String answers(UUID id) {
        return jdbc.sql("select answers::text from consult_session where id = :id").param("id", id).query(String.class).single();
    }

    /** A Book click. The opt-in only ever turns on: a later click without the tick doesn't take back an earlier yes. */
    void booked(UUID id, boolean share, Instant now) {
        jdbc.sql("""
                        update consult_session set book_clicks = book_clicks + 1, booked_at = coalesce(booked_at, :now),
                          share_with_stylist = share_with_stylist or :share
                        where id = :id""")
                .param("id", id).param("share", share).param("now", Timestamp.from(now)).update();
    }

    void findStylist(UUID id, Instant now) {
        jdbc.sql("update consult_session set find_stylist_at = coalesce(find_stylist_at, :now) where id = :id")
                .param("id", id).param("now", Timestamp.from(now)).update();
    }

    /** Deletes sessions created before {@code cutoff}; returns how many. */
    int deleteBefore(Instant cutoff) {
        return jdbc.sql("delete from consult_session where created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
