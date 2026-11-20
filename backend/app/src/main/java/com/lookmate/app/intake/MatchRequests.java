package com.lookmate.app.intake;

import com.lookmate.app.notify.ClientEmails;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Intake's public service: what the matching module (and the admin screens) use to work through client requests
 * (docs/modules/intake.md). It never hands out contact details: intake emails the client itself.
 */
@Service
public class MatchRequests {

    public enum Status { NEW, MATCHING, IN_REVIEW, PUBLISHED, CLOSED, FAILED }

    /** A request as other modules see it. Hair codes are the V2 check-constraint strings. */
    public record Request(UUID id, String market, Status status, String statusReason, String service, String hairType, String texture,
                          String length, Integer budgetTier, String neighborhood, List<String> pastIssues, String pastIssuesNote,
                          String firstName, boolean inspirationReady, boolean inspirationDeleted, Instant createdAt, Instant updatedAt,
                          Instant publishedAt) {
    }

    /** The inspiration photo as stored: location and device metadata already stripped. */
    public record Inspiration(byte[] bytes, String contentType, String sha256) {
    }

    /** The moves {@link #markStatus} allows; PUBLISHED only through {@link #publish}, CLOSED from anywhere. */
    private static final Map<Status, Set<Status>> MOVES = Map.of(
            Status.NEW, EnumSet.of(Status.MATCHING),
            Status.MATCHING, EnumSet.of(Status.IN_REVIEW, Status.FAILED, Status.NEW),
            Status.IN_REVIEW, EnumSet.of(Status.MATCHING),
            Status.PUBLISHED, EnumSet.of(Status.IN_REVIEW),
            Status.FAILED, EnumSet.of(Status.NEW, Status.MATCHING, Status.IN_REVIEW),
            Status.CLOSED, EnumSet.noneOf(Status.class));

    private final MatchRequestRows rows;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final IntakeProperties properties;
    private final ClientEmails emails;

    MatchRequests(MatchRequestRows rows, BlobStore blobs, StorageProperties storage, IntakeProperties properties, ClientEmails emails) {
        this.rows = rows;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
        this.emails = emails;
    }

    /**
     * Claims up to {@code limit} requests for matching, oldest first: NEW ones whose inspiration photo is confirmed (and
     * not deleted), and MATCHING ones untouched for {@code lookmate.intake.matching-stuck-after} (a worker that died).
     * They are MATCHING when this returns. Rows another instance holds are skipped.
     */
    @Transactional
    public List<Request> claimNew(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Instant now = Instant.now();
        List<UUID> ids = rows.claim(limit, now.minus(properties.matchingStuckAfter()), now);
        return rows.findAll(ids).stream().map(MatchRequests::request).toList();
    }

    /** The photo's bytes, or empty if it hasn't been confirmed yet or retention deleted it. */
    @Transactional(readOnly = true)
    public Optional<Inspiration> inspirationBytes(UUID id) {
        return rows.find(id).filter(MatchRequestRows.Row::inspirationReady)
                .map(r -> new Inspiration(blobs.read(storage.photoBucket(), r.objectKey()), r.contentType(), r.sha256()));
    }

    /**
     * Moves a request on (see {@link #MOVES}); the same status again just updates the reason.
     *
     * @throws NoSuchElementException for an unknown id
     * @throws IllegalStateException  for a move that isn't allowed
     */
    @Transactional
    public void markStatus(UUID id, Status status, String reason) {
        MatchRequestRows.Row row = rows.findLocked(id).orElseThrow(() -> new NoSuchElementException("No match request " + id));
        Status from = Status.valueOf(row.status());
        if (from != status && status != Status.CLOSED && !MOVES.get(from).contains(status)) {
            throw new IllegalStateException("A match request can't go from " + from + " to " + status
                    + (status == Status.PUBLISHED ? " here: use publish()" : ""));
        }
        rows.setStatus(id, status.name(), reason, Instant.now());
    }

    /**
     * To PUBLISHED: from MATCHING (the ranker publishing on its own, {@code reviewedBy} null), IN_REVIEW (the admin), or
     * FAILED (a list made by hand). The client is emailed a fresh link to their matches after
     * {@code lookmate.intake.matches-ready-email-delay}, unless they open the list first ({@link #clientViewedMatches}).
     * Call it in the transaction that finalises the match rows.
     */
    @Transactional
    public void publish(UUID id, UUID reviewedBy) {
        MatchRequestRows.Row row = rows.findLocked(id).orElseThrow(() -> new NoSuchElementException("No match request " + id));
        Status from = Status.valueOf(row.status());
        if (from != Status.MATCHING && from != Status.IN_REVIEW && from != Status.FAILED) {
            throw new IllegalStateException("Only a request being matched or in review can be published; this one is " + from);
        }
        Instant now = Instant.now();
        rows.setPublished(id, reviewedBy, now);
        String token = LinkTokens.newToken();
        rows.insertLink(LinkTokens.hash(token), id, "MATCHES_READY", now, now.plus(properties.linkTtl()));
        emails.matchesReady(id, row.email(), row.firstName(), LinkTokens.path(token), now.plus(properties.matchesReadyEmailDelay()), now);
    }

    /**
     * The client opened their published list (tracking calls this when it records VIEWED): the "matches ready" email is
     * no longer needed, so it is cancelled if it hasn't gone yet.
     */
    @Transactional
    public void clientViewedMatches(UUID id) {
        emails.cancelMatchesReady(id, Instant.now());
    }

    /**
     * The request a client's link token opens (any unexpired link: the one from the quiz or from a later email), or
     * empty. For other modules' public endpoints that take the token (tracking's Book links). Every failure is empty.
     */
    @Transactional(readOnly = true)
    public Optional<Request> forToken(String token) {
        if (!LinkTokens.wellFormed(token)) {
            return Optional.empty();
        }
        return rows.requestIdForToken(LinkTokens.hash(token), Instant.now()).flatMap(rows::find).map(MatchRequests::request);
    }

    /** The most recent requests in every status, newest first, for the admin's monitor; {@code market} null for all. */
    @Transactional(readOnly = true)
    public List<Request> recent(String market, int limit) {
        return rows.recent(market, Math.max(0, Math.min(limit, 500))).stream().map(MatchRequests::request).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Request> view(UUID id) {
        return rows.find(id).map(MatchRequests::request);
    }

    /** Newest first, for the admin's queue. */
    @Transactional(readOnly = true)
    public List<Request> list(Status status, int limit) {
        return rows.list(status.name(), Math.max(0, Math.min(limit, 500))).stream().map(MatchRequests::request).toList();
    }

    static Request request(MatchRequestRows.Row r) {
        return new Request(r.id(), r.market(), Status.valueOf(r.status()), r.statusReason(), r.service(), r.hairType(), r.texture(),
                r.length(), r.budgetTier(), r.neighborhood(), r.pastIssues(), r.notes(), r.firstName(), r.inspirationReady(),
                r.inspirationDeletedAt() != null, r.createdAt(), r.updatedAt(), r.publishedAt());
    }
}
