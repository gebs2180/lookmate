# Module: matching

Package `com.lookmate.app.matching`. Matching v1 and the admin review (brief, Must have 4 and 5). Design:
[architecture.md](../architecture.md), "Matching v1".

## Contract with intake

- Intake creates the `client` and `match_request`, uploads the inspiration photo (`inspiration_object_key`,
  `inspiration_content_type`) and, once the photo is uploaded and checked, sets **`status = 'MATCHING'`**. Matching
  takes it from there: MATCHING → IN_REVIEW (ranked) → PUBLISHED, or FAILED if the photo can't be read for matching.
- Matching writes only these `match_request` columns: `status`, `status_reason`, `reviewed_by`, `published_at`,
  `updated_at` (`RequestTransitions`). Everything else in intake's tables it only reads (`CandidateQueries`).
- Client link tokens: SHA-256 hex of the token, as in `access_token_hash` (the same as identity's invite tokens).
  `GET /api/public/match-requests/{token}/matches` accepts the intake token or the one emailed at publish.

## Contract with stylists

Read only: `stylist_profile` (LIVE, market), `salon_location.name`, `portfolio_photo` (READY, `service`, `hair_type`,
`object_key`). Only READY photos with a hair type and the requested service are candidates; every READY photo is
embedded.

## Endpoints

Admin (ADMIN with MFA, by path):

| | |
|---|---|
| `GET /api/admin/match-requests?status=IN_REVIEW` | Queue (also MATCHING, FAILED, PUBLISHED), oldest first |
| `GET /api/admin/match-requests/{id}` | Request + quiz answers, inspiration photo (signed URL), candidates with evidence photos (signed URLs) |
| `GET /api/admin/match-requests/{id}/stylists` | LIVE stylists in the market, for adding by hand |
| `PUT /api/admin/match-requests/{id}/order` | `{matchIds}`: every match, best first → `final_rank` |
| `PUT /api/admin/match-requests/{id}/matches/{matchId}` | `{hidden, note}` |
| `POST /api/admin/match-requests/{id}/matches` | `{orgId, note}`: add a stylist (`source = 'ADMIN'`) |
| `POST /api/admin/match-requests/{id}/publish` | → PUBLISHED, final ranks 1..n over visible matches, queues MATCHES_READY; returns the client path once |
| `GET /api/admin/match-reports/fairness?market=&days=90` | Requests, no-match rate, reviewer changes, published, shown, clicked, booked, mean top score, by client hair type |

Changes need the request IN_REVIEW (409 `NOT_IN_REVIEW` otherwise). Other codes: `NO_VISIBLE_MATCHES`,
`ALREADY_MATCHED`, `STYLIST_NOT_AVAILABLE`, `BAD_ORDER`.

Public: `GET /api/public/match-requests/{token}/matches` → `{published, matches: [{matchId, rank, orgId, stylistName,
locationName, instagram, reason, photos: [{id, url}]}]}`. Only published, ranked, unhidden matches of stylists still
LIVE; no scores or reviewer notes. 404 for an unknown token. Tracking adds the booking redirect by `matchId`.

## Pipeline

`MatchingJob` runs every `lookmate.matching.worker.poll-interval` (30s): embed inspiration photos, embed portfolio
photos, rank. Each batch is claimed with `FOR UPDATE SKIP LOCKED`, so several instances share it. Rows stay locked
for the provider call, so batches are small (16). Failures back off (`embedding_attempt`, V5) and stop after
`max-attempts`; a request whose photo never embeds becomes FAILED.

Scoring (`Scoring`, pure): per stylist, `0.75 × mean(best 3 weighted similarities) + 0.25 × min(1, similar / 10)`.
A photo's weight is 1 for the client's hair type, 0.5 for a neighbouring type, 0 otherwise (`HairCompatibility`).
"Similar" means cosine ≥ 0.8. All of these are settings under `lookmate.matching.ranking`. The top 5 stylists are kept,
with their 4 best photos as evidence.

## Open questions

1. **Client link for the email.** Only intake knows the client's token (only its hash is stored). Matching mints its
   own link at publish (`match_link`, V5); both tokens open the matches. Once intake offers a way to issue a link,
   switch to it and drop `match_link`. Does intake's `/m/<token>` page use this endpoint?
2. **notify:** `Notifier` is package-private and emails users by id, so this module added a public `ClientNotifier`
   (in notify) that emails a given address. Fine to keep, or should notify own a different API?
3. Should a request rank only after all of the market's portfolio photos are embedded? Today it ranks with what's there.
4. Re-rank after a stylist adds photos (an admin "re-run" button)? Not built.
5. Rate limits on the public endpoint: none (a 256-bit token can't be guessed); add one with intake's if it has one.
