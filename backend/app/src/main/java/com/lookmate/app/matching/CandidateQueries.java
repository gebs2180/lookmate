package com.lookmate.app.matching;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL over tables the stylists and intake modules own ({@code stylist_profile}, {@code salon_location},
 * {@code portfolio_photo}, and {@code client} and {@code match_request} for the fairness report and the inspiration
 * photo's key). The one sanctioned cross-module exception (docs/architecture.md): built while the stylists module was
 * written in parallel; it moves onto {@code Stylists} (docs/modules/stylists.md) once that is merged. Requests
 * otherwise go through intake's {@code MatchRequests}. Nothing here writes; the claim query only locks rows
 * ({@code FOR UPDATE SKIP LOCKED}) so two app instances never embed the same photo.
 */
@Repository
class CandidateQueries {

    private final JdbcClient jdbc;

    CandidateQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record PendingImage(UUID id, String objectKey, String contentType) {
    }

    record Stylist(UUID orgId, String displayName, String locationName, String market, String status, String instagram, boolean demo) {
    }

    record Photo(UUID id, UUID orgId, String objectKey, String service, String hairType, String status) {
    }

    /**
     * The inspiration photo's object key, for the admin's signed link; empty once retention deleted it. Intake's
     * {@code MatchRequests} has no link for it yet (docs/modules/matching.md, open questions).
     */
    Optional<String> inspirationKey(UUID requestId) {
        return jdbc.sql("""
                        select inspiration_object_key from match_request
                        where id = :id and inspiration_sha256 is not null and inspiration_deleted_at is null
                        """)
                .param("id", requestId).query(String.class).optional();
    }

    /**
     * Locks the request row for a reviewer's change (so two reviewers can't interleave) and returns its status, or
     * empty for no such request. A lock, not a write; intake's {@code MatchRequests.publish} takes the same lock.
     */
    Optional<String> lockStatus(UUID requestId) {
        return jdbc.sql("select status from match_request where id = :id for update").param("id", requestId).query(String.class).optional();
    }

    /**
     * Locks and returns READY portfolio photos with no embedding for the model, skipping rows another instance holds
     * and photos that failed recently or too often. Call inside a transaction; the locks last until it ends.
     */
    List<PendingImage> claimPortfolioPhotosToEmbed(String model, Instant now, int maxAttempts, int limit) {
        return jdbc.sql("""
                        select p.id, p.object_key, p.content_type from portfolio_photo p
                        where p.status = 'READY'
                          and not exists (select 1 from portfolio_photo_embedding e where e.photo_id = p.id and e.model = :model)
                          and not exists (select 1 from embedding_attempt a
                                          where a.subject_type = 'PORTFOLIO_PHOTO' and a.subject_id = p.id and a.model = :model
                                            and (a.attempts >= :max or a.next_attempt_at > :now))
                        order by p.created_at, p.id
                        limit :limit
                        for update of p skip locked
                        """)
                .param("model", model).param("now", Timestamp.from(now)).param("max", maxAttempts).param("limit", limit)
                .query((rs, n) -> new PendingImage(rs.getObject("id", UUID.class), rs.getString("object_key"), rs.getString("content_type")))
                .list();
    }

    /**
     * The candidates: READY photos of LIVE stylists in the market, of the service (any, if the request has none) and
     * one of the hair types, embedded with the model, with their cosine similarity to the request's inspiration photo.
     * An exact scan; docs/architecture.md says when to add an HNSW index.
     */
    List<Scoring.PhotoMatch> similarities(UUID requestId, String model, String market, String service, Collection<HairType> hairTypes) {
        if (hairTypes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select p.id, p.org_id, p.hair_type, 1 - (e.embedding <=> q.embedding) as similarity
                        from match_request_embedding q
                        join portfolio_photo_embedding e on e.model = q.model
                        join portfolio_photo p on p.id = e.photo_id
                        join stylist_profile s on s.org_id = p.org_id
                        where q.match_request_id = :request and q.model = :model
                          and s.status = 'LIVE' and s.market = :market
                          and p.status = 'READY' and p.hair_type in (:types)
                          and (cast(:service as text) is null or p.service = :service)
                        """)
                .param("request", requestId).param("model", model).param("market", market).param("service", service)
                .param("types", hairTypes.stream().map(Enum::name).toList())
                .query((rs, n) -> new Scoring.PhotoMatch(rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class),
                        HairType.parse(rs.getString("hair_type")), rs.getDouble("similarity")))
                .list();
    }

    /** One stylist's READY photos of the service (any, if null), most similar to the request first where embedded. */
    List<UUID> bestPhotosOf(UUID orgId, UUID requestId, String model, String service, int limit) {
        return jdbc.sql("""
                        select p.id from portfolio_photo p
                        left join portfolio_photo_embedding e on e.photo_id = p.id and e.model = :model
                        left join match_request_embedding q on q.match_request_id = :request and q.model = :model
                        where p.org_id = :org and p.status = 'READY' and (cast(:service as text) is null or p.service = :service)
                        order by (e.embedding <=> q.embedding) nulls last, p.position, p.id
                        limit :limit
                        """)
                .param("org", orgId).param("request", requestId).param("model", model).param("service", service).param("limit", limit)
                .query(UUID.class).list();
    }

    List<Stylist> stylists(Collection<UUID> orgIds) {
        if (orgIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(STYLIST + " where s.org_id in (:ids)").param("ids", orgIds).query(CandidateQueries::stylist).list();
    }

    /** LIVE stylists in the market, by name: who the reviewer can add by hand. */
    List<Stylist> liveStylists(String market) {
        return jdbc.sql(STYLIST + " where s.status = 'LIVE' and s.market = :market order by s.display_name, s.org_id")
                .param("market", market).query(CandidateQueries::stylist).list();
    }

    private static final String STYLIST = """
            select s.org_id, s.display_name, l.name as location_name, s.market, s.status, s.instagram, s.demo
            from stylist_profile s left join salon_location l on l.id = s.location_id
            """;

    List<Photo> photos(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select id, org_id, object_key, service, hair_type, status from portfolio_photo where id in (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new Photo(rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getString("object_key"),
                        rs.getString("service"), rs.getString("hair_type"), rs.getString("status")))
                .list();
    }

    /** Per client hair type (null as UNKNOWN), for the fairness report. Clicks and bookings come from match_event. */
    record FairnessRow(String hairType, long requests, long failed, long reviewed, long published, long noSystemMatches,
                       long withAdminChanges, long shownMatches, long clickedRequests, long bookedRequests, Double meanTopScore) {
    }

    List<FairnessRow> fairness(String market, Instant since) {
        return jdbc.sql("""
                        select coalesce(c.hair_type, 'UNKNOWN') as hair_type,
                          count(*) as requests,
                          count(*) filter (where r.status = 'FAILED') as failed,
                          count(*) filter (where r.status in ('IN_REVIEW', 'PUBLISHED', 'CLOSED')) as reviewed,
                          count(*) filter (where r.published_at is not null) as published,
                          count(*) filter (where r.status in ('IN_REVIEW', 'PUBLISHED', 'CLOSED')
                            and not exists (select 1 from match m where m.match_request_id = r.id and m.source = 'SYSTEM')) as no_system_matches,
                          count(*) filter (where exists (select 1 from match m where m.match_request_id = r.id and m.admin_override)) as with_admin_changes,
                          coalesce(sum((select count(*) from match m where m.match_request_id = r.id and r.published_at is not null
                            and m.final_rank is not null and not m.hidden)), 0) as shown_matches,
                          count(*) filter (where exists (select 1 from match_event v where v.match_request_id = r.id and v.type = 'CLICKED_BOOK')) as clicked,
                          count(*) filter (where exists (select 1 from match_event v where v.match_request_id = r.id and v.type = 'BOOKED')) as booked,
                          avg((select max(m.score) from match m where m.match_request_id = r.id and m.source = 'SYSTEM')) as mean_top_score
                        from match_request r join client c on c.id = r.client_id
                        where r.market = :market and r.created_at >= :since
                        group by 1
                        order by array_position(array['STRAIGHT', 'WAVY', 'CURLY', 'COILY', 'UNKNOWN'], coalesce(c.hair_type, 'UNKNOWN'))
                        """)
                .param("market", market).param("since", Timestamp.from(since))
                .query((rs, n) -> new FairnessRow(rs.getString("hair_type"), rs.getLong("requests"), rs.getLong("failed"),
                        rs.getLong("reviewed"), rs.getLong("published"), rs.getLong("no_system_matches"), rs.getLong("with_admin_changes"),
                        rs.getLong("shown_matches"), rs.getLong("clicked"), rs.getLong("booked"),
                        rs.getObject("mean_top_score") == null ? null : rs.getDouble("mean_top_score")))
                .list();
    }

    private static Stylist stylist(ResultSet rs, int n) throws SQLException {
        return new Stylist(rs.getObject("org_id", UUID.class), rs.getString("display_name"), rs.getString("location_name"),
                rs.getString("market"), rs.getString("status"), rs.getString("instagram"), rs.getBoolean("demo"));
    }

}
