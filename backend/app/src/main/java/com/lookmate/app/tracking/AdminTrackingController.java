package com.lookmate.app.tracking;

import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.tracking.MatchEvents.Source;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path, SecurityConfig): the pilot metrics, and bookings entered by hand. */
@RestController
class AdminTrackingController {

    /** BOOKED or REBOOKED; {@code occurredAt} defaults to now. */
    record RecordBooking(@NotNull MatchEvents.Type type, @Size(max = 2000) String note, Instant occurredAt) {
    }

    record VoidEvent(@NotBlank @Size(max = 2000) String reason) {
    }

    private final MetricsService metrics;
    private final TrackingService tracking;
    private final TrackingQueries queries;
    private final CurrentUser currentUser;

    AdminTrackingController(MetricsService metrics, TrackingService tracking, TrackingQueries queries, CurrentUser currentUser) {
        this.metrics = metrics;
        this.tracking = tracking;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    /** The dashboard. Dates are inclusive, in the tracking time zone; the default is the last twelve weeks. */
    @GetMapping("/api/admin/metrics")
    MetricsService.Metrics metrics(@RequestParam(required = false) String market,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return metrics.metrics(market, from, to);
    }

    /** Recently published matches to record a booking against, newest first; {@code q} searches stylist and client. */
    @GetMapping("/api/admin/tracking/matches")
    List<TrackingQueries.RecentMatch> matches(@RequestParam(required = false) String market,
                                              @RequestParam(required = false) @Size(max = 200) String q) {
        return queries.recentMatches(market == null || market.isBlank() ? null : market, null, q, 50);
    }

    @PostMapping("/api/admin/matches/{matchId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    TrackingService.Recorded record(@PathVariable UUID matchId, @Valid @RequestBody RecordBooking request) {
        return tracking.recordBooking(matchId, null, request.type(), Source.ADMIN, currentUser.id(), request.note(),
                request.occurredAt());
    }

    @GetMapping("/api/admin/matches/{matchId}/events")
    List<MatchEvents.Event> events(@PathVariable UUID matchId) {
        return tracking.events(matchId, null);
    }

    /** Undoes a wrongly recorded event; the original stays, marked voided, with the reason. */
    @PostMapping("/api/admin/events/{eventId}/void")
    MatchEvents.Event voidEvent(@PathVariable UUID eventId, @Valid @RequestBody VoidEvent request) {
        return tracking.voidEvent(eventId, null, Source.ADMIN, currentUser.id(), request.reason());
    }
}
