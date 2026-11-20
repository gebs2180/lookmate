package com.lookmate.app.matching;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.matching.CandidateQueries.Photo;
import com.lookmate.app.intake.ClientMatch;
import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.matching.CandidateQueries.Stylist;
import com.lookmate.app.matching.MatchStore.MatchRow;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
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
 * The admin's review of a request's matches (brief, Must have 5), and the client's read of them once published.
 * Every change takes the request's row lock and needs the request IN_REVIEW, so two reviewers can't publish
 * different lists.
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

    private final CandidateQueries queries;
    private final MatchStore matches;
    private final MatchRequests requests;
    private final EmbeddingProvider provider;
    private final MatchingProperties properties;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final Clock clock;

    ReviewService(CandidateQueries queries, MatchStore matches, MatchRequests requests, EmbeddingProvider provider,
                  MatchingProperties properties, BlobStore blobs, StorageProperties storage, Optional<Clock> clock) {
        this.queries = queries;
        this.matches = matches;
        this.requests = requests;
        this.provider = provider;
        this.properties = properties;
        this.blobs = blobs;
        this.storage = storage;
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

    /** Hides or shows a match and sets the reviewer's note on it. */
    @Transactional
    Review update(UUID requestId, UUID matchId, boolean hidden, String note) {
        lockInReview(requestId);
        MatchRow row = matches.find(requestId, matchId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        matches.update(matchId, hidden, blankToNull(note), hidden != row.hidden(), clock.instant());
        return view(request(requestId));
    }

    /** Adds a LIVE stylist in the request's market that the ranker didn't pick. */
    @Transactional
    Review add(UUID requestId, UUID orgId, String note) {
        lockInReview(requestId);
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
        return view(request);
    }

    /**
     * Publishes the visible matches in the reviewer's order (ranks 1..n). Intake moves the request to PUBLISHED and
     * emails the client a fresh link to them, in this transaction.
     */
    @Transactional
    Review publish(UUID requestId, UUID reviewer) {
        lockInReview(requestId);
        Instant now = clock.instant();
        List<MatchRow> rows = matches.forRequest(requestId);
        if (rows.stream().noneMatch(MatchRow::visible)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.NO_VISIBLE_MATCHES, "Show or add at least one stylist before publishing.");
        }
        int rank = 0;
        for (MatchRow row : rows) {
            Integer finalRank = row.visible() ? ++rank : null;
            if (!java.util.Objects.equals(finalRank, row.finalRank())) {
                matches.setFinalRank(row.id(), finalRank, false, now);
            }
        }
        requests.publish(requestId, reviewer);
        return view(request(requestId));
    }

    @Transactional(readOnly = true)
    FairnessReport fairness(String market, int days) {
        Instant since = clock.instant().minus(java.time.Duration.ofDays(days));
        return new FairnessReport(market, since, queries.fairness(market, since));
    }

    /**
     * The client's list ({@code ClientMatchList}, served by intake through the client's link): ranked, unhidden matches
     * of stylists still LIVE, renumbered 1..n, with signed links to the evidence photos. No scores or reviewer notes.
     * {@code bookUrl} is left to the client page, which has the token tracking's redirect needs.
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
            out.add(new ClientMatch(m.id(), out.size() + 1, s.displayName(), s.locationName(), m.reason(), evidence, null, s.instagram()));
        }
        return out;
    }

    private MatchRequests.Request request(UUID id) {
        return requests.view(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void lockInReview(UUID requestId) {
        String status = queries.lockStatus(requestId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"IN_REVIEW".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, Code.NOT_IN_REVIEW, "This request isn't in review any more (it's " + status.toLowerCase()
                    .replace('_', ' ') + "). Reload the page.");
        }
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
