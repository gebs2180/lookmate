# Orchestrator handoff (2026-11-23)

For an orchestrator session running on Zach's computer in the local `lookmate` clone. Read [CLAUDE.md](../CLAUDE.md)
first; it holds the decisions and rules. This file holds the standing instructions, the state and what's next.

**First thing to do:** `git pull origin main`, read [review-2026-11-22.md](review-2026-11-22.md) (its "Fix groups"
section) and the service finder doc below, then ask Zach what is next.

## Standing instructions from Zach

- **Auto-merge every PR you open** (merge commit). `main` requires the `backend` and `web` checks. If a PR's checks
  already passed, GitHub refuses to queue auto-merge, so merge it directly, pinned to the tested head SHA.
- **Dependabot PRs:** turn on auto-merge for any open one whenever you're active. There's deliberately **no** workflow
  that does this automatically (Zach chose option 1, 2026-11-20).
- **Orchestrate:** for bigger work, start separate sessions, one module each. Name the shared files and the merge order
  in each brief, have them push a branch without opening a PR, and open the PR yourself with auto-merge on.
- **Product name is Combpatible**; `lookmate` stays internal (CLAUDE.md, top). Outsider-visible text says
  Combpatible, including `utm_source=combpatible`.
- Ask before changing a "default to confirm" in CLAUDE.md, and record the answer there.

## Where things are

`main` has everything below merged. Open PRs are only Dependabot bumps. The latest Flyway migration is `V8`
(`backend/app/src/main/resources/db/migration`). What is running in production is not recorded here: check it with
the deploy-prod skill's read-only check (`deploy/prod-check.sh`, [hosting.md](hosting.md#day-to-day)).

| PR | What |
|---|---|
| #1 to #7 | Phase 1 (the scaffold plus stylists, intake, matching, tracking), the UX design, auto-publish matching, the rename to Combpatible, `utm_source=combpatible` |
| #28, #29 | The haircut animation while matching (web). **Merged, but waiting for Zach to deploy.** |
| #30 | The codebase review, [review-2026-11-22.md](review-2026-11-22.md) |
| #35, #32, #44, #46, #31, #47 | Review fix groups A (intake), B (deploy and CI), C (matching), D (stylists and storage), E (notify, tracking, identity), F (web). All merged. |
| This PR | Group G, the docs |
| #42, #43, #45 | Service finder: research, Zach's decisions, and the spreadsheet template |

**Tests:** CI runs the backend and web checks on every PR and on pushes to `main`; read the latest run for the counts
rather than trusting a number here. The images workflow (`.github/workflows/images.yml`) needs the Auth0 repository
variables (`AUTH0_DOMAIN`, `AUTH0_SPA_CLIENT_ID`, `AUTH0_AUDIENCE`) to be set.

**Deferred by Zach:** review finding #22 (Auth0 tokens in `localStorage`), until real stylists join; see the list below.

## In progress

- **Service finder** ("Not sure what to book?"): built (`consult` module and web) and live at `/consult` and
  `/c/partner` since the 2026-11-24 deploy, on our example ruleset ([modules/consult.md](modules/consult.md)). Jason's
  filled-in sheet loads through the admin upload, with no code change. Still open: the partner agreement (Zach), and
  emailing the stylist the answers the client chooses to share (a TODO in `ConsultService.book`).
- **Feature ideas** ([research/feature-ideas.md](research/feature-ideas.md), decided 2026-11-25): build ideas 1–4, 6
  and 7; idea 5 dropped. Sessions, one module area each, merging in this order:
  1. `stylists`: first-visit fields and notes (idea 1) and "taking new clients" (idea 3, never affects matching).
     Migration V13.
  2. `tracking` + `notify`: weekly digest with one-tap Booked/Rebooked (idea 2). Migration V14. Runs alongside 1.
  3. After 1: public stylist page `/s/<slug>` (idea 4) and exact-duplicate photo refusal (idea 7, SHA part).
  4. After 2: "did you get the look?" check-in for all clients (idea 6).
  5. After real embeddings: near-duplicate photo queue (idea 7).
  Generic first-visit notes: our draft in [research/first-visit-notes.md](research/first-visit-notes.md) until Jason's.
- **Deploy:** PRs #28 and #29 and everything after them are on `main` but not necessarily on the server. Zach deploys
  ([hosting.md](hosting.md#day-to-day)); don't deploy for him.
- **Pilot server:** it uses the Auth0 dev tenant with admin MFA off (`LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false`), by
  Zach's decision of 2026-11-20 ([auth.md](auth.md)). Auth0 setup steps are in [auth.md](auth.md) and
  [local-dev.md](local-dev.md). If any Combpatible objects were added to Rawmark's Auth0 tenant, remove them; Rawmark's
  own tenant settings are outside this repo.

## Before real stylists join

- Admin MFA back on: remove `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false` from the server's `.env`.
- Move from the Auth0 dev tenant to a production tenant.
- Move Auth0 tokens out of `localStorage` into memory, with an Auth0 custom domain (`web/src/AuthProvider.tsx`;
  [review-2026-11-22.md](review-2026-11-22.md) #22, deferred by Zach on 2026-11-22).
- Hide the demo stylists.
- A lawyer reviews the stylist terms.

## Waiting on Zach

1. **AWS account** for Amazon Bedrock Titan Multimodal Embeddings G1 (decided). Then wire a real `EmbeddingProvider`
   ([embedding-providers.md](embedding-providers.md)) and set `MATCHING_PROVIDER`. Until then real matching doesn't work.
2. **A labelled textured-hair set:** 20–30 looks, each paired with the portfolio photos a stylist would call a match.
   It sets the similarity threshold (now a 0.8 placeholder) and checks Titan against Cohere v4 on curly and coily hair.
3. **Production Auth0 tenant** (a third account or a paid plan; admin MFA on there) before real stylists join. The pilot
   server itself ([hosting.md](hosting.md), Option A) is already up.
4. **Domain:** decided 2026-11-20, `combpatible.app` only (not `.com`), bought by Zach at Cloudflare
   Registrar. No trademark on "Combpatible" turned up in a web search; a USPTO search or the lawyer should confirm.
5. **A lawyer reviews the stylist terms** (`backend/app/src/main/resources/terms/`, still a draft).

## Next build work (not started)

- **Lookbook follow-ups** (redesign is gebs2180/lookmate#14): a client note field for the reveal's pull quote (now
  built from quiz answers), a single-stylist endpoint for the portfolio dialog, dark mode, install icons, and View
  Transitions between routes.
- **Module boundaries:** matching still reads the stylists and intake tables with read-only SQL (the documented
  exception in [architecture.md](architecture.md)); the follow-ups are listed in
  [modules/matching.md](modules/matching.md). Tracking has a similar documented exception.
- **Deferred:**
  - a reviewer or monitor warning when some of a market's photos aren't embedded yet;
  - an HNSW index once the vector dimension is fixed by Titan (1024);
  - a shared (not per-instance) rate limit, needed only with a second app container.

## Environment notes

- **Local:** Docker Desktop, JDK 25 and Node 24. On Windows use `.\gradlew.bat` and `npm.cmd`.
- **Cloud sessions:** use the `lookmate` cloud environment. It allows `cgr.dev` for the MinIO test image, and its setup
  script installs JDK 25. In a fresh cloud container, start Docker with `dockerd` before running the backend tests.
- **GitHub:** use the GitHub tools for PRs. From cloud sessions, `gh` is for search only.
