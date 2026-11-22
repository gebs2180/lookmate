# Orchestrator handoff (2026-11-20)

For the new orchestrator session, which runs on Zach's computer in the local `lookmate` clone. It takes over from the
cloud orchestrator session that built Phase 1. Read [CLAUDE.md](../CLAUDE.md) first; it holds the decisions and rules.
This file holds the state, the standing instructions and what's next.

**First thing to do:** `git pull origin main`, then help Zach finish the local Auth0 setup ([auth.md](auth.md),
[local-dev.md](local-dev.md)), run the app, and walk the smoke test below.

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

`main` at `a361322` has everything below merged. No PRs are open.

| PR | What |
|---|---|
| #1 | Phase 1: the scaffold (pokemans' stack) plus the stylists, intake, matching and tracking modules |
| #2, #3 | Dependabot: setup-java, AWS SDK |
| #4 | UX design: [docs/ux/](ux/) (direction, flows, tokens, implementation plan) and the prototype at https://claude.ai/artifact/74ApqgXdGU52ULhyr6Cvfm |
| #5 | Matches publish automatically; a public stylist directory; admin as a monitor; the review switch, off by default |
| #6 | Rename to Combpatible; auth and local-dev docs rewritten for the current setup |
| #7 | `utm_source=combpatible` |

**Tests:** on `main`, the backend passes 176 tests and the web app passes 57, and lint and build are clean. CI runs on PRs and on pushes to
`main`. The image build (`.github/workflows/images.yml`) fails on `main` until the Auth0 repository variables
(`AUTH0_DOMAIN`, `AUTH0_SPA_CLIENT_ID`, `AUTH0_AUDIENCE`) are set. That's expected until deployment.

**Stale branches** on GitHub, all merged: `claude/lookmate-{stylists,intake,matching,tracking,auto-publish,ux-design}`,
`claude/combpatible-rename` and `claude/pensive-cerf-u7ya5b` (the old orchestrator's branch). Delete them if Zach
agrees. `claude/tender-hawking-c4yoo8` wasn't created by the old orchestrator: ask Zach before touching it.

## In progress: local setup (Zach)

- **Auth0:** the free plan allows one tenant per account, and Rawmark's account already has one. LookMate gets a **second
  Auth0 account** (a different email) with a `lookmate-dev` tenant. Local dev runs **without MFA**: the Action sets the MFA
  claim to false, and the backend runs with `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false`. Steps: [auth.md](auth.md).
- **Clean up Rawmark's tenant:** if any LookMate objects were added there (an Action in its post-login flow, an API or an
  app), remove them. Rawmark's tenant also shows a **"Pro MFA exceeded"** warning, from its own admin MFA. That's Zach's
  call, in the pokemans repo, and outside this repo.
- **Smoke test, once logged in as ADMIN:**
  1. Add a location at `/admin/locations`.
  2. Create demo stylists at `/admin/demo`, each with 3 or more tagged photos, then publish.
  3. In a private window, go to `/find` and use **a demo stylist's own photo** as the inspiration. The fake matcher only
     matches identical images.
  4. Results appear in place. Check **See all stylists** and **Book** (a tracked redirect).
  5. Check `/admin/matches` and the metrics.

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
3. **Infrastructure** ([hosting.md](hosting.md), Option A): a Hetzner server, R2 buckets, a Sentry project, a production
   Auth0 tenant (a third account or a paid plan, and decide on admin MFA there), health checks, and the GitHub
   repository variables for Auth0.
4. **Domain:** decided 2026-11-20, `combpatible.app` only (not `.com`), bought by Zach at Cloudflare
   Registrar. No trademark on "Combpatible" turned up in a web search; a USPTO search or the lawyer should confirm.
5. **A lawyer reviews the stylist terms** (`backend/app/src/main/resources/terms/`, still a draft).

## Next build work (not started)

- **Lookbook follow-ups** (the redesign is gebs2180/lookmate#14, replacing Chair-side and `docs/ux/tokens.css`): a client
  note field for the reveal's pull quote (now built from quiz answers), a single-stylist endpoint for the portfolio
  dialog, dark mode, install icons, and View Transitions between routes. Studio and admin weren't checked in the
  browser.
- **Module boundaries:** matching still reads the stylists, intake and tracking tables with read-only SQL (the documented
  exception in architecture.md). The follow-ups are in `docs/modules/matching.md`: intake adds `inspirationUrl` and
  `lockForReview`, and the fairness report moves into tracking.
- **Deferred:**
  - a reviewer or monitor warning when some of a market's photos aren't embedded yet;
  - a cleanup job for abandoned upload slots;
  - a fuller rate limit on public booking clicks (capped at 20 counted clicks per link per day for now);
  - an HNSW index once the vector dimension is fixed by Titan (1024).

## Environment notes

- **Local:** Docker Desktop, JDK 25 and Node 24. On Windows use `.\gradlew.bat` and `npm.cmd`.
- **Cloud sessions:** use the `lookmate` cloud environment. It allows `cgr.dev` for the MinIO test image, and its setup
  script installs JDK 25. In a fresh cloud container, start Docker with `dockerd` before running the backend tests.
- **GitHub:** use the GitHub tools for PRs. From cloud sessions, `gh` is for search only.
