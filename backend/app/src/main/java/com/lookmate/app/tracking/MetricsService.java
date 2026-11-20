package com.lookmate.app.tracking;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The pilot metrics (brief, "Success metrics") for the admin dashboard, and a stylist's own numbers. */
@Service
class MetricsService {

    /** Twelve weeks unless the admin picks dates. */
    static final int DEFAULT_DAYS = 84;
    static final int MAX_DAYS = 731;

    record Totals(long stylistsLive, long stylistsActive, long requestsPublished, long clientsMatched, long bookingClicks,
                  long clientsClicked, long bookings, long clientsBooked, long rebooks, long clientsRebooked,
                  long rebookCohort, long rebookCohortRebooked, Double rebookRate,
                  TrackingQueries.SourceSplit clicksBySource, TrackingQueries.SourceSplit bookingsBySource) {
    }

    record Metrics(String market, LocalDate from, LocalDate to, String timeZone, List<String> markets, Totals totals,
                   List<TrackingQueries.WeekRow> weeks, List<TrackingQueries.StylistRow> stylists,
                   List<TrackingQueries.HairTypeRow> hairTypes) {
    }

    record StylistStats(TrackingQueries.OrgCounts last30Days, TrackingQueries.OrgCounts allTime) {
    }

    private final TrackingQueries queries;
    private final ZoneId zone;

    MetricsService(TrackingQueries queries, TrackingProperties properties) {
        this.queries = queries;
        this.zone = properties.timeZone();
    }

    /** {@code from} and {@code to} are inclusive local dates in the tracking time zone; null picks the default. */
    @Transactional(readOnly = true)
    Metrics metrics(String market, LocalDate from, LocalDate to) {
        String m = market == null || market.isBlank() ? null : market.strip();
        LocalDate end = to != null ? to : LocalDate.now(zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a range of two years or less");
        }
        TrackingQueries.Range range = range(start, end);

        TrackingQueries.Totals t = queries.totals(m, range);
        Double rate = t.rebookCohort() == 0 ? null : (double) t.rebookCohortRebooked() / t.rebookCohort();
        Totals totals = new Totals(t.stylistsLive(), t.stylistsActive(), t.requestsPublished(), t.clientsMatched(),
                t.bookingClicks(), t.clientsClicked(), t.bookings(), t.clientsBooked(), t.rebooks(), t.clientsRebooked(),
                t.rebookCohort(), t.rebookCohortRebooked(), rate, t.clicksBySource(), t.bookingsBySource());

        return new Metrics(m, start, end, zone.getId(), queries.markets(), totals, weeks(start, end, queries.weekly(m, range, zone.getId())),
                queries.perStylist(m, range), queries.byHairType(m, range));
    }

    @Transactional(readOnly = true)
    StylistStats stylistStats(UUID orgId) {
        LocalDate today = LocalDate.now(zone);
        return new StylistStats(queries.orgCounts(orgId, range(today.minusDays(29), today)),
                queries.orgCounts(orgId, new TrackingQueries.Range(java.time.Instant.EPOCH, range(today, today).end())));
    }

    private TrackingQueries.Range range(LocalDate from, LocalDate to) {
        return new TrackingQueries.Range(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Every week the range touches, Monday first, zeros where nothing happened, so the line has no gaps. */
    static List<TrackingQueries.WeekRow> weeks(LocalDate from, LocalDate to, List<TrackingQueries.WeekRow> rows) {
        Map<LocalDate, TrackingQueries.WeekRow> byWeek = rows.stream()
                .collect(Collectors.toMap(TrackingQueries.WeekRow::weekStart, Function.identity()));
        List<TrackingQueries.WeekRow> out = new ArrayList<>();
        for (LocalDate week = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !week.isAfter(to);
                week = week.plusWeeks(1)) {
            out.add(byWeek.getOrDefault(week, new TrackingQueries.WeekRow(week, 0, 0, 0, 0, 0)));
        }
        return out;
    }
}
