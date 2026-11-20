package com.lookmate.app.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every read this module makes of other modules' tables ({@code stylist_profile}, {@code portfolio_photo},
 * {@code client}, {@code match_request}, {@code match}), in one place. Read-only: nothing here writes them.
 *
 * <p>This is a sanctioned exception to "modules talk through public services" (docs/architecture.md): stylists, intake
 * and matching were built in parallel with tracking, and the metrics are aggregate SQL over the whole Phase 1 schema
 * that no single module's service would answer efficiently. When those modules expose what the redirect and the
 * manual entry need, those lookups can move behind their services; the metrics queries can stay as reporting SQL.
 *
 * <p>Time ranges are half-open {@code [start, end)} instants; {@code market} null means every market. Indexes that
 * serve these queries are listed in docs/tracking.md.
 */
@Repository
class TrackingQueries {

    /** A match the client may book through: published, ranked, not hidden, stylist LIVE. */
    record BookingTarget(UUID matchId, UUID orgId, UUID requestId, String market, String bookingUrl) {
    }

    /** A match a booking can be recorded against: its request has been published to the client. */
    record EntryTarget(UUID matchId, UUID orgId, UUID requestId, UUID clientId, Instant requestCreatedAt) {
    }

    record Range(Instant start, Instant end) {
    }

    record Totals(long stylistsLive, long stylistsActive, long requestsPublished, long clientsMatched, long bookingClicks,
                  long clientsClicked, long bookings, long clientsBooked, long rebooks, long clientsRebooked,
                  long rebookCohort, long rebookCohortRebooked, SourceSplit clicksBySource, SourceSplit bookingsBySource) {
    }

    /**
     * Book clicks or bookings by where the client picked the stylist: from their recommendations (the event is on a
     * match), or from the directory (no match), split by whether that stylist was also on the client's published list.
     */
    record SourceSplit(long recommended, long directory, long directoryAlsoRecommended) {
    }

    record WeekRow(LocalDate weekStart, long requestsPublished, long clientsMatched, long bookingClicks, long bookings,
                   long rebooks) {
    }

    record StylistRow(UUID orgId, String displayName, String status, long readyPhotos, long matchesShown,
                      long bookingClicks, long bookings, long rebooks, long clientsBooked, long clientsRebooked) {
    }

    record HairTypeRow(String hairType, long clientsMatched, long clientsClicked, long clientsBooked) {
    }

    record OrgCounts(long matchesShown, long bookingClicks, long bookings, long rebooks) {
    }

    record RecentMatch(UUID matchId, UUID requestId, UUID orgId, String stylistName, String clientFirstName,
                       String clientEmail, String market, String service, Instant publishedAt, Integer finalRank,
                       long bookingClicks, long bookings, long rebooks) {
    }

    private final JdbcClient jdbc;

    TrackingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Client link ---------------------------------------------------------------------------------------------

    /** The match, if the client of this request may book through it: published, ranked, not hidden, stylist LIVE. */
    Optional<BookingTarget> bookingTarget(UUID requestId, UUID matchId) {
        return jdbc.sql("""
                        select m.id, m.org_id, r.id as request_id, r.market, p.booking_url
                        from match m
                        join match_request r on r.id = m.match_request_id
                        join stylist_profile p on p.org_id = m.org_id
                        where m.id = :match and r.id = :request
                          and r.status = 'PUBLISHED' and m.final_rank is not null and not m.hidden
                          and p.status = 'LIVE'
                        """)
                .param("match", matchId)
                .param("request", requestId)
                .query((rs, n) -> new BookingTarget(uuid(rs, "id"), uuid(rs, "org_id"), uuid(rs, "request_id"),
                        rs.getString("market"), rs.getString("booking_url")))
                .optional();
    }

    // --- Manual entry ----------------------------------------------------------------------------------------------

