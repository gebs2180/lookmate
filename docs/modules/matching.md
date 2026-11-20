# Module: matching

Package `com.lookmate.app.matching`. Matching v1, publishing, the admin's monitor, and the review (brief, Must have 4
and 5). Design: [architecture.md](../architecture.md), "Matching v1".

## Publishing (decided 2026-11-20: matching publishes automatically; admin monitors)

- **Switch off (the default):** once a request is ranked, `Ranker` publishes it in the same transaction through intake's
  `MatchRequests.publish(id, null)` (MATCHING → PUBLISHED; `reviewed_by` stays null). The final ranking is the system
  ranking (`insertSystem` sets `final_rank = system_rank`). A request with no candidates goes to IN_REVIEW with
  `NO_CANDIDATES`, so the admin can add stylists by hand and publish; the client can use the directory meanwhile.
- **Switch on** (`lookmate.matching.review-before-publish: true`, env `MATCHING_REVIEW_BEFORE_PUBLISH`): every ranked
  request goes to IN_REVIEW and waits for the admin to publish, exactly as in Phase 1. The web app's `/admin/matches`
  shows the review queue again.
- **Switching review off while lists wait (decided 2026-11-20):** each worker pass (`MatchingJob`, after ranking)
  publishes requests still IN_REVIEW as they stand: the reviewer's order, hides and additions kept, final ranks 1..n
  over the visible matches, `reviewed_by` null (`ReviewService.publishWaitingReviews`, under the request's row lock, so
  an admin publishing at the same moment wins cleanly). A request with nothing visible (no candidates, or all hidden)
  stays IN_REVIEW for the admin. With the switch on, this step does nothing. Tested in `AutoPublishFlowTest`.
