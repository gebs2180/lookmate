package com.lookmate.app.tracking;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.stylists.Stylists;
import com.lookmate.app.tracking.MatchEvents.Source;
import com.lookmate.app.tracking.MatchEvents.Type;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Recording what happened after a match: the client's views and "Book" clicks, and bookings entered by hand. */
@Service
public class TrackingService {

    /** Why a manual entry was refused; the web app branches on these. */
    enum Code { ALREADY_BOOKED, REBOOK_WITHOUT_BOOKING, OCCURRED_IN_FUTURE, OCCURRED_BEFORE_REQUEST, ALREADY_VOIDED,
        BOOKING_HAS_REBOOKS }

    /** Clock skew allowed on a hand-entered time before it counts as "in the future". */
    private static final Duration FUTURE_SLACK = Duration.ofMinutes(5);

    record Recorded(UUID id, UUID matchId, Type type, Source source, Instant occurredAt, String note) {
    }

    /** Clicks, bookings and rebooks on one match. */
    public record MatchActivity(UUID matchId, long bookingClicks, long bookings, long rebooks) {
    }

    /**
     * What a client did with a request, for the admin's monitor: when they first opened their list (null if never),
     * per match, and on stylists they picked from the directory (no match).
     */
    public record RequestActivity(UUID requestId, Instant firstViewedAt, List<MatchActivity> matches, long directoryClicks,
                                  long directoryBookings) {

        public MatchActivity match(UUID matchId) {
            return matches.stream().filter(m -> m.matchId().equals(matchId)).findFirst()
                    .orElse(new MatchActivity(matchId, 0, 0, 0));
        }
    }

    private final TrackingQueries queries;
    private final MatchEvents events;
    private final TrackingProperties properties;
    private final MatchRequests requests;
    private final Stylists stylists;
    private final Clock clock;

    TrackingService(TrackingQueries queries, MatchEvents events, TrackingProperties properties, MatchRequests requests,
                    Stylists stylists) {
        this.queries = queries;
        this.events = events;
        this.properties = properties;
        this.requests = requests;
        this.stylists = stylists;
        this.clock = Clock.systemUTC();
    }