    Optional<EntryTarget> entryTarget(UUID matchId) {
        return jdbc.sql("""
                        select m.id, m.org_id, r.id as request_id, r.client_id, r.created_at
                        from match m join match_request r on r.id = m.match_request_id
                        where m.id = :match and r.published_at is not null
                        """)
                .param("match", matchId)
                .query((rs, n) -> new EntryTarget(uuid(rs, "id"), uuid(rs, "org_id"), uuid(rs, "request_id"),
                        uuid(rs, "client_id"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /**
     * A request a directory booking (no match) can be recorded against, with the stylist: any request, published or
     * not, since the directory is open to every client with a link.
     */
    Optional<EntryTarget> directoryEntryTarget(UUID requestId, UUID orgId) {
        return jdbc.sql("""
                        select r.id as request_id, r.client_id, r.created_at
                        from match_request r
                        where r.id = :request and exists (select 1 from stylist_profile p where p.org_id = :org)
                        """)
                .param("request", requestId)
                .param("org", orgId)
                .query((rs, n) -> new EntryTarget(null, orgId, uuid(rs, "request_id"), uuid(rs, "client_id"),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /** The client of a request (for the rebook checks on a directory booking, which has no match). */
    Optional<UUID> clientOf(UUID requestId) {
        return jdbc.sql("select client_id from match_request where id = :request").param("request", requestId)
                .query(UUID.class).optional();
    }

    /** A stylist one client picked from the directory: their clicks, and the directory booking if one is recorded. */
    record DirectoryClick(UUID requestId, UUID orgId, String stylistName, String clientFirstName, String clientEmail,
                          String market, String service, Instant lastClickedAt, long clicks, boolean recommended,
                          UUID bookedEventId) {
    }

    /**
     * Stylists clients picked from the directory (a Book click with no match), newest first, for the admin's "record a
     * booking" page; {@code recommended} says whether the same stylist was also on that client's published list.
     */
    List<DirectoryClick> recentDirectoryClicks(String market, String search, int limit) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase().replace("%", "") + "%";
        return jdbc.sql("""
                        select e.match_request_id, e.org_id, p.display_name, c.first_name, c.email, r.market, r.service,
                               max(e.occurred_at) as last_clicked, count(*) as clicks,
                               exists (select 1 from match m where m.match_request_id = e.match_request_id and m.org_id = e.org_id
                                         and m.final_rank is not null and not m.hidden and r.published_at is not null) as recommended,
                               (select b.id from match_event_live b
                                where b.match_request_id = e.match_request_id and b.org_id = e.org_id and b.match_id is null
                                  and b.type = 'BOOKED' limit 1) as booked_event
                        from match_event_live e
                        join match_request r on r.id = e.match_request_id
                        join client c on c.id = r.client_id
                        left join stylist_profile p on p.org_id = e.org_id
                        where e.type = 'CLICKED_BOOK' and e.match_id is null
                          and (cast(:market as text) is null or r.market = :market)
                          and (cast(:like as text) is null
                               or lower(coalesce(p.display_name, '')) like :like
                               or lower(coalesce(c.first_name, '')) like :like
                               or lower(coalesce(c.email, '')) like :like)
                        group by e.match_request_id, e.org_id, p.display_name, c.first_name, c.email, r.market, r.service,
                                 r.published_at
                        order by last_clicked desc
                        limit :limit
                        """)
                .param("market", market)
                .param("like", like)
                .param("limit", limit)
                .query((rs, n) -> new DirectoryClick(uuid(rs, "match_request_id"), uuid(rs, "org_id"), rs.getString("display_name"),
                        rs.getString("first_name"), rs.getString("email"), rs.getString("market"), rs.getString("service"),
                        rs.getTimestamp("last_clicked").toInstant(), rs.getLong("clicks"), rs.getBoolean("recommended"),
                        uuid(rs, "booked_event")))
                .list();
    }

    // --- Activity, for the admin's match monitor (TrackingService.activity) ------------------------------------------

    record EventCount(UUID requestId, UUID matchId, String type, long n, Instant first) {
    }

    /** Live CLICKED_BOOK, BOOKED, REBOOKED and VIEWED counts per request and match (null for the list or the directory). */
    List<EventCount> eventCounts(java.util.Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select match_request_id, match_id, type, count(*) as n, min(occurred_at) as first
                        from match_event_live
                        where match_request_id in (:requests) and type in ('VIEWED', 'CLICKED_BOOK', 'BOOKED', 'REBOOKED')
                        group by match_request_id, match_id, type
                        """)
                .param("requests", requestIds)
                .query((rs, n) -> new EventCount(uuid(rs, "match_request_id"), uuid(rs, "match_id"), rs.getString("type"),
                        rs.getLong("n"), rs.getTimestamp("first").toInstant()))
                .list();
    }

    /** Whether the client had a BOOKED with this stylist (through any of their matches) before {@code before}. */
    boolean bookedBefore(UUID clientId, UUID orgId, Instant before) {
        return jdbc.sql("""
                        select exists (
                          select 1 from match_event_live e join match_request r on r.id = e.match_request_id
                          where r.client_id = :client and e.org_id = :org and e.type = 'BOOKED'
                            and e.occurred_at < cast(:before as timestamptz))
                        """)
                .param("client", clientId)
                .param("org", orgId)
                .param("before", utc(before))
                .query(Boolean.class).single();
    }

    /**
     * Whether a live REBOOKED of this client with this stylist would be left with no earlier live BOOKED if
     * {@code bookedEventId} were voided.
     */
    boolean rebooksDependOn(UUID clientId, UUID orgId, UUID bookedEventId) {
        return jdbc.sql("""
                        select exists (
                          select 1 from match_event_live x join match_request rx on rx.id = x.match_request_id
                          where x.type = 'REBOOKED' and x.org_id = :org and rx.client_id = :client
                            and not exists (
                              select 1 from match_event_live b join match_request rb on rb.id = b.match_request_id
                              where b.type = 'BOOKED' and b.org_id = :org and rb.client_id = :client
                                and b.id <> :booked and b.occurred_at < x.occurred_at))
                        """)
                .param("client", clientId)
                .param("org", orgId)
                .param("booked", bookedEventId)
                .query(Boolean.class).single();
    }

    /** Recently published matches, newest first, for the admin's "record a booking" picker. */
    List<RecentMatch> recentMatches(String market, UUID orgId, String search, int limit) {
        String like = search == null || search.isBlank() ? null : "%" + search.strip().toLowerCase().replace("%", "") + "%";
        return jdbc.sql("""
                        select m.id, r.id as request_id, m.org_id, p.display_name, c.first_name, c.email, r.market,
                               r.service, r.published_at, m.final_rank,
                               count(e.id) filter (where e.type = 'CLICKED_BOOK') as clicks,
                               count(e.id) filter (where e.type = 'BOOKED') as bookings,
                               count(e.id) filter (where e.type = 'REBOOKED') as rebooks
                        from match m
                        join match_request r on r.id = m.match_request_id
                        join client c on c.id = r.client_id
                        left join stylist_profile p on p.org_id = m.org_id
                        left join match_event_live e on e.match_id = m.id
                        where r.published_at is not null and m.final_rank is not null and not m.hidden
                          and (cast(:market as text) is null or r.market = :market)
                          and (cast(:org as uuid) is null or m.org_id = :org)
                          and (cast(:like as text) is null
                               or lower(coalesce(p.display_name, '')) like :like
                               or lower(coalesce(c.first_name, '')) like :like
                               or lower(coalesce(c.email, '')) like :like)
                        group by m.id, r.id, p.display_name, c.first_name, c.email
                        order by r.published_at desc, m.final_rank
                        limit :limit
                        """)
                .param("market", market)
                .param("like", like)
                .param("org", orgId)
                .param("limit", limit)
                .query((rs, n) -> new RecentMatch(uuid(rs, "id"), uuid(rs, "request_id"), uuid(rs, "org_id"),
                        rs.getString("display_name"), rs.getString("first_name"), rs.getString("email"),
                        rs.getString("market"), rs.getString("service"), rs.getTimestamp("published_at").toInstant(),
                        (Integer) rs.getObject("final_rank"), rs.getLong("clicks"), rs.getLong("bookings"),
                        rs.getLong("rebooks")))
                .list();
    }

    // --- Metrics -------------------------------------------------------------------------------------------------

    /** Every market that has a stylist or a request, for the dashboard's filter. */
    List<String> markets() {
        return jdbc.sql("select market from stylist_profile union select market from match_request order by 1")
                .query(String.class).list();
    }

    Totals totals(String market, Range range) {
        record Stylists(long live, long active) {
        }
        Stylists stylists = jdbc.sql("""
                        with p as (
                          select p.status,
                                 exists (select 1 from portfolio_photo ph
                                         where ph.org_id = p.org_id and ph.status = 'READY') as has_ready_photo
                          from stylist_profile p
                          where cast(:market as text) is null or p.market = :market)
                        select count(*) filter (where status = 'LIVE') as live,
                               count(*) filter (where status = 'LIVE' and has_ready_photo) as active
                        from p
                        """)
                .param("market", market)
                .query((rs, n) -> new Stylists(rs.getLong("live"), rs.getLong("active")))
                .single();

        long[] requests = jdbc.sql("""
                        select count(*) as requests, count(distinct client_id) as clients
                        from match_request
                        where published_at >= cast(:start as timestamptz) and published_at < cast(:end as timestamptz)
                          and (cast(:market as text) is null or market = :market)
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> new long[] {rs.getLong("requests"), rs.getLong("clients")})
                .single();

        long[] events = jdbc.sql("""
                        select count(*) filter (where e.type = 'CLICKED_BOOK') as clicks,
                               count(distinct r.client_id) filter (where e.type = 'CLICKED_BOOK') as clients_clicked,
                               count(*) filter (where e.type = 'BOOKED') as bookings,
                               count(distinct r.client_id) filter (where e.type = 'BOOKED') as clients_booked,
                               count(*) filter (where e.type = 'REBOOKED') as rebooks,
                               count(distinct r.client_id) filter (where e.type = 'REBOOKED') as clients_rebooked
                        from match_event_live e join match_request r on r.id = e.match_request_id
                        where e.occurred_at >= cast(:start as timestamptz) and e.occurred_at < cast(:end as timestamptz)
                          and (cast(:market as text) is null or r.market = :market)
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> new long[] {rs.getLong("clicks"), rs.getLong("clients_clicked"), rs.getLong("bookings"),
                        rs.getLong("clients_booked"), rs.getLong("rebooks"), rs.getLong("clients_rebooked")})
                .single();

        // Rebook rate, as a cohort: clients whose (first) BOOKED with a stylist falls in the range, and of those, the
        // ones with a REBOOKED with that same stylist any time after it, even after the range ends.
        long[] rebook = jdbc.sql("""
                        with booked as (
                          select r.client_id, e.org_id, min(e.occurred_at) as first_booked
                          from match_event_live e join match_request r on r.id = e.match_request_id
                          where e.type = 'BOOKED'
                            and e.occurred_at >= cast(:start as timestamptz) and e.occurred_at < cast(:end as timestamptz)
                            and (cast(:market as text) is null or r.market = :market)
                          group by r.client_id, e.org_id),
                        flagged as (
                          select b.client_id,
                                 exists (select 1 from match_event_live x join match_request rx on rx.id = x.match_request_id
                                         where x.type = 'REBOOKED' and x.org_id = b.org_id and rx.client_id = b.client_id
                                           and x.occurred_at > b.first_booked) as rebooked
                          from booked b)
                        select count(distinct client_id) as cohort,
                               count(distinct client_id) filter (where rebooked) as rebooked
                        from flagged
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> new long[] {rs.getLong("cohort"), rs.getLong("rebooked")})
                .single();

        java.util.Map<String, SourceSplit> split = new java.util.HashMap<>();
        jdbc.sql("""
                        select e.type,
                               count(*) filter (where e.match_id is not null) as recommended,
                               count(*) filter (where e.match_id is null) as directory,
                               count(*) filter (where e.match_id is null and exists (
                                 select 1 from match m where m.match_request_id = e.match_request_id and m.org_id = e.org_id
                                   and m.final_rank is not null and not m.hidden and r.published_at is not null)) as also_recommended
                        from match_event_live e join match_request r on r.id = e.match_request_id
                        where e.type in ('CLICKED_BOOK', 'BOOKED')
                          and e.occurred_at >= cast(:start as timestamptz) and e.occurred_at < cast(:end as timestamptz)
                          and (cast(:market as text) is null or r.market = :market)
                        group by e.type
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> split.put(rs.getString("type"), new SourceSplit(rs.getLong("recommended"),
                        rs.getLong("directory"), rs.getLong("also_recommended"))))
                .list();
        SourceSplit none = new SourceSplit(0, 0, 0);
        return new Totals(stylists.live(), stylists.active(), requests[0], requests[1], events[0], events[1], events[2],
                events[3], events[4], events[5], rebook[0], rebook[1], split.getOrDefault("CLICKED_BOOK", none),
                split.getOrDefault("BOOKED", none));
    }

    /** Counts per week (Monday start, in {@code zone}). Weeks with nothing are absent; the caller fills them. */
    List<WeekRow> weekly(String market, Range range, String zone) {
        return jdbc.sql("""
                        with req as (
                          select date_trunc('week', published_at at time zone :zone)::date as week,
                                 count(*) as requests, count(distinct client_id) as clients
                          from match_request
                          where published_at >= cast(:start as timestamptz) and published_at < cast(:end as timestamptz)
                            and (cast(:market as text) is null or market = :market)
                          group by 1),
                        ev as (
                          select date_trunc('week', e.occurred_at at time zone :zone)::date as week,
                                 count(*) filter (where e.type = 'CLICKED_BOOK') as clicks,
                                 count(*) filter (where e.type = 'BOOKED') as bookings,
                                 count(*) filter (where e.type = 'REBOOKED') as rebooks
                          from match_event_live e join match_request r on r.id = e.match_request_id
                          where e.occurred_at >= cast(:start as timestamptz) and e.occurred_at < cast(:end as timestamptz)
                            and (cast(:market as text) is null or r.market = :market)
                          group by 1)
                        select coalesce(req.week, ev.week) as week,
                               coalesce(req.requests, 0) as requests, coalesce(req.clients, 0) as clients,
                               coalesce(ev.clicks, 0) as clicks, coalesce(ev.bookings, 0) as bookings,
                               coalesce(ev.rebooks, 0) as rebooks
                        from req full join ev on ev.week = req.week
                        order by 1
                        """)
                .params(rangeParams(market, range))
                .param("zone", zone)
                .query((rs, n) -> new WeekRow(rs.getObject("week", LocalDate.class), rs.getLong("requests"),
                        rs.getLong("clients"), rs.getLong("clicks"), rs.getLong("bookings"), rs.getLong("rebooks")))
                .list();
    }

    /** Every LIVE stylist in the market, plus any other stylist with activity in the range. */
    List<StylistRow> perStylist(String market, Range range) {
        return jdbc.sql("""
                        with ev as (
                          select e.org_id,
                                 count(*) filter (where e.type = 'CLICKED_BOOK') as clicks,
                                 count(*) filter (where e.type = 'BOOKED') as bookings,
                                 count(*) filter (where e.type = 'REBOOKED') as rebooks,
                                 count(distinct r.client_id) filter (where e.type = 'BOOKED') as clients_booked,
                                 count(distinct r.client_id) filter (where e.type = 'REBOOKED') as clients_rebooked
                          from match_event_live e join match_request r on r.id = e.match_request_id
                          where e.org_id is not null
                            and e.occurred_at >= cast(:start as timestamptz) and e.occurred_at < cast(:end as timestamptz)
                            and (cast(:market as text) is null or r.market = :market)
                          group by e.org_id),
                        shown as (
                          select m.org_id, count(*) as n
                          from match m join match_request r on r.id = m.match_request_id
                          where m.final_rank is not null and not m.hidden
                            and r.published_at >= cast(:start as timestamptz) and r.published_at < cast(:end as timestamptz)
                            and (cast(:market as text) is null or r.market = :market)
                          group by m.org_id),
                        photos as (
                          select org_id, count(*) as n from portfolio_photo where status = 'READY' group by org_id)
                        select p.org_id, p.display_name, p.status, coalesce(photos.n, 0) as ready_photos,
                               coalesce(shown.n, 0) as shown, coalesce(ev.clicks, 0) as clicks,
                               coalesce(ev.bookings, 0) as bookings, coalesce(ev.rebooks, 0) as rebooks,
                               coalesce(ev.clients_booked, 0) as clients_booked,
                               coalesce(ev.clients_rebooked, 0) as clients_rebooked
                        from stylist_profile p
                        left join ev on ev.org_id = p.org_id
                        left join shown on shown.org_id = p.org_id
                        left join photos on photos.org_id = p.org_id
                        where (p.status = 'LIVE' and (cast(:market as text) is null or p.market = :market))
                           or ev.org_id is not null or shown.org_id is not null
                        order by bookings desc, clicks desc, shown desc, p.display_name
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> new StylistRow(uuid(rs, "org_id"), rs.getString("display_name"), rs.getString("status"),
                        rs.getLong("ready_photos"), rs.getLong("shown"), rs.getLong("clicks"), rs.getLong("bookings"),
                        rs.getLong("rebooks"), rs.getLong("clients_booked"), rs.getLong("clients_rebooked")))
                .list();
    }

    /**
     * Fairness across hair types (CLAUDE.md): of the clients whose request was published in the range, by their hair
     * type, how many went on to click "Book" and to book (at any time since).
     */
    List<HairTypeRow> byHairType(String market, Range range) {
        return jdbc.sql("""
                        select coalesce(c.hair_type, 'UNKNOWN') as hair_type,
                               count(distinct r.client_id) as matched,
                               count(distinct r.client_id) filter (where e.type = 'CLICKED_BOOK') as clicked,
                               count(distinct r.client_id) filter (where e.type = 'BOOKED') as booked
                        from match_request r
                        join client c on c.id = r.client_id
                        left join match_event_live e on e.match_request_id = r.id and e.type in ('CLICKED_BOOK', 'BOOKED')
                        where r.published_at >= cast(:start as timestamptz) and r.published_at < cast(:end as timestamptz)
                          and (cast(:market as text) is null or r.market = :market)
                        group by 1
                        order by array_position(array['STRAIGHT', 'WAVY', 'CURLY', 'COILY', 'UNKNOWN'],
                                                coalesce(c.hair_type, 'UNKNOWN'))
                        """)
                .params(rangeParams(market, range))
                .query((rs, n) -> new HairTypeRow(rs.getString("hair_type"), rs.getLong("matched"), rs.getLong("clicked"),
                        rs.getLong("booked")))
                .list();
    }

    /** One stylist's own numbers: matches shown to clients (by publish time) and events (by when they happened). */
    OrgCounts orgCounts(UUID orgId, Range range) {
        long shown = jdbc.sql("""
                        select count(*) from match m join match_request r on r.id = m.match_request_id
                        where m.org_id = :org and m.final_rank is not null and not m.hidden
                          and r.published_at >= cast(:start as timestamptz) and r.published_at < cast(:end as timestamptz)
                        """)
                .param("org", orgId)
                .param("start", utc(range.start()))
                .param("end", utc(range.end()))
                .query(Long.class).single();
        return jdbc.sql("""
                        select count(*) filter (where type = 'CLICKED_BOOK') as clicks,
                               count(*) filter (where type = 'BOOKED') as bookings,
                               count(*) filter (where type = 'REBOOKED') as rebooks
                        from match_event_live
                        where org_id = :org
                          and occurred_at >= cast(:start as timestamptz) and occurred_at < cast(:end as timestamptz)
                        """)
                .param("org", orgId)
                .param("start", utc(range.start()))
                .param("end", utc(range.end()))
                .query((rs, n) -> new OrgCounts(shown, rs.getLong("clicks"), rs.getLong("bookings"), rs.getLong("rebooks")))
                .single();
    }

    private static java.util.Map<String, Object> rangeParams(String market, Range range) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("market", market);
        params.put("start", utc(range.start()));
        params.put("end", utc(range.end()));
        return params;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
