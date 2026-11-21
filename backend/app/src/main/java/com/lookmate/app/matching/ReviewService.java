package com.lookmate.app.matching;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.matching.CandidateQueries.Photo;
import com.lookmate.app.intake.ClientMatch;
import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.matching.CandidateQueries.Stylist;
import com.lookmate.app.matching.MatchStore.MatchRow;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import com.lookmate.app.tracking.TrackingService;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin's view of matching: the monitor of recent requests (what each client was shown and did), the review of
 * one request's matches, and the client's read of them once published. Lists publish on their own unless
 * {@code lookmate.matching.review-before-publish} is on (then they wait IN_REVIEW, brief Must have 5). Every change
 * takes the request's row lock; reorder and publish need the request IN_REVIEW, while adding, hiding or showing a
 * match also works after publishing (the client's list changes at once).
 */
@Service
class ReviewService {

    /** Codes the web app can branch on. */
    enum Code { NOT_IN_REVIEW, NO_VISIBLE_MATCHES, ALREADY_MATCHED, STYLIST_NOT_AVAILABLE, BAD_ORDER }

    record QueueItem(UUID id, String status, String statusReason, String market, String service, HairType hairType, String texture,
                     String length, String firstName, Instant createdAt, int candidates) {
    }

    record RequestView(UUID id, String status, String statusReason, String market, String service, HairType hairType, String texture,
                       String length, Integer budgetTier, String neighborhood, List<String> pastIssues, String notes, String firstName,
                       Instant createdAt, Instant publishedAt, boolean inspirationDeleted) {
    }

    record EvidencePhoto(UUID id, URI url, String service, String hairType) {
    }

    record MatchView(UUID id, UUID orgId, String stylistName, String locationName, String instagram, boolean stylistLive, double score,
                     String reason, int systemRank, Integer finalRank, boolean hidden, boolean adminOverride, String adminNote, String source,
                     List<EvidencePhoto> evidence) {
    }

    /** {@code inspirationUrl} is a short-lived link, null once the photo is deleted (retention). */
    record Review(RequestView request, URI inspirationUrl, List<MatchView> matches) {
    }

    record StylistOption(UUID orgId, String displayName, String locationName, boolean alreadyMatched) {
    }

    record FairnessReport(String market, Instant since, List<CandidateQueries.FairnessRow> rows) {
    }

    /** One match as the monitor shows it: where the client saw it (null rank: not shown) and what they did with it. */
    record MonitorMatch(UUID id, UUID orgId, String stylistName, boolean stylistLive, Integer finalRank, int systemRank, boolean hidden,
                        String source, long bookingClicks, long bookings, long rebooks) {
    }

    /** A recent request: its state, the list the client was shown, and their activity (views, clicks, bookings). */
    record MonitorItem(UUID id, String status, String statusReason, String market, String service, HairType hairType, String firstName,
                       Instant createdAt, Instant publishedAt, boolean adminChanged, Instant firstViewedAt, List<MonitorMatch> matches,
                       long directoryClicks, long directoryBookings) {
    }

    /** {@code reviewBeforePublish}: whether lists wait for the admin (the review queue is then the main view). */
    record Monitor(boolean reviewBeforePublish, List<MonitorItem> requests) {
    }

    private final CandidateQueries queries;
    private final MatchStore matches;
    private final MatchRequests requests;
    private final EmbeddingProvider provider;
    private final MatchingProperties properties;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final TrackingService tracking;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final Clock clock;

    ReviewService(CandidateQueries queries, MatchStore matches, MatchRequests requests, EmbeddingProvider provider,
                  MatchingProperties properties, BlobStore blobs, StorageProperties storage, TrackingService tracking,
                  org.springframework.transaction.support.TransactionTemplate tx, Optional<Clock> clock) {
        this.queries = queries;
        this.matches = matches;
        this.requests = requests;
        this.provider = provider;
        this.properties = properties;
        this.blobs = blobs;
        this.storage = storage;
        this.tracking = tracking;
        this.tx = tx;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @Transactional(readOnly = true)
    List<QueueItem> queue(MatchRequests.Status status) {
        // Intake lists newest first; the review queue works oldest first.
        return requests.list(status, 200).reversed().stream()
                .map(r -> new QueueItem(r.id(), r.status().name(), r.statusReason(), r.market(), r.service(), HairType.parse(r.hairType()),
                        r.texture(), r.length(), r.firstName(), r.createdAt(), matches.forRequest(r.id()).size()))
                .toList();
    }

    /**
     * The most recent requests in every status, newest first ({@code market} null for all), with the list each client
     * was shown and what they did: views, Book clicks and bookings per match, and stylists picked from the directory.
     */
    @Transactional(readOnly = true)
    Monitor monitor(String market, int limit) {
        List<MatchRequests.Request> recent = requests.recent(market, limit);
        Map<UUID, TrackingService.RequestActivity> activity = tracking.activity(recent.stream().map(MatchRequests.Request::id).toList());
        Map<UUID, List<MatchRow>> rowsByRequest = new java.util.HashMap<>();
        for (MatchRequests.Request r : recent) {
            rowsByRequest.put(r.id(), matches.forRequest(r.id()));
        }
        Map<UUID, Stylist> stylists = stylistsOf(rowsByRequest.values().stream().flatMap(List::stream).toList());
        List<MonitorItem> items = recent.stream().map(r -> {
            TrackingService.RequestActivity a = activity.get(r.id());
            boolean published = r.publishedAt() != null;
            List<MonitorMatch> shown = rowsByRequest.get(r.id()).stream().map(m -> {
                Stylist s = stylists.get(m.orgId());
                TrackingService.MatchActivity n = a.match(m.id());
                return new MonitorMatch(m.id(), m.orgId(), s == null ? null : s.displayName(), s != null && "LIVE".equals(s.status()),
                        published && !m.hidden() ? m.finalRank() : null, m.systemRank(), m.hidden(), m.source(), n.bookingClicks(),
                        n.bookings(), n.rebooks());
            }).toList();
            return new MonitorItem(r.id(), r.status().name(), r.statusReason(), r.market(), r.service(), HairType.parse(r.hairType()),
                    r.firstName(), r.createdAt(), r.publishedAt(), shown.stream().anyMatch(m -> !"SYSTEM".equals(m.source()))
                    || rowsByRequest.get(r.id()).stream().anyMatch(MatchRow::adminOverride), a.firstViewedAt(), shown,
                    a.directoryClicks(), a.directoryBookings());
        }).toList();
        return new Monitor(properties.reviewBeforePublish(), items);
    }

    @Transactional(readOnly = true)
    Review review(UUID requestId) {
        return view(request(requestId));
    }

    @Transactional(readOnly = true)
    List<StylistOption> stylistsToAdd(UUID requestId) {
        MatchRequests.Request request = request(requestId);
        Set<UUID> matched = matches.forRequest(requestId).stream().map(MatchRow::orgId).collect(Collectors.toSet());
        return queries.liveStylists(request.market()).stream()
                .map(s -> new StylistOption(s.orgId(), s.displayName(), s.locationName(), matched.contains(s.orgId())))
                .toList();
    }

    /** Sets the order: {@code matchIds} must be every match of the request, hidden ones included, best first. */
    @Transactional
    Review reorder(UUID requestId, List<UUID> matchIds) {
        lockInReview(requestId);
        List<MatchRow> rows = matches.forRequest(requestId);
        Set<UUID> ids = rows.stream().map(MatchRow::id).collect(Collectors.toSet());
        if (matchIds.size() != ids.size() || !ids.equals(new HashSet<>(matchIds))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.BAD_ORDER, "The order must list every match of this request exactly once.");
        }
        Map<UUID, MatchRow> byId = rows.stream().collect(Collectors.toMap(MatchRow::id, Function.identity()));
        Instant now = clock.instant();
        for (int i = 0; i < matchIds.size(); i++) {
            MatchRow row = byId.get(matchIds.get(i));
            int rank = i + 1;
            if (row.finalRank() == null || row.finalRank() != rank) {
                matches.setFinalRank(row.id(), rank, rank != row.systemRank(), now);
            }
        }
        return view(request(requestId));
    }

    /**
     * Hides or shows a match and sets the reviewer's note on it. Also after publishing: hiding takes the stylist off the
     * client's list at once, and showing one again puts it back (last, if it was hidden when the list was published).
     */
    @Transactional
    Review update(UUID requestId, UUID matchId, boolean hidden, String note) {
        String status = lock(requestId, "IN_REVIEW", "PUBLISHED");
        MatchRow row = matches.find(requestId, matchId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant now = clock.instant();
        matches.update(matchId, hidden, blankToNull(note), hidden != row.hidden(), now);
        if ("PUBLISHED".equals(status) && !hidden && row.finalRank() == null) {
            int last = matches.forRequest(requestId).stream().map(MatchRow::finalRank).filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue).max().orElse(0);
            matches.setFinalRank(matchId, last + 1, true, now);
        }
        return view(request(requestId));
    }

    /**
     * Adds a LIVE stylist in the request's market that the ranker didn't pick. Also after publishing (a list published
     * empty, or one the admin wants to extend): the stylist goes last on the client's list at once, and the reason the
     * list was empty is cleared; intake emails the client if they asked to hear about picks ({@link MatchRequests#picksAdded}).
     */
    @Transactional
    Review add(UUID requestId, UUID orgId, String note) {
        String status = lock(requestId, "IN_REVIEW", "PUBLISHED");
        MatchRequests.Request request = request(requestId);
        Stylist stylist = queries.stylists(List.of(orgId)).stream().findFirst()
                .filter(s -> "LIVE".equals(s.status()) && request.market().equals(s.market()))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, Code.STYLIST_NOT_AVAILABLE,
                        "That stylist isn't live in this market."));
        if (matches.forRequest(requestId).stream().anyMatch(m -> m.orgId().equals(orgId))) {
            throw new ApiException(HttpStatus.CONFLICT, Code.ALREADY_MATCHED, "That stylist is already in the list. Show them instead.");
        }
        List<UUID> evidence = queries.bestPhotosOf(stylist.orgId(), requestId, provider.model(), request.service(),
                properties.ranking().evidencePhotos());
        matches.insertByAdmin(requestId, orgId, evidence, blankToNull(note), clock.instant());
        if ("PUBLISHED".equals(status) && request.statusReason() != null) {
            // A list published empty gets its first pick: intake clears the reason and emails the client if they asked.
            requests.picksAdded(requestId);
            return view(request(requestId));
        }
        return view(request);
    }

    /**
     * Publishes the visible matches in the reviewer's order (ranks 1..n). Intake moves the request to PUBLISHED and
     * emails the client a fresh link to them, in this transaction.
     */
    @Transactional
    Review publish(UUID requestId, UUID reviewer) {
        lockInReview(requestId);
        if (matches.forRequest(requestId).stream().noneMatch(MatchRow::visible)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.NO_VISIBLE_MATCHES, "Show or add at least one stylist before publishing.");
        }
        finalizeAndPublish(requestId, reviewer);
        return view(request(requestId));
    }

    /**
     * With review switched off, publishes up to {@code limit} requests still waiting IN_REVIEW (from while the switch was
     * on), each with its current list: the reviewer's order, hides and additions kept. A request with no match at all
     * (the ranker found nobody and nobody was added) is published empty, as the ranker does now; one whose matches are
     * all hidden stays for the admin. Each request in its own transaction; returns how many were published.
     */
    int publishWaitingReviews(int limit) {
        if (properties.reviewBeforePublish()) {
            return 0;
        }
        int published = 0;
        for (MatchRequests.Request r : requests.list(MatchRequests.Status.IN_REVIEW, limit).reversed()) {
            Boolean done = tx.execute(s -> {
                // The admin may be publishing it right now: take the lock and look again.
                if (!"IN_REVIEW".equals(queries.lockStatus(r.id()).orElse(null))) {
                    return false;
                }
                List<MatchRow> rows = matches.forRequest(r.id());
                if (rows.isEmpty()) {
                    requests.publishWithoutMatches(r.id(), Ranker.NO_CANDIDATES);
                    return true;
                }
                if (rows.stream().noneMatch(MatchRow::visible)) {
                    return false;
                }
                finalizeAndPublish(r.id(), null);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                published++;
            }
        }
        return published;
    }

    /** Final ranks 1..n over the visible matches in the current order, then intake publishes. Caller holds the row lock. */
    private void finalizeAndPublish(UUID requestId, UUID reviewer) {
        Instant now = clock.instant();
        List<MatchRow> rows = matches.forRequest(requestId);
        int rank = 0;
        for (MatchRow row : rows) {
            Integer finalRank = row.visible() ? ++rank : null;
            if (!java.util.Objects.equals(finalRank, row.finalRank())) {
                matches.setFinalRank(row.id(), finalRank, false, now);
            }
        }
        requests.publish(requestId, reviewer);
    }

    @Transactional(readOnly = true)
    FairnessReport fairness(String market, int days) {
        Instant since = clock.instant().minus(java.time.Duration.ofDays(days));
        return new FairnessReport(market, since, queries.fairness(market, since));
    }

    /**
     * The client's list ({@code ClientMatchList}, served by intake through the client's link): ranked, unhidden matches
     * of stylists still LIVE, renumbered 1..n, with signed links to the evidence photos. No scores or reviewer notes.
     * The booking link is built by the client page, which has the token tracking's redirect needs.
     */
    @Transactional(readOnly = true)
    List<ClientMatch> forClient(UUID requestId) {
        List<MatchRow> rows = matches.published(requestId);
        Map<UUID, Stylist> stylists = stylistsOf(rows);
        Map<UUID, Photo> photos = photosOf(rows);
        List<ClientMatch> out = new ArrayList<>();
        for (MatchRow m : rows) {
            Stylist s = stylists.get(m.orgId());
            if (s == null || !"LIVE".equals(s.status())) {
                continue; // a stylist who left or was hidden since
            }
            List<String> evidence = m.evidence().stream().map(photos::get)
                    .filter(p -> p != null && "READY".equals(p.status()))
                    .map(p -> url(p.objectKey()).toString()).toList();
            out.add(new ClientMatch(m.id(), m.orgId(), out.size() + 1, s.displayName(), s.locationName(), m.reason(), evidence, s.instagram(),
                    s.demo()));
        }
        return out;
    }

    private MatchRequests.Request request(UUID id) {
        return requests.view(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void lockInReview(UUID requestId) {
        lock(requestId, "IN_REVIEW");
    }

    /** Takes the request's row lock and returns its status, which must be one of {@code allowed}. */
    private String lock(UUID requestId, String... allowed) {
        String status = queries.lockStatus(requestId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!List.of(allowed).contains(status)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.NOT_IN_REVIEW, "This request isn't in review any more (it's " + status.toLowerCase()
                    .replace('_', ' ') + "). Reload the page.");
        }
        return status;
    }

    private Review view(MatchRequests.Request r) {
        List<MatchRow> rows = matches.forRequest(r.id());
        Map<UUID, Stylist> stylists = stylistsOf(rows);
        Map<UUID, Photo> photos = photosOf(rows);
        List<MatchView> views = rows.stream().map(m -> {
            Stylist s = stylists.get(m.orgId());
            List<EvidencePhoto> evidence = m.evidence().stream().map(photos::get)
                    .filter(p -> p != null && "READY".equals(p.status()))
                    .map(p -> new EvidencePhoto(p.id(), url(p.objectKey()), p.service(), p.hairType())).toList();
            return new MatchView(m.id(), m.orgId(), s == null ? null : s.displayName(), s == null ? null : s.locationName(),
                    s == null ? null : s.instagram(), s != null && "LIVE".equals(s.status()), m.score(), m.reason(), m.systemRank(),
                    m.finalRank(), m.hidden(), m.adminOverride(), m.adminNote(), m.source(), evidence);
        }).toList();
        Optional<String> inspiration = queries.inspirationKey(r.id());
        RequestView request = new RequestView(r.id(), r.status().name(), r.statusReason(), r.market(), r.service(), HairType.parse(r.hairType()),
                r.texture(), r.length(), r.budgetTier(), r.neighborhood(), r.pastIssues(), r.pastIssuesNote(), r.firstName(), r.createdAt(),
                r.publishedAt(), r.inspirationDeleted());
        return new Review(request, inspiration.map(this::url).orElse(null), views);
    }

    private Map<UUID, Stylist> stylistsOf(List<MatchRow> rows) {
        return queries.stylists(rows.stream().map(MatchRow::orgId).distinct().toList()).stream()
                .collect(Collectors.toMap(Stylist::orgId, Function.identity()));
    }

    private Map<UUID, Photo> photosOf(List<MatchRow> rows) {
        return queries.photos(rows.stream().flatMap(m -> m.evidence().stream()).distinct().toList()).stream()
                .collect(Collectors.toMap(Photo::id, Function.identity()));
    }

    private URI url(String objectKey) {
        return blobs.presignDownload(storage.photoBucket(), objectKey);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
