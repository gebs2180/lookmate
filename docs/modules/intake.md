# Module: `intake`

Client intake: the style quiz, the inspiration photo, the client's link, and the queue the matching module works
from. Clients never sign in: everything client-facing is under `/api/public/match-requests/**` and reached with a
link token (only its SHA-256 is stored).

## Public service for other modules: `com.lookmate.app.intake.MatchRequests`

Everything else in the package is package-private (`ModularityTest`). Hair codes are the strings in the V2 check
constraints (`STRAIGHT|WAVY|CURLY|COILY`, `FINE|MEDIUM|THICK`, `PIXIE|SHORT|MEDIUM|LONG`, the `service` list).

```java
public class MatchRequests {

    public enum Status { NEW, MATCHING, IN_REVIEW, PUBLISHED, CLOSED, FAILED }

    /** A request as other modules see it. No contact details: intake keeps those (and emails the client itself). */
    public record Request(UUID id, String market, Status status, String statusReason, String service,
                          String hairType, String texture, String length, Integer budgetTier, String neighborhood,
                          List<String> pastIssues, String pastIssuesNote, String firstName,
                          boolean inspirationReady, boolean inspirationDeleted,
                          Instant createdAt, Instant updatedAt, Instant publishedAt) { }

    public record Inspiration(byte[] bytes, String contentType, String sha256) { }

    /**
     * Claims up to {@code limit} requests for matching, oldest first: NEW ones whose inspiration photo is confirmed
     * (and not deleted), plus MATCHING ones untouched for lookmate.intake.matching-stuck-after (default 15m: a worker
     * that died). Sets them to MATCHING in this call's own transaction (FOR UPDATE SKIP LOCKED), so two instances
     * never claim the same row.
     */
    @Transactional public List<Request> claimNew(int limit);

    /** The photo's bytes (location/device metadata already stripped). Empty if not uploaded yet or deleted by retention. */
    public Optional<Inspiration> inspirationBytes(UUID id);

    /**
     * Moves a request on. Allowed: NEW->MATCHING; MATCHING->IN_REVIEW|FAILED|NEW; IN_REVIEW->MATCHING;
     * PUBLISHED->IN_REVIEW; FAILED->NEW|MATCHING|IN_REVIEW; anything->CLOSED. PUBLISHED only through publish().
     * Throws IllegalStateException on any other move, NoSuchElementException for an unknown id.
     */
    @Transactional public void markStatus(UUID id, Status status, String reason);

    /**
     * MATCHING (the ranker publishing on its own, reviewedBy null), IN_REVIEW (the admin) or FAILED (a hand-made
     * list) -> PUBLISHED: sets published_at and reviewed_by, and queues the client's email with a fresh link to
     * /m/<token> (template MATCHES_READY), due after lookmate.intake.matches-ready-email-delay. Call it in the same
     * transaction that finalises the match rows.
     */
    @Transactional public void publish(UUID id, UUID reviewedBy);

    /** The client opened their published list (tracking calls it on VIEWED): a MATCHES_READY not yet sent is cancelled. */
    @Transactional public void clientViewedMatches(UUID id);

    /** The request an unexpired link token opens (any of its links), or empty. For tracking's Book redirects. */
    public Optional<Request> forToken(String token);

    /** Recent requests in every status, newest first; market null for all (the admin's monitor). */
    public List<Request> recent(String market, int limit);

    public Optional<Request> view(UUID id);

    /** Newest first, for the admin queue. */
    public List<Request> list(Status status, int limit);
}
```

### Extension point: the client's match list

`GET /api/public/match-requests/{token}` shows matches once the request is PUBLISHED. Intake doesn't build them: it
asks a bean implementing

```java
public interface ClientMatchList {
    /** The client-visible matches (final_rank set, not hidden), best first. Only called for PUBLISHED requests. */
    List<ClientMatch> forClient(UUID matchRequestId);
}

public record ClientMatch(UUID matchId, UUID orgId, int rank, String stylistName, String locationName, String reason,
                          List<String> photoUrls, String instagram, boolean demo) { }
```

The matching module provides the bean (matching -> intake is the only dependency direction). With no bean the list
is empty. `photoUrls` must be short-lived presigned links. The `/m/:token` page builds each "Book" link from its own token
(`/api/public/book/{token}/{matchId}`, tracking's redirect, so the click is recorded) and posts
`/api/public/match-requests/{token}/viewed` when the list shows.

## HTTP API (public, rate-limited per IP)

| Method and path | What |
|---|---|
| `POST /api/public/match-requests` | The quiz. Creates `client` + `match_request` (NEW, default market), returns `{token, statusPath, upload}`; `upload` is a presigned PUT to `intake/{id}/inspiration`. Emails the client the link. 429 `TOO_MANY_OPEN_REQUESTS` past the per-email daily cap. |
| `POST /api/public/match-requests/{token}/inspiration` | A fresh upload slot (the first expired or failed). 409 once a photo is confirmed. |
| `POST /api/public/match-requests/{token}/inspiration/confirm` | Checks the upload (`ImageGuard`: JPEG/PNG within size), strips location and device metadata, stores its SHA-256. 422 `IMAGE_REJECTED` (the upload is deleted; ask for a new slot), 409 `NOT_UPLOADED`. Idempotent. |
| `GET /api/public/match-requests/{token}` | The client's view: stage, quiz summary, photo state (with a short-lived link, and `photoKeptDays`) and, once published, the matches. |
| `GET /api/public/match-requests/{token}/stylists` | The stylist directory scoped to the request's market (`ClientDirectoryController`, using the stylists module's `StylistDirectory`); rate-limited in the browse bucket |
| `GET /api/public/intake/settings` | `{photoKeptDays, maxPhotoBytes}`, so the quiz states the real retention period before anything is sent. |

