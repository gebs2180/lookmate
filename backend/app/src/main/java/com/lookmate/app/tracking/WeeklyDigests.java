package com.lookmate.app.tracking;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.identity.Directory;
import com.lookmate.app.identity.OrgType;
import com.lookmate.app.notify.StylistEmails;
import com.lookmate.app.stylists.NewClientStatus;
import com.lookmate.app.stylists.ProfileStatus;
import com.lookmate.app.stylists.Stylists;
import com.lookmate.app.tracking.MatchEvents.Source;
import com.lookmate.app.tracking.MatchEvents.Type;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Monday email to each live stylist (docs/tracking.md, "Weekly digest"): how many clients saw them last week, who
 * tapped Book, and a one-tap "Booked"/"Rebooked" per client. Planned as one stylist_digest row per org per week (the
 * unique key makes it once a week), sent by whichever instance claims the row (FOR UPDATE SKIP LOCKED).
 */
@Service
public class WeeklyDigests {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDigests.class);

    /** Links are kept this long after they expire, so a late tap says "expired" rather than "not found". */
    private static final Duration KEEP_EXPIRED_LINKS = Duration.ofDays(30);

    /** Why a digest link was refused; the web app branches on these. */
    enum Code { LINK_USED, LINK_EXPIRED }

    /** What a digest link is about, for the confirm page. First name and service only. */
    record LinkInfo(UUID orgId, String clientFirstName, String service, LocalDate weekStart, boolean directoryPick,
                    boolean used, boolean expired, Instant expiresAt, UUID eventId) {
    }

    private final DigestStore store;
    private final TrackingQueries queries;
    private final TrackingService tracking;
    private final Directory directory;
    private final Stylists stylists;
    private final StylistEmails emails;
    private final DigestProperties properties;
    private final ZoneId zone;
    private final TransactionTemplate transaction;

    WeeklyDigests(DigestStore store, TrackingQueries queries, TrackingService tracking, Directory directory, Stylists stylists,
                  StylistEmails emails, DigestProperties properties, TrackingProperties trackingProperties,
                  PlatformTransactionManager transactions) {
        this.store = store;
        this.queries = queries;
        this.tracking = tracking;
        this.directory = directory;
        this.stylists = stylists;
        this.emails = emails;
        this.properties = properties;
        this.zone = trackingProperties.timeZone();
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The Monday the week before {@code now}'s week began (tracking time zone): the week a digest sent now reports. */
    LocalDate reportedWeek(Instant now) {
        return LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /**
     * On Mondays from the send hour on, adds last week's digest for every LIVE, non-demo stylist that doesn't have one.
     * Any other time it does nothing. Returns how many were added.
     */
    @Transactional
    public int plan(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        if (local.getDayOfWeek() != DayOfWeek.MONDAY || local.getHour() < properties.sendHour()) {
            return 0;
        }
        LocalDate week = reportedWeek(now);
        int added = 0;
        for (UUID orgId : directory.orgsOfType(OrgType.STYLIST)) {
            boolean live = stylists.profile(orgId).filter(p -> p.status() == ProfileStatus.LIVE && !p.demo()).isPresent();
            if (live && store.plan(orgId, week, now)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Sends PENDING digests, one transaction each, until none is left or {@code max} were handled. A digest that throws
     * is rolled back and counted as a failed attempt. Returns how many were handled.
     */
    public int sendPending(Instant now, int max) {
        int handled = 0;
        while (handled < max) {
            Optional<DigestStore.Pending> sent;
            UUID[] claimed = new UUID[1];
            try {
                sent = transaction.execute(status -> {
                    Optional<DigestStore.Pending> next = store.claimNext();
                    next.ifPresent(d -> {
                        claimed[0] = d.id();
                        store.done(d.id(), send(d, now), now);
                    });
                    return next;
                });
            } catch (RuntimeException e) {
                if (claimed[0] == null) {
                    throw e;
                }
                log.error("Sending stylist digest {} failed", claimed[0], e);
                UUID id = claimed[0];
                transaction.executeWithoutResult(status -> store.failed(id, String.valueOf(e), properties.maxAttempts(), now));
                handled++;
                continue;
            }
            if (sent == null || sent.isEmpty()) {
                break;
            }
            handled++;
        }
        return handled;
    }

    /** Deletes links that expired more than 30 days ago. */
    @Transactional
    public int cleanUp(Instant now) {
        return store.deleteExpiredLinks(now.minus(KEEP_EXPIRED_LINKS));
    }

    /** Queues one email per member who hasn't opted out; returns how many. Nothing to say means no email. */
    private int send(DigestStore.Pending digest, Instant now) {
        Optional<Stylists.ProfileSummary> profile = stylists.profile(digest.orgId())
                .filter(p -> p.status() == ProfileStatus.LIVE && !p.demo());
        if (profile.isEmpty()) {
            return 0;
        }
        TrackingQueries.Range week = range(digest.weekStart(), digest.weekStart().plusWeeks(1));
        long shown = store.clientsShown(digest.orgId(), week);
        List<DigestStore.Tapped> tapped = store.tapped(digest.orgId(), week);
        if (shown == 0 && tapped.isEmpty()) {
            return 0;
        }
        List<UUID> recipients = directory.members(digest.orgId()).stream()
                .filter(user -> !store.optedOut(digest.orgId(), user))
                .toList();
        if (recipients.isEmpty()) {
            return 0;
        }
        TrackingQueries.OrgCounts month = queries.orgCounts(digest.orgId(),
                range(digest.weekStart().plusWeeks(1).minusDays(30), digest.weekStart().plusWeeks(1)));
        String statsLine = statsLine(month);
        List<DigestStore.Tapped> listed = tapped.subList(0, Math.min(tapped.size(), properties.maxClients()));
        String name = profile.get().displayName();
        String statusQuestion = stylists.newClients(digest.orgId())
                .filter(s -> s.confirmedAt().plus(properties.newClientsConfirmAfter()).isBefore(now))
                .map(s -> s.newClients().status() == NewClientStatus.NOT_TAKING
                        ? "Still not taking new clients?" : "Still taking new clients?")
                .orElse(null);

        int queued = 0;
        for (UUID user : recipients) {
            Instant linkExpiry = now.plus(properties.linkTtl());
            List<StylistEmails.DigestClient> clients = new ArrayList<>();
            for (DigestStore.Tapped t : listed) {
                String token = store.newLink(DigestStore.LinkKind.RECORD, digest.id(), digest.orgId(), user, t.requestId(),
                        t.matchId(), now, linkExpiry);
                clients.add(new StylistEmails.DigestClient(t.firstName(), "/digest/" + token + "?type=BOOKED",
                        "/digest/" + token + "?type=REBOOKED", t.booked()));
            }
            String unsubscribe = store.newLink(DigestStore.LinkKind.UNSUBSCRIBE, digest.id(), digest.orgId(), user, null, null,
                    now, now.plus(properties.unsubscribeTtl()));
            String statusConfirm = null;
            if (statusQuestion != null) {
                statusConfirm = "/digest/new-clients/" + store.newLink(DigestStore.LinkKind.NEW_CLIENTS, digest.id(),
                        digest.orgId(), user, null, null, now, linkExpiry);
            }
            StylistEmails.WeeklyDigest email = new StylistEmails.WeeklyDigest(name, digest.weekStart(), shown, clients,
                    statsLine, "/orgs/" + digest.orgId() + "/stats", "/stylist/" + digest.orgId(),
                    "/digest/unsubscribe/" + unsubscribe, statusConfirm, statusQuestion);
            if (emails.weeklyDigest(digest.id(), digest.orgId(), user, email, now)) {
                queued++;
            }
        }
        return queued;
    }

    static String statsLine(TrackingQueries.OrgCounts c) {
        return "Last 30 days: on " + plural(c.matchesShown(), "client list", "client lists") + ", "
                + plural(c.bookingClicks(), "Book tap", "Book taps") + ", " + plural(c.bookings(), "booking", "bookings")
                + ", " + plural(c.rebooks(), "rebook", "rebooks") + ".";
    }

    private static String plural(long n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private TrackingQueries.Range range(LocalDate from, LocalDate toExclusive) {
        return new TrackingQueries.Range(from.atStartOfDay(zone).toInstant(), toExclusive.atStartOfDay(zone).toInstant());
    }

    // --- The links in the email -------------------------------------------------------------------------------------

    /**
     * A RECORD link, for a member of its org: {@code requireMember} throws (a 404) for anyone else, so a forwarded
     * email shows nothing to whoever signs in with it. 404 for an unknown token too.
     */
    @Transactional(readOnly = true)
    LinkInfo info(String token, Consumer<UUID> requireMember, Instant now) {
        DigestStore.Link link = recordLink(token, false);
        requireMember.accept(link.orgId());
        return info(link, now);
    }

    private DigestStore.Link recordLink(String token, boolean forUpdate) {
        return store.link(token, forUpdate)
                .filter(l -> l.kind() == DigestStore.LinkKind.RECORD)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    }

    private static LinkInfo info(DigestStore.Link l, Instant now) {
        return new LinkInfo(l.orgId(), l.clientFirstName(), l.service(), l.weekStart(), l.matchId() == null,
                l.usedAt() != null, !now.isBefore(l.expiresAt()), l.expiresAt(), l.eventId());
    }

    /**
     * Records the BOOKED or REBOOKED a link stands for, as the signed-in caller, who must be a member of the link's
     * org ({@code requireMember} throws a 404 otherwise). Once per link: a used link is LINK_USED, an expired one
     * LINK_EXPIRED. The usual booking checks apply (ALREADY_BOOKED, REBOOK_WITHOUT_BOOKING); a refusal leaves the link
     * unused.
     */
    @Transactional
    TrackingService.Recorded record(String token, Consumer<UUID> requireMember, Type type, Instant occurredAt,
                                    Source source, UUID userId, Instant now) {
        DigestStore.Link locked = recordLink(token, true);
        requireMember.accept(locked.orgId());
        if (locked.usedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, Code.LINK_USED,
                    "This link was already used. Change the booking on your bookings page.");
        }
        if (!now.isBefore(locked.expiresAt())) {
            throw new ApiException(HttpStatus.GONE, Code.LINK_EXPIRED,
                    "This link has expired. Record the booking on your bookings page.");
        }
        TrackingService.Recorded recorded = locked.matchId() != null
                ? tracking.recordBooking(locked.matchId(), locked.orgId(), type, source, userId, null, occurredAt)
                : tracking.recordDirectoryBooking(locked.requestId(), locked.orgId(), type, source, userId, null, occurredAt);
        store.used(locked.id(), userId, recorded.id(), now);
        return recorded;
    }

    // --- Opting out ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    boolean enabledFor(UUID orgId, UUID userId) {
        return !store.optedOut(orgId, userId);
    }

    @Transactional
    void setEnabled(UUID orgId, UUID userId, boolean on, Instant now) {
        if (on) {
            store.optIn(orgId, userId);
        } else {
            store.optOut(orgId, userId, DigestStore.OptOutSource.SETTINGS, now);
        }
    }

    /** The email's unsubscribe link: no sign-in. False (a 404) for an unknown or expired link. Repeats are fine. */
    @Transactional
    boolean unsubscribe(String token, Instant now) {
        Optional<DigestStore.Link> link = store.link(token, false)
                .filter(l -> l.kind() == DigestStore.LinkKind.UNSUBSCRIBE)
                .filter(l -> now.isBefore(l.expiresAt()));
        link.ifPresent(l -> store.optOut(l.orgId(), l.userId(), DigestStore.OptOutSource.UNSUBSCRIBE_LINK, now));
        return link.isPresent();
    }

    /**
     * The email's "Still taking new clients? Confirm" link: no sign-in, like unsubscribing, since all it can do is say
     * the status as it stands is still right. Empty (a 404) for an unknown or expired link. Repeats are fine.
     */
    @Transactional
    Optional<Stylists.NewClientsStatus> confirmNewClients(String token, Instant now) {
        return store.link(token, false)
                .filter(l -> l.kind() == DigestStore.LinkKind.NEW_CLIENTS)
                .filter(l -> now.isBefore(l.expiresAt()))
                .flatMap(l -> stylists.confirmNewClients(l.orgId()));
    }
}
