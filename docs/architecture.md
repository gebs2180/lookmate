# Architecture

Fast to change for a 40-stylist pilot, with no decision that has to be undone to reach thousands of stylists in
several cities. Stack and infrastructure are pokemans' (see [hosting.md](hosting.md)); this file is what's specific
to LookMate.

## Shape

One Spring Boot app (a modular monolith, boundaries checked by Spring Modulith's `ModularityTest`), one Postgres with
pgvector, one private photo bucket, one React PWA. Clients never sign in; stylists and the admin do (Auth0).

| Module | Owns | Status |
|---|---|---|
| `identity` | users, stylist orgs, invites, memberships, terms | From pokemans |
| `storage` | `BlobStore` (presigned uploads, never overwrite), image checks | From pokemans |
| `notify` | email outbox, Resend, retention | From pokemans |
| `stylists` | `salon_location`, `stylist_profile`, `portfolio_photo` and tags | To build |
| `intake` | `client`, `match_request`, inspiration upload, `/api/public/match-requests/**` | Built ([modules/intake.md](modules/intake.md)) |
| `matching` | embeddings, ranking, `match`, admin review | To build |
| `tracking` | `match_event`, booking redirect, metrics | To build |

The whole Phase 1 schema is in `V2__matching_domain.sql`, so modules can be built in parallel without competing
migrations. Each module reads another's data only through that module's public service, never its repositories.

**Sanctioned exception (tracking):** `tracking/TrackingQueries` reads `stylist_profile`, `portfolio_photo`, `client`,
`match_request` and `match` with read-only SQL, because tracking was built in parallel with the modules that own them
and the metrics are aggregate SQL across the whole schema. Nothing in tracking writes those tables; its tests seed them
with SQL fixtures. The redirect and manual-entry lookups can move behind the owners' services once they exist; the
metrics stay reporting SQL. See [tracking.md](tracking.md).

## Matching v1

1. **Embed** each READY portfolio photo and each inspiration photo through an `EmbeddingProvider` interface (a hosted
   image model; a deterministic fake for tests). Work runs off the request path: a scheduled worker picks rows with no
   embedding for the current model (`FOR UPDATE SKIP LOCKED`, the email outbox's pattern), so uploads stay fast and a
   provider outage only delays matching.
2. **Filter** to LIVE stylists in the request's market whose READY photos match the requested service and a compatible
   hair type (exact type first; neighbouring types at a lower weight).
3. **Rank** stylists, not photos: a stylist's score combines their best few photo similarities (cosine distance in
   pgvector) with how many similar photos they have, so one lucky photo doesn't win. Keep the top 3-5 and the evidence
   photos, and write a reason ("12 similar cuts on wavy hair").
4. **Review:** the request goes to IN_REVIEW; the admin reorders, hides or adds stylists, then publishes. The client is
   emailed a link (`/m/<token>`) to the ranked list.

Module details, endpoints and open questions: [modules/matching.md](modules/matching.md). Provider choice:
[embedding-providers.md](embedding-providers.md).

**The one sanctioned cross-module exception (Phase 1 build):** `matching/CandidateQueries` reads the stylists module's
V2 tables (`stylist_profile`, `salon_location`, `portfolio_photo`) with read-only SQL, because that module was built in
parallel with matching, plus a few reads of intake's (the inspiration key, a row lock while reviewing, the fairness
counts). It moves onto `Stylists` and `MatchRequests` once they are merged. Request status changes and publishing
already go through intake's `MatchRequests`.

Quality across hair types (CLAUDE.md, Privacy) is measured, not assumed: an admin report of match acceptance by hair
type, and a small labelled set of textured-hair looks checked before each model change.

## How it scales

| Concern | Pilot (now) | When it grows | What makes that a change, not a rewrite |
|---|---|---|---|
| App servers | One container on one VPS | Several containers behind a load balancer | The API is stateless (bearer tokens, no sessions); scheduled jobs use `SKIP LOCKED`, so two instances never take the same row |
| Database | Postgres in Compose | Managed Postgres with pgvector (Neon, Supabase, RDS, ...) | Plain Flyway SQL; nothing tied to the host |
| Vector search | Exact scan (fast below ~100k photos) | HNSW index per model | Embeddings are in their own tables keyed by model; the index is a migration |
| Model changes | One model | Re-embed alongside the old model, switch when complete | `model` is part of the embedding key |
| Embedding cost/latency | Hosted API, async | Batch calls, or a self-hosted Python worker | Behind `EmbeddingProvider` |
| Photos | R2, signed URLs | Same; add thumbnails and a CDN in front | R2 has no egress fees; keys are never public |
| New cities, salon brands | `columbus-oh`, Salon Lofts | More markets and brands | `market` on every place-bound row; `brand` is data |
| Booking | Link-out with UTM tags | Platform webhooks or APIs for confirmed bookings | `match_event` already has BOOKED/REBOOKED with a source |
| Metrics | Queries over `match_event` | A warehouse fed from the same append-only table | Events are append-only, so they can be copied safely |
| Rate limits on `/api/public/**` | In memory, per IP (and per email for intake's create), one instance | Postgres-backed counters: a fixed-window table (`key`, `window_start`, `count`) with `insert ... on conflict do update set count = count + 1 returning count`, old windows deleted by the retention job | Every check goes through `api/PublicRateLimitFilter`, so only its bucket store changes. Until then a second instance multiplies every budget by the instance count |

**Client addresses.** Rate limits key on the client IP. In production Tomcat takes it from `X-Forwarded-For` only
when the connection comes from Caddy (`server.forward-headers-strategy: native`, `server.tomcat.remoteip.internal-proxies`,
Docker's private ranges; override with `TRUSTED_PROXIES`). Caddy replaces any `X-Forwarded-For` a client sends, and
the backend publishes no port. If a CDN or load balancer is ever put in front of Caddy, set Caddy's `trusted_proxies`
to it, or every client will share the CDN's few addresses and one budget.

Not built for the pilot, on purpose: multi-region, a queue service (Postgres is the queue until it hurts), caching,
read replicas. Each slots in behind the points above.