A wrong or expired token is 404, the same for every case. 429 `RATE_LIMITED` carries `Retry-After`.

## The "matches ready" email

Publishing queues MATCHES_READY in the outbox due `matches-ready-email-delay` (5 min) later, not at once. When the client
opens their list, the page posts `/viewed`; tracking records VIEWED and calls `MatchRequests.clientViewedMatches`, which
cancels the email if it hasn't gone (status CANCELLED, reason "the client opened their matches before it went"). A
client still on their page, which checks back by itself while matching, so never gets an email for what they've seen.
Sent at most once per request, as before.

## Settings (`lookmate.intake` in application.yml)

`link-ttl` (90d), `max-open-requests-per-email-per-day` (3), `matching-stuck-after` (15m),
`matches-ready-email-delay` (5m), `rate-limit.creates-per-hour` (10 per IP), `rate-limit.reads-per-minute` (60 per IP),
`rate-limit.browse-per-minute` (60 per IP: the stylist directory and its Book links, which this limiter also covers).

The rate limiter is in memory, so it is per instance: with more than one app container, move it to Caddy or Postgres
(docs/architecture.md, "How it scales").

## Retention

- Inspiration photo: `lookmate.retention.inspiration-photo-days` (30), `InspirationRetention`.
- Client contact details: `lookmate.retention.client-contact-days` (365) after the latest request by that email,
  `ClientContactRetention`. Email, phone, first name, neighborhood and the note are cleared; the quiz codes stay for
  the fairness metrics. Decided 2026-11-20 (CLAUDE.md).
- Links: `lookmate.intake.link-ttl` (90d).

## Photo metadata: one copy, in `storage`

`com.lookmate.app.storage.PhotoMetadata.strip(bytes)` is the only metadata stripper in LookMate (and
`PhotoMetadata.orientation(bytes)`), used by both intake and stylists since the merge of 2026-11-20.

## Schema (V4)

`match_request_link` (one row per emailed link, hashed, with an expiry), `match_request.inspiration_size_bytes`,
and an index on `lower(client.email)` for the abuse guard. One `client` row per request: quiz answers are a snapshot.

## Emails (notify)

Clients have no user row, so notify gained a public `ClientEmails` (`requestReceived`, `matchesReady`) over a new
`Notifier.queueToAddress`, and the template `MATCH_REQUEST_RECEIVED`. `MatchRequests.publish` sends `MATCHES_READY`;
the matching module should not email clients itself.

## Privacy

- The photo is uploaded straight to storage with a presigned PUT bound to type, size and `If-None-Match: *`. On
  confirm it must pass `ImageGuard`; the stored original is rewritten without EXIF/XMP/text (orientation kept,
  `storage/PhotoMetadata`, ported from pokemans' `ExactCopy`). A file that fails either check is deleted at once.
- The client sees their own photo through a presigned link only (`photoUrl`, short-lived).
- Inspiration photos are deleted after `lookmate.retention.inspiration-photo-days` (`InspirationRetention`) and are
  left out of the photo backups, which can't delete.
- Tokens are in URLs. Caddy's access log redacts `/m/<token>` and `/api/public/match-requests/<token>` (its error log,
  written only when the backend fails, still has the raw URI). Book links on `/m/:token` should be
  `rel="noreferrer"` (the web page sets `<meta name="referrer" content="no-referrer">`).

## Web

`/find` (the quiz, one question per screen, then the photo and an email) and `/m/:token` (the client's request: stage,
photo, matches once published; it can also take the photo if the first upload failed). While the request is NEW or
being matched, `/m/:token` checks back by itself (`usePoll`: every 1.5s for the first 30s, then ×1.5 up to every 30s,
for 20 minutes) and shows the list in place once published. `/m/:token/stylists` and `/stylists` are the directory (below the
recommendations, never instead of them; see [stylists.md](stylists.md)). Both render in
`web/src/pages/public/PublicShell.tsx`, outside the signed-in shell. The browser re-encodes the photo as a JPEG of at
most 2048 px before upload (which also drops its metadata and turns HEIC into JPEG where the browser can decode it);
the server strips metadata again regardless.
