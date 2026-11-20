package com.lookmate.app.tracking;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.tracking.MatchEvents.Source;
import com.lookmate.app.tracking.MatchEvents.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
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
    enum Code { ALREADY_BOOKED, REBOOK_WITHOUT_BOOKING, OCCURRED_IN_FUTURE, OCCURRED_BEFORE_REQUEST }

    /** Clock skew allowed on a hand-entered time before it counts as "in the future". */
    private static final Duration FUTURE_SLACK = Duration.ofMinutes(5);

    record Recorded(UUID id, UUID matchId, Type type, Source source, Instant occurredAt, String note) {
    }

    private final TrackingQueries queries;
    private final MatchEvents events;
    private final TrackingProperties properties;
    private final Clock clock;

    TrackingService(TrackingQueries queries, MatchEvents events, TrackingProperties properties) {
        this.queries = queries;
        this.events = events;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * The tagged booking link for one of the client's matches, recording the click. Empty (a 404 for the caller) when
     * the token, the match, its state or the stylist's link isn't right; the reasons are not told apart.
     */
    @Transactional
    public Optional<URI> clickBook(String token, UUID matchId) {
        if (!plausibleToken(token)) {
            return Optional.empty();
        }
        Optional<TrackingQueries.BookingTarget> found = queries.bookingTarget(hash(token), matchId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        TrackingQueries.BookingTarget target = found.get();
        Optional<URI> link = BookingLinks.tagged(target.bookingUrl(), target.market(), target.matchId());
        if (link.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        events.lock(target.matchId());
        if (!events.clickedSince(target.matchId(), now.minus(properties.clickDedupeWindow()))) {
            events.append(new MatchEvents.New(target.matchId(), target.requestId(), target.orgId(), Type.CLICKED_BOOK,
                    Source.CLIENT, null, null, now));
        }
        return link;
    }

    /** Records that the client opened their match list; once per request per day. False if the token isn't valid. */
    @Transactional
    public boolean viewed(String token) {
        if (!plausibleToken(token)) {
            return false;
        }
        Optional<UUID> request = queries.publishedRequestId(hash(token));
        if (request.isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        Instant startOfDay = LocalDate.ofInstant(now, properties.timeZone()).atStartOfDay(properties.timeZone()).toInstant();
        events.lock(request.get());
        if (!events.viewedSince(request.get(), startOfDay)) {
            events.append(new MatchEvents.New(null, request.get(), null, Type.VIEWED, Source.CLIENT, null, null, now));
        }
        return true;
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

        Instant now = clock.instant();
        Instant when = occurredAt == null ? now : occurredAt;
        if (when.isAfter(now.plus(FUTURE_SLACK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.OCCURRED_IN_FUTURE, "The booking date can't be in the future.");
        }
        if (when.isBefore(target.requestCreatedAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.OCCURRED_BEFORE_REQUEST,
                    "The booking date is before the client asked for a match.");
        }

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

    /** A link token is a short random string; something very long can't match and isn't worth hashing. */
    private static boolean plausibleToken(String token) {
        return token != null && !token.isBlank() && token.length() <= 512;
    }

    /** Lower-case hex SHA-256 of the UTF-8 token, as stored in {@code match_request.access_token_hash}. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
