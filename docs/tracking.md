# Tracking: booking handoff and pilot metrics

The `tracking` module (`com.lookmate.app.tracking`) covers brief items 6 and 7: "Book" hands off to the stylist's own
booking link with UTM tags, clicks are recorded, bookings and rebooks are entered by hand, and the admin gets the pilot
table. Everything it records goes to `match_event` (V2), which is append-only: a database trigger refuses updates, so
a correction is a new row (a `VOIDED` event, below).

## Decisions (Zach, 2026-11-20)

- Wrong entries are undone by voiding (append-only `VOIDED` rows), not edits or deletes.
- Days and weeks are Eastern time (`America/New_York`, so EST/EDT) for now, for every market.
- Stylists see their matched clients' first names (never email or phone).
- Only LIVE stylists are linked to: a match to a stylist who is no longer LIVE doesn't redirect. (Matching should only
  match active stylists, LIVE with a READY photo, in the first place.)
- The `match_request (published_at)` and READY-photo indexes are left to the intake and stylists modules.

## Endpoints

| Who | Method and path | What it does |
|---|---|---|
| Client (link token) | `GET /api/public/book/{token}/{matchId}` | Records `CLICKED_BOOK` and answers `302` to the stylist's `booking_url` with `utm_source=combpatible&utm_medium=referral&utm_campaign=<market>&utm_content=<matchId>` |
| Client (link token) | `GET /api/public/book-stylist/{token}/{orgId}` | Book on a stylist picked from the directory: records `CLICKED_BOOK` with `match_id` null and the stylist's `org_id`, attributed to the client's request (any status but CLOSED), and answers the same `302` with `utm_content=directory` |
| Client (link token) | `POST /api/public/match-requests/{token}/viewed` | Records `VIEWED` (whole list, `match_id` null), at most once per request per day, and tells intake (`clientViewedMatches`), which cancels a held "matches ready" email. `204` |
| Admin | `GET /api/admin/metrics?market=&from=&to=` | The pilot table, weekly series, per-stylist and per-hair-type breakdowns |
| Admin | `GET /api/admin/tracking/matches?market=&q=` | The 50 most recently published visible matches, to pick one for manual entry |
| Admin | `POST /api/admin/matches/{matchId}/events` | `{type: BOOKED\|REBOOKED, note?, occurredAt?}`, `source = ADMIN` |
| Stylist (own org) | `POST /api/orgs/{orgId}/matches/{matchId}/events` | Same body; `OrgAccess.require(orgId, STYLIST)`, and the match must be this org's. `source = STYLIST` (or `ADMIN` if the admin uses the route) |
| Stylist (own org) | `GET /api/orgs/{orgId}/tracking/stats` | Matches shown, clicks, bookings, rebooks: last 30 days and all time |
| Stylist (own org) | `GET /api/orgs/{orgId}/tracking/matches` | Their 50 most recent published matches: client first name, service, counts |
| Admin | `GET /api/admin/tracking/directory-clicks?market=&q=` | The 50 latest stylists clients picked from the directory (per request and stylist): clicks, whether that stylist was also on the client's published list, and the directory booking if recorded |
| Admin | `POST /api/admin/match-requests/{requestId}/stylists/{orgId}/events` | `{type: BOOKED\|REBOOKED, note?, occurredAt?}` for a directory pick (no match), `source = ADMIN`. Same rules as below, per request and stylist |
| Admin | `GET /api/admin/matches/{matchId}/events` | The match's events, newest first, voided ones marked with the reason |
| Stylist (own org) | `GET /api/orgs/{orgId}/matches/{matchId}/events` | The same, for their own match |
| Admin | `POST /api/admin/events/{eventId}/void` | `{reason}`: voids any event |
| Stylist (own org) | `POST /api/orgs/{orgId}/events/{eventId}/void` | `{reason}`: voids a BOOKED or REBOOKED on their own match or directory pick |
| Stylist (own org) | `GET /api/orgs/{orgId}/tracking/directory-picks` | The 50 latest clients who picked them from the directory: first name, service, clicks, the booking if recorded. No email |
| Stylist (own org) | `POST /api/orgs/{orgId}/match-requests/{requestId}/events` | `{type: BOOKED\|REBOOKED, note?, occurredAt?}` for a directory pick. `404` unless that client tapped Book on this org from the directory. `source = STYLIST` (or `ADMIN`) |
| Stylist (signed in, link's org) | `GET /api/digest-links/{token}` | What a weekly-digest Booked/Rebooked link is about: org, client first name, service, week, directory pick or not, used/expired, the recorded event. `404` for anyone not in the link's org |
| Stylist (signed in, link's org) | `POST /api/digest-links/{token}` | `{type: BOOKED\|REBOOKED, occurredAt?}`: records it once. `201`; `409 LINK_USED`, `410 LINK_EXPIRED`, plus the manual-entry codes |
| Stylist (own org) | `GET`/`PUT /api/orgs/{orgId}/digest` | `{weeklyDigest}`: the signed-in member's switch for the Monday email |
| Anyone with the link | `POST /api/public/digest/unsubscribe/{token}` | Turns the digest off for the member it was sent to. `204`; `404` for an unknown or expired link |

Web: `/admin/metrics` (dashboard), `/admin/bookings` (record or void a booking), `/orgs/:orgId/bookings` (the same
for a stylist's own matches and directory picks), `/digest/:token?type=` (confirm a digest link, signed in),
`/digest/unsubscribe/:token` (public), `/orgs/:orgId/stats` (the stylist's stats card as a page; `StylistStatsCard` is exported
for the stylist home page to embed).

### The redirect

- The token must open the request through intake (`MatchRequests.forToken`: any unexpired link, the quiz's or the one in
  the "matches ready" email); the match must belong to that request; the request must be `PUBLISHED`; the match must have a `final_rank` and not be
  `hidden`; the stylist's profile must be `LIVE`. Any failure is the same `404`, so nothing is confirmed to a guesser.
- **No open redirect:** only an absolute `https` URL with a host and no user info is followed (`BookingLinks`). `http`,
  `javascript:`, scheme-relative, relative and malformed links are a `404` and record nothing.
- The stylist's own query parameters and fragment are kept; any `utm_source/medium/campaign/content` already in the
  link is replaced, so the booking platform sees one value each.
- The response is `Cache-Control: no-store` and `Referrer-Policy: no-referrer`, so the client's token in the URL never
  reaches the booking site.
- **Directory Book** (`/book-stylist/{token}/{orgId}`): the stylist must be LIVE (`Stylists.liveProfile`) and their
  link pass the same https check; otherwise `404` and nothing recorded. There is no tokenless Book: a client who hasn't
  made a request is sent to `/find` first (docs/modules/stylists.md), so every click is attributed and no redirect is
  open. Dedupe is per request and stylist; the daily cap is shared with match clicks.
- A second click on the same match within `lookmate.tracking.click-dedupe-window` (30s) still redirects but isn't
  recorded again. At most `max-clicks-per-request-per-day` (20) clicks are counted per client link per Eastern day;
  more still redirect. Both checks and the insert run under a transaction-scoped advisory lock on the request id.

### Manual entry rules

- Only `BOOKED` and `REBOOKED`; the match's request must have been published (`published_at` set).
- `occurredAt` defaults to now; more than 5 minutes in the future is `400 OCCURRED_IN_FUTURE`; before the request was
  made is `400 OCCURRED_BEFORE_REQUEST`.
- A second `BOOKED` on the same match is `409 ALREADY_BOOKED` (a return visit is a `REBOOKED`).
- A `REBOOKED` needs an earlier `BOOKED` by the same client with the same stylist (through any of the client's
  matches), else `409 REBOOK_WITHOUT_BOOKING`.

### Voiding

- A `VOIDED` row points at the event it undoes (`voids_event_id`, unique, so once only) and must carry the reason in
  `note`. The original row stays. Every count reads the `match_event_live` view (V6), which leaves out `VOIDED` rows and
  the rows they void, so a voided booking can be recorded again.
- The admin can void any event (a test click, say); a stylist only BOOKED and REBOOKED on their own org's matches.
- A BOOKED that a later REBOOKED (same client, same stylist) depends on can't be voided while that rebook stands:
  `409 BOOKING_HAS_REBOOKS`. Voiding twice is `409 ALREADY_VOIDED`. A VOIDED row can't be voided; re-record instead.

## Weekly digest (idea 2, 2026-11-25)

Every Monday each member of a LIVE, non-demo stylist org gets an email about the week before (Monday to Sunday in
`lookmate.tracking.time-zone`): "Last week Combpatible showed you to N clients; M tapped Book: Sam and Dana", one line
of 30-day numbers, and per client who tapped a **Booked** and a **Rebooked** button (only Rebooked once a booking is
recorded). Clients appear by first name only: never contact details or photos.

- **Who counts.** "Showed you to": distinct requests where the org was on the published list (ranked, not hidden,
  published in the week) or was picked from the directory (a directory `CLICKED_BOOK` in the week). "Tapped Book":
  requests with a `CLICKED_BOOK` on the org in the week, from the list or the directory. A week with neither sends
  nothing.
- **Queue.** `stylist_digest` (V14), one row per org per week (unique), so planning is idempotent. `WeeklyDigestJob`
  runs every `poll-interval` (10 min): on Mondays from `send-hour` (8) local time it adds the missing rows, then
  sends PENDING ones claimed with `FOR UPDATE SKIP LOCKED`, one transaction each. A failure is retried on the next run
  and the digest is FAILED after `max-attempts` (3). Each member's email goes through the notify outbox with dedupe
  key `stylist-digest:<digestId>:<userId>` (`StylistEmails.weeklyDigest`, template `STYLIST_DIGEST`), so a resend
  never doubles up.
- **Links.** `stylist_digest_link`: 32 random bytes, only the SHA-256 stored, one per client per member, plus one
  unsubscribe link per email. A Booked/Rebooked link lasts `link-ttl` (14 days), works once and needs the stylist's
  Auth0 sign-in as a member of the link's org, so a forwarded email can't record anything. Opening it records nothing:
  the page asks to confirm. It records through the same paths as manual entry (`recordBooking` on the match, or
  `recordDirectoryBooking` for a directory pick), so the same rules apply and the event is an ordinary `BOOKED` or
  `REBOOKED` with `source = STYLIST`, which the client check-in (idea 6) can key on. The confirm page offers Undo
  (the normal void). Links are deleted 30 days after they expire.
- **Opt-out.** Per member: the switch on the stylist home page, or the email's unsubscribe link (`unsubscribe-ttl`,
  90 days; a button, so a mail scanner opening the link changes nothing). Both write `stylist_digest_opt_out`.
- **Config.** `lookmate.tracking.digest.*`: `enabled` (env `STYLIST_DIGEST_ENABLED`, default true; false stops the
  job), `send-hour`, `poll-interval`, `link-ttl`, `unsubscribe-ttl`, `max-attempts`, `max-clients` (20 per email).
- **Not yet:** the "Still taking new clients? Confirm" line (shown when the status is older than 30 days) waits for
  `stylists` to have the status and a public confirm method; see docs/modules/stylists.md, "Follow-ups".
- Tests: `WeeklyDigestTest` (one digest per week, first names only, single-use and member-only links, expiry and
  clean-up, both opt-outs, the stylist directory route).

## Metric definitions

Voided events count nowhere. Range: `from`..`to` are inclusive dates in `lookmate.tracking.time-zone` (America/New_York); default the last 84 days
(12 weeks), at most two years. `market` empty means all markets; events are filtered by their request's market.

| Metric | Definition |
|---|---|
| Stylists live | `stylist_profile.status = 'LIVE'` in the market. A snapshot of now, not range-filtered |
| **Stylists active** | Live stylists with at least one `READY` portfolio photo. Snapshot of now |
| Requests published | `match_request` rows with `published_at` in the range (whatever their status since) |
| **Clients matched** | Distinct `client_id` over those requests. One `client` row per request and the email is optional, so this counts requests, not people |
| **Booking clicks** | `CLICKED_BOOK` events with `occurred_at` in the range (already deduped at write); plus distinct clients |
| **Confirmed bookings** | `BOOKED` events in the range; "clients who booked" is the distinct clients (the brief's "matched clients who book", target 100+) |
| **Rebooks** | `REBOOKED` events in the range; plus distinct clients |
| **Rebook rate** | Cohort: clients whose first `BOOKED` with a stylist falls in the range; the share of them with a `REBOOKED` with that same stylist after that booking, at any time (also after the range). Null when the cohort is empty. Target 50%+ |
| Weekly series | Per week (Monday start, in the tracking zone): requests published, clients matched, clicks, bookings, rebooks. Weeks with nothing are zeros |
| Per stylist | Every LIVE stylist in the market plus any stylist with activity: ready photos (now), matches shown (visible, ranked matches on requests published in the range), clicks, bookings, rebooks, distinct clients booked/rebooked (events in the range) |
| By hair type | Clients whose request was published in the range, by `client.hair_type`, and how many of those requests led to a click or a booking at any time. The fairness check (CLAUDE.md) |
| Stylist stats | For one org: matches shown (by publish time), clicks, bookings, rebooks (by event time), last 30 days and all time |

| Clicks and bookings by source | `clicksBySource` and `bookingsBySource` in the totals: **recommended** (the event is on a match), **directory** (no match: picked from the directory), and **directoryAlsoRecommended** (of the directory ones, those where the same stylist was on that client's published list, ranked and not hidden). Range by event time |

The exact numbers are pinned by `MetricsTest` on a hand-counted fixture.

## Queries and indexes

All SQL is in `TrackingQueries` (reads) and `MatchEvents` (writes to `match_event`). Correct first; at pilot volume
every query is a small scan. What supports them:

- `match_event_type_time_idx (type, occurred_at)` (V2): event totals and the weekly series by range.
- `match_event_org_idx (org_id, type, occurred_at)` (V2): per-stylist stats.
- `match_event_match_idx (match_id, type, occurred_at)` (V6): click dedupe, "already booked?", the admin match list.
- `match_event_request_idx (match_request_id, type, occurred_at)` (V6): VIEWED once a day, the rebook-rate and
  hair-type joins from requests to events.
- `match_request.access_token_hash` is unique (V2): the token lookup.
- Not indexed yet: `match_request (published_at)` (requests in a range) and `portfolio_photo (org_id) where status =
  'READY'`. Those tables belong to intake and stylists; add the indexes there when the dashboard gets slow, or move the
  metrics to a warehouse fed from the append-only `match_event` (docs/architecture.md).

## Not done here

- **Rate limits** are intake's `PublicRateLimit`, in memory per IP (so per instance; a second app container needs a
  shared limit in Caddy or Postgres). Both Book routes (`/api/public/book/**` and `/api/public/book-stylist/**`) share
  the directory's `browse-per-minute` bucket (60); `/viewed` falls under intake's `reads-per-minute`. The click dedupe and the daily cap
  still keep repeated clicks from skewing the metrics.
