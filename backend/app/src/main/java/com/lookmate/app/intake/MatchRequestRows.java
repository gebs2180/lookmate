package com.lookmate.app.intake;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code client}, {@code match_request} and {@code match_request_link}. Callers own the transactions. */
@Repository
class MatchRequestRows {

    /** A request with its client's quiz answers and contact. {@code email} is null until the client gives one (it's optional). */
    record Row(UUID id, UUID clientId, String market, String status, String statusReason, String service, String objectKey,
               String contentType, Long sizeBytes, String sha256, Instant inspirationDeletedAt, Instant createdAt, Instant updatedAt,
               Instant publishedAt, String email, String firstName, String hairType, String texture, String length, Integer budgetTier,
               String neighborhood, List<String> pastIssues, String notes, int emailChanges) {

        boolean inspirationReady() {
            return sha256 != null && inspirationDeletedAt == null;
        }
    }

    record NewRequest(UUID clientId, UUID requestId, String market, String email, String firstName, String hairType, String texture,
                      String length, Integer budgetTier, String neighborhood, List<String> pastIssues, String notes, String service,
                      String objectKey, String contentType, long sizeBytes, String tokenHash, Instant expiresAt, Instant now) {
    }

    private static final String SELECT = """
            select r.id, r.client_id, r.market, r.status, r.status_reason, r.service, r.inspiration_object_key,
                   r.inspiration_content_type, r.inspiration_size_bytes, r.inspiration_sha256, r.inspiration_deleted_at,
                   r.created_at, r.updated_at, r.published_at, c.email, c.first_name, c.hair_type, c.texture, c.length,
                   c.budget_tier, c.neighborhood, c.past_issues, c.notes, c.email_changes
            from match_request r join client c on c.id = r.client_id
            """;

    private final JdbcClient jdbc;

    MatchRequestRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(NewRequest n) {
        jdbc.sql("""
                        insert into client (id, email, first_name, hair_type, texture, length, budget_tier, neighborhood, market,
                          past_issues, notes, created_at, email_set_at, email_changes)
                        values (:id, :email, :firstName, :hairType, :texture, :length, :budget, :neighborhood, :market,
                          string_to_array(:issues, ','), :notes, :now, :emailSetAt, :emailChanges)""")
                .param("id", n.clientId())
                .param("email", n.email())
                .param("firstName", n.firstName())
                .param("hairType", n.hairType())
                .param("texture", n.texture())
                .param("length", n.length())
                .param("budget", n.budgetTier())
                .param("neighborhood", n.neighborhood())
                .param("market", n.market())
                .param("issues", String.join(",", n.pastIssues()))
                .param("notes", n.notes())
                .param("now", ts(n.now()))
                .param("emailSetAt", n.email() == null ? null : ts(n.now()))
                .param("emailChanges", n.email() == null ? 0 : 1)
                .update();
        jdbc.sql("""
                        insert into match_request (id, client_id, market, service, inspiration_object_key, inspiration_content_type,
                          inspiration_size_bytes, access_token_hash, status, created_at, updated_at)
                        values (:id, :client, :market, :service, :key, :type, :size, :hash, 'NEW', :now, :now)""")
                .param("id", n.requestId())
                .param("client", n.clientId())
                .param("market", n.market())
                .param("service", n.service())
                .param("key", n.objectKey())
                .param("type", n.contentType())
                .param("size", n.sizeBytes())
                .param("hash", n.tokenHash())
                .param("now", ts(n.now()))
                .update();
        insertLink(n.tokenHash(), n.requestId(), "CREATED", n.now(), n.expiresAt());
    }

