# Combpatible: stylist matching app

Product name: Combpatible. 'lookmate' stays the internal name in packages, Gradle, databases, buckets, config keys and
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
  (the rule above): the repo, `com.lookmate`, Gradle, database, buckets, containers, GHCR images, `lookmate.*` config
  keys, `LOOKMATE_*` variables, the Auth0 claim namespaces `https://lookmate.app/*` and the audience
  `https://api.lookmate.app`.
- **Stack: pokemans', not Next.js + Supabase.** Spring Boot 4.1 / Java 25 / Gradle / Spring Modulith / Flyway, React 19 +
  Vite PWA, Postgres 18 with pgvector, Cloudflare R2, Auth0, Sentry, Resend. The privacy work the brief asks for
  (private photos, presigned uploads, retention job, encrypted backups) already exists there.
- **Hosting: Option A.** Its own Hetzner server running pokemans' Compose setup; same Hetzner/Cloudflare/Sentry/GHCR/
  Resend accounts, but a **separate Auth0 tenant** and **separate database**. Details: [docs/hosting.md](docs/hosting.md).
- **Scale without slowing down:** see [docs/architecture.md](docs/architecture.md). Stateless API, Postgres as the
  queue (`SKIP LOCKED`), embeddings keyed by model, `market` on every place-bound row.
- **Design (proposed):** "Chair-side" direction, flows, tokens and plan in [docs/ux/design-direction.md](docs/ux/design-direction.md).
- **Matching publishes automatically; admin monitors** (2026-11-20, Zach). Clients choose; we recommend: a ranked list
  goes to the client as soon as it's ready, clients can browse every live stylist (`/api/public/stylists`), and the
  admin watches `/admin/matches` and steps in after the fact. Review switch `lookmate.matching.review-before-publish`,
  off. This reverses the brief's Must-have 5 (admin review before the client sees matches).
- **Clients don't sign in.** Intake and the match list work through `/api/public/**` and an emailed link token.
  Stylists and the admin use Auth0; ADMIN needs MFA.

## Defaults to confirm (ask before changing; record the answer here)

- ~~Embedding provider and model~~ **Decided 2026-11-20: Amazon Bedrock, Titan Multimodal Embeddings G1** (Zach). AWS
  account in progress; threshold to set from the labelled set. See [docs/embedding-providers.md](docs/embedding-providers.md).
- Inspiration-photo retention: 30 days (`lookmate.retention.inspiration-photo-days`).
- Client contact retention: **decided 2026-11-20**: anonymised 365 days after the latest request by that email
  (`lookmate.retention.client-contact-days`). Client links expire after 90 days (`lookmate.intake.link-ttl`).
- Stylist terms text: a draft (`backend/app/src/main/resources/terms/`), needs a lawyer before the pilot.
- ~~App name~~ **Decided 2026-11-20: Combpatible** (Zach). Domain: still a placeholder, `pilot.combpatible.example` in
  `deploy/`, docs and `.claude/skills/`, until a domain is bought.
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
