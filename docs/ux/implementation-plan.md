# Implementation plan: bringing the web app to "Chair-side"

How to move `web/` from the inherited "Ledger" styling (pokemans) to [design-direction.md](design-direction.md) in
small PRs, client flow first. Each PR is one module's files where possible, keeps tests green
(`cd web && npm test && npm run lint && npm run build`), and changes no behaviour except where it says so.
PRs marked **backend** change an API: run `./gradlew test -Dopenapi.update=true`, then `npm run api:types` in `web/`.

Order is by user impact: clients first (they decide whether the pilot works), then stylists (supply), then the admin
(one person, desktop).

| PR | What | Files | Depends on |
|---|---|---|---|
| 1 | **Tokens and fonts.** Swap the `:root` and dark blocks of `index.css` for [tokens.css](tokens.css) (same token names, so component CSS needn't change). Add Bodoni Moda and Hanken Grotesk via Fontsource; remove Manrope once nothing uses it. Theme colour `#8c1d47`, background `#fbf8f7` in the PWA manifest and `<meta name="theme-color">` (light and dark). Remove the Ledger-specific rules (`.offer-price`, `.slip`, `.stackbar`) if unused. | `web/src/index.css`, `web/src/main.tsx`, `web/package.json`, `web/vite.config.ts`, `web/index.html` | none |
| 2 | **Wordmark and public shell.** `<Wordmark/>` (ring mark + name from one constant) in `ui.tsx`; used by both shells. Public shell: wordmark only, no other chrome; 18px gutters; display type on `h1`. | `web/src/ui.tsx`, `web/src/App.tsx`, `web/src/pages/public/PublicShell.tsx`, `web/src/index.css` | 1 |
| 3 | **One hair vocabulary.** New `web/src/hair.ts`: labels, hints, curl-code hints and an inline-SVG `<HairGlyph type/>` for STRAIGHT/WAVY/CURLY/COILY, plus "Strand thickness" wording and the new length labels. `quiz.ts` and `stylists/types.ts` import from it, so the client and the stylist see the same words. Copy only; codes unchanged. | `web/src/hair.ts` (new), `web/src/pages/public/quiz.ts`, `web/src/stylists/types.ts`, tests that assert labels | 1 |
| 4 | **Quiz redesign.** Hair type as a 2×2 card grid with glyphs; chips for service; tap-to-advance on single choices (260 ms, Back kept); thin progress bar; "What should your next stylist get right?" with positive labels mapped 1:1 to today's `PAST_ISSUES` codes; Skip on optional steps; answers kept in `sessionStorage` until sent (Instagram's in-app browser drops state when switching apps). | `web/src/pages/public/FindPage.tsx`, `web/src/pages/public/quiz.ts`, `web/src/pages/public/public.css` (new, moved out of `index.css`), `FindPage.test.tsx` | 3 |
| 5 | **Photo step and privacy note.** Picker as a drop card; preview with "Change photo"; `PrivacyNote` as the three-fact info card (who sees it, metadata removed, deletion after N days from settings). Clearer rejected-photo copy using `maxPhotoBytes`. | `web/src/pages/public/PhotoPicker.tsx`, `web/src/pages/public/photo.ts`, `public.css` | 4 |
| 6 | **Landing page.** A signed-out `/` that is the client landing (hero collage, CTA, privacy line, four textures, three steps) with a small "Stylist log in" link, instead of today's two-button welcome. Placeholder duotones until real photography exists (see Open questions). | `web/src/pages/public/LandingPage.tsx` (new), `web/src/App.tsx`, `web/src/pages/public/publicPaths.ts`, `public.css`, `PublicShell.test.tsx` | 2 |
| 7a | **backend, intake:** return the client's own words to their page: `pastIssues` and `pastIssuesNote` on `ClientView` (the client's own data, on their own token). | `backend/app/src/main/java/com/lookmate/app/intake/**`, `docs/modules/intake.md`, `web/src/api.gen.ts` | none |
| 7 | **Sent screen and status page.** The echo (their note in display italic, answers as chips), the three-stage timeline with honest timing, the "Save this page" card; photo deletion date. | `web/src/pages/public/FindPage.tsx` (sent state), `web/src/pages/public/MatchStatusPage.tsx`, `public.css`, `MatchStatusPage.test.tsx` | 4, 7a |
| 8a | **backend, matching + intake:** add `demo`, `bookingPlatform` and per-photo hair-type tags to `ClientMatch`. `demo` closes a gap: docs/modules/stylists.md requires demo stylists to be labelled wherever a client sees them, and the client list can't do that today. | `backend/.../intake/ClientMatch*`, `backend/.../matching/MatchListForClients*`, `docs/modules/matching.md`, `web/src/api.gen.ts` | none |
| 8 | **Match cards.** Reason first with the glyph; 3 evidence photos 4:5 with tags on a scrim; "Why {name}?" disclosure; "Book on {platform} ↗"; "Sample stylist" label; "Not feeling these?" card at the end (links to browse once it exists; until then it says "Ask us for more options" by email, or is left out). | `web/src/pages/public/MatchStatusPage.tsx`, `public.css` | 7, 8a |
| 9 | **Tag sheet speed.** "Tag the next photo" entry, "Same as last photo", "Save and next", segmented hair-type control with glyphs, Hide/Delete moved into a "⋯" menu, before/after offers neighbouring photos first. Focus returns to the photo on close. | `web/src/stylists/TagSheet.tsx`, `web/src/stylists/PortfolioPage.tsx`, `web/src/stylists/stylists.css`, `PortfolioPage.test.tsx` | 3 |
| 10 | **Stylist home and go-live.** Checklist rows as links with a "Tag now" chip; Go live button names what's missing; detected booking platform; "Your work by hair type" (from the photos the page already loads); live state. Bottom tab bar (Home, Portfolio, Stats, Profile) for stylist routes. | `web/src/stylists/StylistHomePage.tsx`, `web/src/stylists/ProfilePage.tsx`, `web/src/App.tsx`, `stylists.css`, tests | 1, 3 |
| 11 | **Admin shell and review.** Sidebar (Matching, Stylists, Pilot) with queue count; review as three columns (client: photo, answers, the echo; candidates: score, reason, 4 tagged evidence photos, up/down/hide; client preview + publish + this list's fairness strip); keyboard shortcuts; after publish go to the next request. | `web/src/App.tsx`, `web/src/pages/admin/MatchReviewPage.tsx`, `web/src/pages/admin/MatchQueuePage.tsx`, `web/src/pages/admin/matching.css`, `MatchReviewPage.test.tsx` | 1 |
| 12 | **Sweep.** Remaining screens onto the tokens (`tracking.css`, admin stylists, locations, demo, metrics, fairness, invite, terms); dark mode and reduced-motion pass; axe check of every route in the Playwright smoke test. | `web/src/pages/tracking/*`, `web/src/admin/*`, `web/src/pages/InvitePage.tsx`, `web/src/stylists/TermsStep.tsx`, CSS files | 1–11 |

Notes:

- **Parallel work.** 1 must merge first. After that, the client track (2→4→5→7→8, plus 6) and the stylist track
  (3→9→10) can run in parallel sessions; both touch `index.css` only through PR 1, and 3 is the one shared file
  (`hair.ts`), so merge 3 before starting 4 or 9. 7a and 8a are backend-only and can start any time.
- **Tests.** Most component tests query by role and label; PR 3 and 4 change visible labels, so those tests change in the
  same PR. Add a token contrast test in PR 1 (parse `index.css`, check the documented pairs ≥ 4.5:1 and borders ≥ 3:1) so a
  later colour tweak can't silently fail AA.
- **Not in these PRs:** "Browse all stylists" (`/m/:token/all`) is a new feature, needs a public, rate-limited list
  endpoint over `Stylists.liveProfiles`, and a product decision first (open question 4 in [design-direction.md](design-direction.md#open-questions)).