    void insertLink(String tokenHash, UUID requestId, String purpose, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        insert into match_request_link (token_hash, match_request_id, purpose, created_at, expires_at)
                        values (:hash, :id, :purpose, :now, :expires)""")
                .param("hash", tokenHash)
                .param("id", requestId)
                .param("purpose", purpose)
                .param("now", ts(now))
                .param("expires", ts(expiresAt))
                .update();
    }

    /** The request a link token opens, if the link exists and hasn't expired. */
    Optional<UUID> requestIdForToken(String tokenHash, Instant now) {
        return jdbc.sql("select match_request_id from match_request_link where token_hash = :hash and expires_at > :now")
                .param("hash", tokenHash)
                .param("now", ts(now))
                .query(UUID.class)
                .optional();
    }

    Optional<Row> find(UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id").param("id", id).query(MatchRequestRows::row).optional();
    }

    /** The request, locked until the transaction ends. */
    Optional<Row> findLocked(UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id for update of r").param("id", id).query(MatchRequestRows::row).optional();
    }

    List<Row> findAll(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " where r.id in (:ids) order by r.created_at, r.id").param("ids", ids).query(MatchRequestRows::row).list();
    }

    List<Row> list(String status, int limit) {
        return jdbc.sql(SELECT + " where r.status = :status order by r.created_at desc, r.id limit :limit")
                .param("status", status)
                .param("limit", limit)
                .query(MatchRequestRows::row)
                .list();
    }

    List<Row> recent(String market, int limit) {
        return jdbc.sql(SELECT + " where (cast(:market as text) is null or r.market = :market) order by r.created_at desc, r.id limit :limit")
                .param("market", market)
                .param("limit", limit)
                .query(MatchRequestRows::row)
                .list();
    }

    /** Serialises setting one address on requests (the per-address cap), until the transaction ends. */
    void lockEmail(String email) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(lower(:email)))").param("email", email).query().listOfRows();
    }

    /** Requests other than {@code except} that had this address set since {@code since}. */
    int emailsSetSince(String email, UUID exceptClient, Instant since) {
        return jdbc.sql("""
                        select count(*) from client
                        where email is not null and lower(email) = lower(:email) and email_set_at > :since and id <> :except""")
                .param("email", email)
                .param("except", exceptClient)
                .param("since", ts(since))
                .query(Integer.class)
                .single();
    }

    /** Sets (or replaces) the client's email and counts the change. */
    void setEmail(UUID clientId, String email, Instant now) {
        jdbc.sql("update client set email = :email, email_set_at = :now, email_changes = email_changes + 1 where id = :id")
                .param("id", clientId)
                .param("email", email)
                .param("now", ts(now))
                .update();
    }

    /** Claims due requests: NEW with a confirmed photo, or MATCHING and stuck. Returns their ids, oldest first. */
    List<UUID> claim(int limit, Instant stuckBefore, Instant now) {
        return jdbc.sql("""
                        with picked as (
                          select id from match_request
                          where (status = 'NEW' and inspiration_sha256 is not null and inspiration_deleted_at is null)
                             or (status = 'MATCHING' and updated_at < :stuck)
                          order by created_at, id
                          limit :limit
                          for update skip locked)
                        update match_request r set status = 'MATCHING', status_reason = null, updated_at = :now
                        from picked where r.id = picked.id
                        returning r.id""")
                .param("limit", limit)
                .param("stuck", ts(stuckBefore))
                .param("now", ts(now))
                .query(UUID.class)
                .list();
    }

    void setStatus(UUID id, String status, String reason, Instant now) {
        jdbc.sql("update match_request set status = :status, status_reason = :reason, updated_at = :now where id = :id")
                .param("id", id)
                .param("status", status)
                .param("reason", reason)
                .param("now", ts(now))
                .update();
    }

    void setPublished(UUID id, UUID reviewedBy, String reason, Instant now) {
        jdbc.sql("""
                        update match_request set status = 'PUBLISHED', status_reason = :reason, reviewed_by = :by, published_at = :now,
                          updated_at = :now where id = :id""")
                .param("id", id)
                .param("by", reviewedBy)
                .param("reason", reason)
                .param("now", ts(now))
                .update();
    }

    void setUploadDeclared(UUID id, String contentType, long sizeBytes, Instant now) {
        jdbc.sql("update match_request set inspiration_content_type = :type, inspiration_size_bytes = :size, updated_at = :now where id = :id")
                .param("id", id)
                .param("type", contentType)
                .param("size", sizeBytes)
                .param("now", ts(now))
                .update();
    }

    void setInspirationReady(UUID id, String contentType, long sizeBytes, String sha256, Instant now) {
        jdbc.sql("""
                        update match_request set inspiration_content_type = :type, inspiration_size_bytes = :size,
                          inspiration_sha256 = :sha, updated_at = :now where id = :id""")
                .param("id", id)
                .param("type", contentType)
                .param("size", sizeBytes)
                .param("sha", sha256)
                .param("now", ts(now))
                .update();
    }

    /** Requests whose inspiration photo is due for deletion, oldest first. */
    List<Row> inspirationDue(Instant createdBefore, int limit) {
        return jdbc.sql(SELECT + """
                         where r.inspiration_deleted_at is null and r.inspiration_object_key is not null and r.created_at < :cutoff
                        order by r.created_at, r.id limit :limit""")
                .param("cutoff", ts(createdBefore))
                .param("limit", limit)
                .query(MatchRequestRows::row)
                .list();
    }

    void setInspirationDeleted(UUID id, Instant now) {
        jdbc.sql("update match_request set inspiration_deleted_at = :now, updated_at = :now where id = :id and inspiration_deleted_at is null")
                .param("id", id)
                .param("now", ts(now))
                .update();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class), rs.getString("market"),
                rs.getString("status"), rs.getString("status_reason"), rs.getString("service"), rs.getString("inspiration_object_key"),
                rs.getString("inspiration_content_type"), (Long) rs.getObject("inspiration_size_bytes"), rs.getString("inspiration_sha256"),
                instant(rs, "inspiration_deleted_at"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "published_at"),
                rs.getString("email"), rs.getString("first_name"), rs.getString("hair_type"), rs.getString("texture"), rs.getString("length"),
                (Integer) rs.getObject("budget_tier", Integer.class), rs.getString("neighborhood"), strings(rs.getArray("past_issues")),
                rs.getString("notes"), rs.getInt("email_changes"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