    /**
     * The tagged booking link for one of the client's matches, recording the click. Empty (a 404 for the caller) when
     * the token, the match, its state or the stylist's link isn't right; the reasons are not told apart.
     */
    @Transactional
    public Optional<URI> clickBook(String token, UUID matchId) {
        Optional<MatchRequests.Request> request = requests.forToken(token);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        Optional<TrackingQueries.BookingTarget> found = queries.bookingTarget(request.get().id(), matchId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TrackingQueries.BookingTarget target = found.get();
        Optional<URI> link = BookingLinks.tagged(target.bookingUrl(), target.market(), target.matchId());
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // One lock per client link covers both checks: the per-match dedupe and the per-link daily cap.
        events.lock(target.requestId());
        if (!events.clickedSince(target.matchId(), now.minus(properties.clickDedupeWindow()))
                && events.clicksOnRequestSince(target.requestId(), startOfDay(now)) < properties.maxClicksPerRequestPerDay()) {
            events.append(new MatchEvents.New(target.matchId(), target.requestId(), target.orgId(), Type.CLICKED_BOOK,
                    Source.CLIENT, null, null, now));
        }
        return link;
    }

    /**
     * The tagged booking link of a LIVE stylist the client picked from the directory, recording a CLICKED_BOOK with no
     * match (attributed to the client's request). Empty (a 404) for a bad token, a stylist who isn't LIVE, or a booking
     * link that isn't a safe https link: nothing is recorded then, and nothing redirects (no open redirect).
     */
    @Transactional
    public Optional<URI> clickDirectory(String token, UUID orgId) {
        Optional<MatchRequests.Request> request = requests.forToken(token);
        if (request.isEmpty() || request.get().status() == MatchRequests.Status.CLOSED) {
            return Optional.empty();
        }
        Optional<Stylists.LiveProfile> stylist = stylists.liveProfile(orgId);
        if (stylist.isEmpty()) {
            return Optional.empty();
        }
        Optional<URI> link = BookingLinks.tagged(stylist.get().bookingUrl(), stylist.get().market(), BookingLinks.DIRECTORY);
        if (link.isEmpty()) {
            return Optional.empty();
        }
        UUID requestId = request.get().id();
        Instant now = clock.instant();
        events.lock(requestId);
        if (!events.directoryClickedSince(requestId, orgId, now.minus(properties.clickDedupeWindow()))
                && events.clicksOnRequestSince(requestId, startOfDay(now)) < properties.maxClicksPerRequestPerDay()) {
            events.append(new MatchEvents.New(null, requestId, orgId, Type.CLICKED_BOOK, Source.CLIENT, null, null, now));
        }
        return link;
    }

    /**
     * Records that the client opened their published match list, once per request per day, and tells intake, which
     * then doesn't send the "matches ready" email if it hasn't gone yet. False if the token isn't valid or the list
     * isn't published.
     */
    @Transactional
    public boolean viewed(String token) {
        Optional<MatchRequests.Request> request = requests.forToken(token)
                .filter(r -> r.status() == MatchRequests.Status.PUBLISHED);
        if (request.isEmpty()) {
            return false;
        }
        UUID requestId = request.get().id();
        Instant now = clock.instant();
        Instant startOfDay = startOfDay(now);
        events.lock(requestId);
        if (!events.viewedSince(requestId, startOfDay)) {
            events.append(new MatchEvents.New(null, requestId, null, Type.VIEWED, Source.CLIENT, null, null, now));
        }
        requests.clientViewedMatches(requestId);
        return true;
    }

    /** Views, clicks and bookings for each of these requests (the admin's monitor). Voided events count nowhere. */
    @Transactional(readOnly = true)
    public Map<UUID, RequestActivity> activity(Collection<UUID> requestIds) {
        Map<UUID, Instant> viewed = new HashMap<>();
        Map<UUID, Map<UUID, long[]>> perMatch = new HashMap<>();
        Map<UUID, long[]> directory = new HashMap<>();
        for (TrackingQueries.EventCount c : queries.eventCounts(requestIds)) {
            if (c.type().equals("VIEWED")) {
                viewed.merge(c.requestId(), c.first(), (a, b) -> a.isBefore(b) ? a : b);
                continue;
            }
            int slot = switch (c.type()) {
                case "CLICKED_BOOK" -> 0;
                case "BOOKED" -> 1;
                default -> 2;
            };
            long[] counts = c.matchId() == null
                    ? directory.computeIfAbsent(c.requestId(), k -> new long[3])
                    : perMatch.computeIfAbsent(c.requestId(), k -> new HashMap<>()).computeIfAbsent(c.matchId(), k -> new long[3]);
            counts[slot] += c.n();
        }
        Map<UUID, RequestActivity> out = new HashMap<>();
        for (UUID id : requestIds) {
            List<MatchActivity> matches = new ArrayList<>();
            perMatch.getOrDefault(id, Map.of()).forEach((matchId, n) -> matches.add(new MatchActivity(matchId, n[0], n[1], n[2])));
            long[] d = directory.getOrDefault(id, new long[3]);
            out.put(id, new RequestActivity(id, viewed.get(id), matches, d[0], d[1]));
        }
        return out;
    }

    /**
     * A BOOKED or REBOOKED with a stylist the client picked from the directory (no match), entered by the admin or by
     * that stylist (the caller checks the stylist's route: the client must have tapped Book on them). The request
     * needn't be published: the directory is open to any client with a link.
     */
    @Transactional
    Recorded recordDirectoryBooking(UUID requestId, UUID orgId, Type type, Source source, UUID recordedBy, String note,
                                    Instant occurredAt) {
        if (type != Type.BOOKED && type != Type.REBOOKED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only BOOKED and REBOOKED can be entered by hand");
        }
        TrackingQueries.EntryTarget target = queries.directoryEntryTarget(requestId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request or stylist not found"));
        Instant when = checkedTime(occurredAt, target);
        events.lock(requestId);
        if (type == Type.BOOKED && events.directoryExists(requestId, orgId, Type.BOOKED)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.ALREADY_BOOKED,
                    "A booking with this stylist is already recorded for this request. Record a later visit as a rebook.");
        }
        if (type == Type.REBOOKED && !queries.bookedBefore(target.clientId(), orgId, when)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.REBOOK_WITHOUT_BOOKING,
                    "There's no earlier booking with this stylist for this client. Record a booking first.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        UUID id = events.append(new MatchEvents.New(null, requestId, orgId, type, source, recordedBy, cleanNote, when));
        return new Recorded(id, null, type, source, when, cleanNote);
    }

    /** Whether the client of this request tapped Book on this stylist from the directory (a stylist may record then). */
    @Transactional(readOnly = true)
    boolean directoryPicked(UUID requestId, UUID orgId) {
        return events.directoryExists(requestId, orgId, Type.CLICKED_BOOK);
    }

    private Instant checkedTime(Instant occurredAt, TrackingQueries.EntryTarget target) {
        Instant now = clock.instant();
        Instant when = occurredAt == null ? now : occurredAt;
        if (when.isAfter(now.plus(FUTURE_SLACK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.OCCURRED_IN_FUTURE, "The booking date can't be in the future.");
        }
        if (when.isBefore(target.requestCreatedAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.OCCURRED_BEFORE_REQUEST,
                    "The booking date is before the client asked for a match.");
        }
        return when;
    }

    /**
     * A BOOKED or REBOOKED entered by the admin ({@code orgId} null) or by the stylist for their own org. A match of
     * another org, or one never published to the client, is a 404.
     */
    @Transactional
    Recorded recordBooking(UUID matchId, UUID orgId, Type type, Source source, UUID recordedBy, String note,
                           Instant occurredAt) {
        if (type != Type.BOOKED && type != Type.REBOOKED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only BOOKED and REBOOKED can be entered by hand");
        }
        TrackingQueries.EntryTarget target = queries.entryTarget(matchId)
                .filter(t -> orgId == null || t.orgId().equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found"));

        Instant when = checkedTime(occurredAt, target);

        events.lock(target.matchId());
        if (type == Type.BOOKED && events.exists(target.matchId(), Type.BOOKED)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.ALREADY_BOOKED,
                    "A booking is already recorded for this match. Record a later visit as a rebook.");
        }
        if (type == Type.REBOOKED && !queries.bookedBefore(target.clientId(), target.orgId(), when)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.REBOOK_WITHOUT_BOOKING,
                    "There's no earlier booking with this stylist for this client. Record a booking first.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        UUID id = events.append(new MatchEvents.New(target.matchId(), target.requestId(), target.orgId(), type, source,
                recordedBy, cleanNote, when));
        return new Recorded(id, target.matchId(), type, source, when, cleanNote);
    }

    /**
     * The events on a match, newest first, voided ones marked. For a stylist ({@code orgId} set) the match must be
     * theirs, else 404.
     */
    @Transactional(readOnly = true)
    List<MatchEvents.Event> events(UUID matchId, UUID orgId) {
        queries.entryTarget(matchId)
                .filter(t -> orgId == null || t.orgId().equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found"));
        return events.forMatch(matchId);
    }

    /**
     * Undoes a wrongly recorded event by appending a VOIDED row that points at it, with the reason. The admin
     * ({@code orgId} null) can void any event; a stylist only the bookings and rebooks on their own matches. A booking
     * that a later rebook depends on can't be voided until that rebook is.
     */
    @Transactional
    MatchEvents.Event voidEvent(UUID eventId, UUID orgId, Source source, UUID recordedBy, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why the event is being voided");
        }
        MatchEvents.Event target = events.find(eventId)
                .filter(e -> e.type() != Type.VOIDED)
                .filter(e -> orgId == null
                        || (orgId.equals(e.orgId()) && (e.type() == Type.BOOKED || e.type() == Type.REBOOKED)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        events.lock(target.matchId() != null ? target.matchId() : target.matchRequestId());
        MatchEvents.Event current = events.find(eventId).orElseThrow();
        if (current.voided()) {
            throw new ApiException(HttpStatus.CONFLICT, Code.ALREADY_VOIDED, "This event was already voided.");
        }
        if (target.type() == Type.BOOKED) {
            UUID clientId = queries.clientOf(target.matchRequestId()).orElseThrow();
            if (queries.rebooksDependOn(clientId, target.orgId(), target.id())) {
                throw new ApiException(HttpStatus.CONFLICT, Code.BOOKING_HAS_REBOOKS,
                        "A rebook with this stylist counts on this booking. Void the rebook first.");
            }
        }
        events.append(new MatchEvents.New(target.matchId(), target.matchRequestId(), target.orgId(), Type.VOIDED, source,
                recordedBy, reason.strip(), clock.instant(), target.id()));
        return events.find(eventId).orElseThrow();
    }

    private Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, properties.timeZone()).atStartOfDay(properties.timeZone()).toInstant();
    }
}