- **After the fact, either way:** on a PUBLISHED request the admin can hide a match (`PUT .../matches/{matchId}`, it
  leaves the client's list and its Book link at once) or show it again (it goes last if it was hidden at publishing),
  and edit notes. Reordering, adding and publishing stay IN_REVIEW only. To take a stylist out of every result, the
  admin hides their profile (`POST /api/admin/stylists/{orgId}/hide`, the stylists module's existing hide).

## Contract with intake (built against `claude/lookmate-intake`, merged into this branch)

- Matching takes requests with `MatchRequests.claimNew` (NEW with a confirmed photo → MATCHING, `SKIP LOCKED`), reads the
  photo with `inspirationBytes`, and moves them on with `publish` (switch off, at least one stylist) or `markStatus`
  (IN_REVIEW, or FAILED after `max-attempts`).
  If embedding fails, the request stays MATCHING with a reason; intake hands it back after
  `lookmate.intake.matching-stuck-after` (15m).
- Publishing calls `MatchRequests.publish`, which sets PUBLISHED and queues the client's email with a fresh link
  (MATCHES_READY), held for `lookmate.intake.matches-ready-email-delay` and cancelled if the client opens the list
  first. Matching sends no email itself.
- Matching provides intake's `ClientMatchList` bean (`MatchListForClients`): the client's page,
  `GET /api/public/match-requests/{token}` (intake's), shows the published matches. The web page builds each "Book"
  link itself from its token (`/api/public/book/{token}/{matchId}`, tracking's redirect) and posts the view.
- Still read with SQL (`CandidateQueries`): the inspiration photo's object key, for the admin's signed link (intake's
  service has no link for it), a `FOR UPDATE` lock on the request row while a reviewer edits, and the fairness report's
  counts by `client.hair_type`.

## Contract with stylists

The stylists module's code isn't merged yet (only its API doc, docs/modules/stylists.md). Until it is, matching reads
with SQL: `stylist_profile` (LIVE, market), `salon_location.name`, `portfolio_photo` (READY, `service`, `hair_type`,
`object_key`). Only READY photos with a hair type and the requested service are candidates; every READY photo is
embedded. Once `Stylists` is merged, swap to `readyPhotos`, `liveProfiles`, `photoUrl` and `photoBytes`, and filter
the SQL candidates by `readyPhotos` (the vector query itself stays in matching).

## Endpoints

Admin (ADMIN with MFA, by path):

| | |
|---|---|
| `GET /api/admin/match-monitor?market=&limit=50` | The monitor: recent requests in every status, newest first, each with the list the client was shown (rank, hidden), clicks, bookings and rebooks per match, when the client first opened it, directory clicks and bookings; plus `reviewBeforePublish` |
| `GET /api/admin/match-requests?status=IN_REVIEW` | Queue (also MATCHING, FAILED, PUBLISHED), oldest first |
| `GET /api/admin/match-requests/{id}` | Request + quiz answers, inspiration photo (signed URL), candidates with evidence photos (signed URLs) |
| `GET /api/admin/match-requests/{id}/stylists` | LIVE stylists in the market, for adding by hand |
| `PUT /api/admin/match-requests/{id}/order` | `{matchIds}`: every match, best first → `final_rank` |
| `PUT /api/admin/match-requests/{id}/matches/{matchId}` | `{hidden, note}` |
| `POST /api/admin/match-requests/{id}/matches` | `{orgId, note}`: add a stylist (`source = 'ADMIN'`) |
| `POST /api/admin/match-requests/{id}/publish` | Final ranks 1..n over the visible matches, then intake's `publish` (PUBLISHED + client email) |
| `GET /api/admin/match-reports/fairness?market=&days=90` | Requests, no-match rate, reviewer changes, published, shown, clicked, booked, mean top score, by client hair type |

Changes need the request IN_REVIEW (409 `NOT_IN_REVIEW` otherwise), except hiding, showing and notes, which also work
on a PUBLISHED request. The monitor's activity comes from tracking's public `TrackingService.activity` (no table
reads). Other codes: `NO_VISIBLE_MATCHES`,
`ALREADY_MATCHED`, `STYLIST_NOT_AVAILABLE`, `BAD_ORDER`.

The client's list is served by intake (see above); it holds only published, ranked, unhidden matches of stylists still
LIVE, with no scores or reviewer notes. Each carries the stylist's `orgId` (so the directory can mark "On your list")
and `demo` (shown as "Sample stylist").

## Pipeline

`MatchingJob` runs every `lookmate.matching.worker.poll-interval` (30s): embed new portfolio photos (batches claimed
with `FOR UPDATE SKIP LOCKED`; the rows stay locked for the provider call, so batches are small, 16), then claim
requests from intake, embed each inspiration photo outside a transaction and rank it in one. Portfolio failures back
off (`embedding_attempt`, V5) and stop after `max-attempts`; a request whose photo never embeds becomes FAILED.

Scoring (`Scoring`, pure): per stylist, `0.75 × mean(best 3 weighted similarities) + 0.25 × min(1, similar / 10)`.
A photo's weight is 1 for the client's hair type, 0.5 for a neighbouring type, 0 otherwise (`HairCompatibility`).
"Similar" means cosine ≥ 0.8. All of these are settings under `lookmate.matching.ranking`. The top 5 stylists are kept,
with their 4 best photos as evidence.

## Open questions

1. **Inspiration link for the admin:** could intake's `MatchRequests` offer `inspirationUrl(id)`, so matching stops
   reading `inspiration_object_key`?
2. **Locking during review:** matching takes `select ... for update` on `match_request` while a reviewer edits. A
   `MatchRequests.lock(id)` would remove the last read of intake's table outside the fairness report.
3. Should a request rank only after all of the market's portfolio photos are embedded? Today it ranks with what's there.
4. Re-rank after a stylist adds photos (intake allows IN_REVIEW → MATCHING)? No button yet; re-ranking keeps existing
   match rows (`on conflict do nothing`).
6. **No candidates with auto-publish:** such a request waits IN_REVIEW for the admin (the monitor shows the reason).
   Should it publish an empty list instead, pointing the client at the directory, with a different email?
5. The fairness report counts `match_event` directly (tracking's table, read only). Move to a tracking service when
   one exists.
