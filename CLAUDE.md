# Combpatible: stylist matching app

Product name: Combpatible. 'lookmate' stays the internal name in packages, Gradle, databases, config keys and
Auth0 identifiers. Anything outsiders see says Combpatible, including the `utm_source=combpatible` tag on booking links
(stylists see it in their booking tools' reports).

Matches hair clients with stylists by the stylist's actual portfolio work on similar hair, not just distance and price.
Columbus, Ohio first, with independent stylists at Salon Lofts. The original project brief (what we build, why, the
pilot metrics) is [docs/brief.md](docs/brief.md); read it before product decisions.

**Sibling project:** this repo reuses the stack and infrastructure accounts of `gebs2180/pokemans` (Rawmark), which
stays a separate app. Code was copied, not shared: a fix in one doesn't reach the other.

**Orchestrating?** Start with [docs/orchestrator-handoff.md](docs/orchestrator-handoff.md): state, standing instructions and next steps.

## Decisions (2026-11-20)

- **Name: Combpatible** (Zach, 2026-11-20), replacing the working name LookMate. Internal identifiers keep `lookmate`
  (the rule above): the repo, `com.lookmate`, Gradle, database, containers, GHCR images, `lookmate.*` config
  keys, `LOOKMATE_*` variables and the Auth0 claim namespaces `https://lookmate.app/*`. The exceptions, all named before this
  rule could apply: the Auth0 API audience, **`https://api.combpatible.app`** (the dev tenant's API was created with it,
  and an API identifier can't be changed), and the R2 buckets **`combpatible-photos`** and **`combpatible-backup`**
  (Zach, 2026-11-20).
- **Stack: pokemans', not Next.js + Supabase.** Spring Boot 4.1 / Java 25 / Gradle / Spring Modulith / Flyway, React 19 +
  Vite PWA, Postgres 18 with pgvector, Cloudflare R2, Auth0, Sentry, Resend. The privacy work the brief asks for
  (private photos, presigned uploads, retention job, encrypted backups) already exists there.
- **Hosting: Option A.** Its own Hetzner server running pokemans' Compose setup; same Hetzner/Cloudflare/Sentry/GHCR/
  Resend accounts, but a **separate Auth0 tenant** and **separate database**. Details: [docs/hosting.md](docs/hosting.md).
- **Scale without slowing down:** see [docs/architecture.md](docs/architecture.md). Stateless API, Postgres as the
  queue (`SKIP LOCKED`), embeddings keyed by model, `market` on every place-bound row.
- **Design: Lookbook with Strand motion** (Zach, 2026-11-20). Paper and oxblood, Instrument Serif over Inter Tight,
  photos as the hero, and the Strand (one hair that draws itself and morphs between the four hair types) as the
  signature motion. Spec and prototypes: [docs/ux/redesign-options.md](docs/ux/redesign-options.md); live tokens in
  `web/src/index.css`, Lookbook styles in `web/src/lookbook.css`. The principles, tone, accessibility and inclusivity
  sections of [docs/ux/design-direction.md](docs/ux/design-direction.md) still stand; its type and colour don't.
- **Matching publishes automatically; admin monitors** (2026-11-20, Zach). Clients choose; we recommend: a ranked list
  goes to the client as soon as it's ready, clients can browse every live stylist (`/api/public/stylists`), and the
  admin watches `/admin/matches` and steps in after the fact. Review switch `lookmate.matching.review-before-publish`,
  off. This reverses the brief's Must-have 5 (admin review before the client sees matches). A request with no
  candidates is published empty at once: the client sees "No close matches yet" with a link to every stylist and gets
  a "no close match" email; the monitor keeps the reason and the admin can add picks afterwards.
- **Clients don't sign in.** Intake and the match list work through `/api/public/**` and a link token in the page's
  URL. Stylists and the admin use Auth0; ADMIN needs MFA. **Exception (Zach, 2026-11-20):** MFA is off on the
  pilot server while it uses the dev tenant (`LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false` in its `.env`); turn it back on
  before real stylists join.
- **Client email is optional and asked after the reveal** (2026-11-20, Zach). Asking for an email before showing
  anything costs clients at the last step; the results page is the link, and "Email me this list" (or "Tell me when
  picks are added") sets it. No email means no emails, ever. Limits are per IP, not per address.
  See [docs/modules/intake.md](docs/modules/intake.md).

- **What to expect in the chair: profile labels and from-prices** (Zach, 2026-11-20). Stylists may tick gender-neutral
  pricing, LGBTQ+-affirming, sensory-friendly on request, kids welcome and languages, and give a from-price per service
  and whether they offer a consultation. All optional. **Profile only:** no filters, nothing in ranking or matching. No
  political or identity labels. Quiet appointments are the client's choice, not a stylist label. See
  [docs/modules/stylists.md](docs/modules/stylists.md) and [docs/research/beyond-the-hair.md](docs/research/beyond-the-hair.md).
- **Service finder: "Not sure what to book?"** (Zach, 2026-11-22). A quiz that ends in the service to book or "book a
  consultation first". It is driven by rules a partner stylist writes and tests, never by AI. It's a separate `consult`
  module that no other module depends on, so it can break out later. Pilot: the partner's own link plus the general
  Combpatible button, both on the partner's ruleset, with Salon Lofts stylists only. The partner owns their rules and
  Combpatible licenses them, in a one-page agreement. Answers go to the stylist only if the client ticks the box.
  Charging stylists a membership is allowed later, at about 10 weekly users; "no payments" still covers clients. See
  [docs/research/service-finder.md](docs/research/service-finder.md).

## Defaults to confirm (ask before changing; record the answer here)

- ~~Embedding provider and model~~ **Decided 2026-11-20: Amazon Bedrock, Titan Multimodal Embeddings G1** (Zach). AWS
  account in progress; threshold to set from the labelled set. See [docs/embedding-providers.md](docs/embedding-providers.md).
- Inspiration-photo retention: 30 days (`lookmate.retention.inspiration-photo-days`).
- Client contact retention: **decided 2026-11-20**: anonymised 365 days after the latest request by that email
  (`lookmate.retention.client-contact-days`). Client links expire after 90 days (`lookmate.intake.link-ttl`).
- Stylist terms text: a draft (`backend/app/src/main/resources/terms/`), needs a lawyer before the pilot.
- ~~App name~~ **Decided 2026-11-20: Combpatible** (Zach). Domain **decided 2026-11-20: `combpatible.app`**
  (Zach); `combpatible.com` isn't bought. The hosted site uses the Auth0 dev tenant until real stylists join.
- Tagged photos a stylist needs before going live: 3 (`lookmate.stylists.min-tagged-photos-to-publish`).
- Salon locations: entered by Zach on `/admin/locations`, not seeded (2026-11-20).
- Demo stylists: made-up profiles for the proof of concept, created at `/admin/demo` and hidden all at once when real
  stylists join (2026-11-20). See [docs/modules/stylists.md](docs/modules/stylists.md).

## Working here

- Backend: `cd backend && ./gradlew test`. Web: `cd web && npm test && npm run lint && npm run build`. Docker must be
  running or the Testcontainers tests are skipped (CI fails instead). See [docs/local-dev.md](docs/local-dev.md).
- API changes: `./gradlew test -Dopenapi.update=true`, then `npm run api:types` in `web/`. CI checks both.
- Schema: Flyway migrations in `backend/app/src/main/resources/db/migration`. Never edit a merged one; add `V<n>`.
- Modules (`identity`, `storage`, `notify`, then `stylists`, `intake`, `matching`, `tracking`) talk through public
  services only; `ModularityTest` enforces it.
- When starting parallel sessions, name the shared files and the merge order in each brief, and keep each to one module.

## Non-negotiables

- **Client photos are private.** No public URLs, ever: presigned links with short lifetimes. Location metadata is stripped
  at upload. Inspiration photos are deleted on schedule.
- **Recommendations are suggestions.** Clients can browse and book any stylist.
- **No calendar, payments or messaging.** "Book" hands off to the stylist's own booking link, tracked.
- **Measure fairness across hair types.** Matching quality on curly and coily hair is checked, not assumed.
- Personal accounts and infrastructure only.

## Compact instructions

Keep PR numbers, open decisions, file paths and test status.
