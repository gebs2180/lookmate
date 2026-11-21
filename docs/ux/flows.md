# Information architecture and key flows

Companion to [design-direction.md](design-direction.md). Screen numbers refer to the prototype
(<https://claude.ai/artifact/74ApqgXdGU52ULhyr6Cvfm>). Routes are today's (`web/src/App.tsx`); routes marked *new* don't
exist yet.

## Information architecture

```
Public (no sign-in, PublicShell)
├── /                     Landing (today: the signed-out welcome inside the app shell)        [screen 1]
├── /find                 Style quiz: 7 questions → photo → optional first name (no email)     [screens 2, 3]
│                         └── sends, then goes straight to /m/:token
├── /m/:token             The client's page: a few seconds' wait that turns into the results    [screen 4]
│   ├── For you           Recommendations (3–5), reasons, evidence photos, Book
│   └── See all stylists  Every live stylist, filter by service and hair type (new route,       [screen 5]
│                         proposed /m/:token/stylists; align with the auto-publish branch)
└── /privacy, /terms      Plain-language pages linked from the privacy note                     (new)

Stylist (Auth0, phone)
├── /invite/:token        Accept invite → sign up
├── /stylist/:orgId       Home: terms → go-live checklist → live status, stats preview         [screen 7]
│   ├── /profile          Name, location, specialties, booking link, Instagram
│   ├── /portfolio        Grid + upload + tag sheet                                             [screen 6]
│   ├── /stats            (today /orgs/:orgId/stats) views, booking clicks, bookings
│   └── /bookings         (today /orgs/:orgId/bookings)
└── Bottom tab bar: Home · Portfolio · Stats · Profile                                          (new)

Admin (Auth0 + MFA, desktop or tablet)
├── Matching: /admin/matches (match log, with the fairness summary on top) → /admin/matches/:id   [screen 8]
│             /admin/matches/fairness   (one click from the log)
│             Review-before-publish switch on the log (off by default); when on, a "Waiting" tab
├── Stylists: /admin/stylists, /admin/locations, /admin/demo
└── Pilot:    /admin/metrics, /admin/bookings
    Left sidebar with these three groups; a waiting count beside "Match log" only while review is on. (new layout)
```

Navigation principle: clients never see navigation chrome beyond the wordmark (one task, one path); stylists get a
four-tab bar; the admin gets a sidebar.

---

## Client: from Instagram to a stylist's booking page

**Entry.** A link in a stylist's or Combpatible's Instagram bio or story, opened in Instagram's in-app browser on a phone.

| # | Step | Decision for the client | Design notes |
|---|---|---|---|
| 1 | Landing | "Is this for me, and is it safe?" | Hero collage across ≥3 hair types; one CTA "Find my stylist"; privacy line under it. If they arrive from a specific stylist's link (`?via=amara`, new), say "Amara uses Combpatible to match new clients". |
| 2 | Service | What am I booking? | Chips, tap to advance. |
| 3 | Hair type | Which of four? | 2×2 equal cards with glyphs; "If it's a mix, pick what you see most"; "See photo examples". |
| 4 | Strand thickness | Fine / medium / thick / not sure | "Not sure" is a full answer, not a failure. |
| 5 | Length now | Four options | Shrinkage hint for curls and coils. |
| 6 | Budget | Four tiers or not sure | Show dollar ranges, not only $ signs (open question: confirm ranges with the stylist on the team). |
| 7 | Area | Optional | Quick chips for common Columbus areas plus free text. Skip is visible. |
| 8 | What should your next stylist get right? | Optional, multi | Positive chips + "In your own words". Skip is visible. |
| 9 | Inspiration photo | Which photo? | Privacy note under the picker; preview with "Change photo". |
| 10 | First name (optional) | Do I have to sign up? | No email here (decided 2026-11-20): "No email needed: your results open on their own page. Bookmark that page, or email it to yourself from there." |
| 11 | The wait (seconds) | none | Same page, no navigation: "Thanks, Jordan. Here's what we heard." (the echo), their photo with a scan line, three steps ticking off, tiles filling with stylists' photos. The page polls `GET /api/public/match-requests/{token}` about every 1.5 s while the stage is RECEIVED or MATCHING. |
| 12 | Results, in place | Which stylist? | "Found in 4 seconds." Tabs: **For you · 3** and **See all stylists**. Cards: reason first, evidence photos, "Why Priya?", "Book on Vagaro ↗". "Not feeling these? See all 23 stylists" at the end. |
| 13 | See all stylists (optional) | Anyone else? | Filters start from their answers (service, hair type); recommended stylists sort first with a "For you" label; each card shows work counts per hair type and three photos. |
| 14 | Book | Leave for the stylist's site | Opens in a new tab through tracking's redirect; no referrer. |
| 15 | Keep this list (optional) | How do I come back? | A box on the results page: "This page is your link. Bookmark this page, or email it to yourself." Field "Email (optional)", "We may ask how your appointment went." Once given, the masked address replaces the box ("Use a different email" fixes a typo). On "No close matches yet" it reads "Tell me when picks are added". Nothing is emailed to a client who gives no email. |

**Friction to remove (from today's `/find`):**

- *Next* after every single-choice answer: auto-advance instead (keeps Back).
- The first screen is a question with no context when someone arrives from Instagram: add the landing (step 1), which
  today exists only as the signed-out welcome inside the stylist shell.
- "Question 1 of 9" with a native `<progress>`: keep the count, style a thin bar.
- Instagram's in-app browser can't pick HEIC well and loses state on app switch: keep answers in `sessionStorage` until
  sent, so leaving to find a screenshot doesn't restart the quiz.
- The sent screen says "a Combpatible stylist is matching you…" and "we'll email you": replace it with the in-page wait that
  becomes the results (matches now publish automatically in seconds).
- The match list is the only way to see stylists: add "See all stylists" next to it, so choosing anyone is real.

**Empty states**

| Where | State | Copy and action |
|---|---|---|
| `/m/:token`, RECEIVED/MATCHING | Ranking under way | The wait (step 11). |
| Ranking slow (> 20 s) | Worker busy or the embedding provider slow | Keep the wait, change the line: "This is taking longer than usual. Stay here, or bookmark this page and come back." Keep polling (back off to every 10 s). |
| Ranking failed (FAILED) | Provider outage, unreadable photo | "We couldn't match your photo just now. We'll try again and email you. Meanwhile, here's everyone who does curly cuts." with See all stylists pre-filtered. |
| No close match | Ranking returned 0 stylists for the hair type and service | Honest headline: "We couldn't find stylists with work close to your photo on curly hair yet." Show See all stylists filtered to the hair type. Never pad the list with poor matches. |
| Few close matches | 1–2 stylists | Show them; the reason says when evidence is from a neighbouring type ("…on wavy and curly hair"). |
| Review switched on (admin) | Request waits for a person | "A stylist is checking your matches. We'll email you when they're ready, usually within a few hours." See all stylists stays available. |
| Photo deleted (after 30 days) | Photo gone | "Deleted on Dec 20, as promised." Matches stay visible. |
| See all stylists, filters return nothing | | "No stylists show braids on straight hair yet. The pilot is small, so some combinations are thin." Button: "Show braids on any hair type". |

**Error states**

| Error | Today | Proposed |
|---|---|---|
| Photo rejected (422 `IMAGE_REJECTED`) | Generic message | "We couldn't read that photo. Try a JPEG or PNG up to {maxPhotoBytes} MB, or take a screenshot of it." (the size comes from `/api/public/intake/settings`) Keep the answers; reopen the picker. |
| Upload fails after the request was created | Alert + link to request page | Same, plus "Your answers are saved". (Today's flow is right; restyle only.) |
| Rate limited (429) | Message | "You've sent a few requests today. Your existing link still works." |
| Link expired or wrong (404) | "This link doesn't work" | Same headline; "Links last 90 days. Start a new request; it takes two minutes." |
| Offline in the quiz | Fetch error | Inline: "You're offline. Your answers are saved here; send when you're back." |
| Booking site down | n/a (external) | Nothing to do on our side; the tracked redirect still records the click. |

---

## Stylist: invite to live

**Entry.** An invite email from Zach (or a QR card at a Salon Lofts location), opened on a phone.

| # | Step | Decision | Design notes |
|---|---|---|---|
| 1 | Invite → sign up (Auth0) | Trust the invite | Invite page shows who invited them and what Combpatible does in two lines. |
| 2 | Terms | Accept | Plain-language summary above the full text (the text needs a lawyer before the pilot). |
| 3 | Home: checklist | What's left? | Screen 7. Each row links to its step. |
| 4 | Profile | Name, location, specialties, booking link, Instagram | Location is a list (entered by the admin). Booking link: paste, then show "Detected: Booksy" or "We don't recognise this site; clients will still go there". |
| 5 | Upload | Which photos? | Multi-select from the camera roll; uploads continue in the background with per-photo progress. Hint: "Before/after pairs rank higher". |
| 6 | Tag | Four taps per photo | Screen 6. "Tag the next photo" opens the sheet on the first untagged one; "Same as last photo"; "Save and next". |
| 7 | Go live | Publish | Button names what's missing until it's enabled. |
| 8 | Live | Anything to do? | Home shows "You're live", booking clicks this week, and photos to tag. |

**Friction to remove:** the tag sheet today has *Save tags* + *Cancel* and returns to the grid, so tagging 10 photos is
10 round trips: add *Save and next* and *Same as last photo*. Hide/Delete sit in the sheet beside Save: move them to a
"⋯" menu so a quick Save can't hit Delete. Before/after pairing picks from every other photo: offer the next and previous
photos first ("Pair with the next photo"), since pairs are usually uploaded together.

**Empty states:** no photos ("Add 10–20 photos of your best work. Start with ones that show the hair clearly."); all
tagged ("All tagged. Clients can be matched with any of these."); live with no clicks yet ("No booking clicks yet. Matches
go out as clients ask; most stylists see their first within two weeks." Confirm that claim during the pilot.)

**Errors:** upload fails ("This photo didn't upload. Tap to try again." per tile, the others continue); photo cap reached
(today's server message is good: "You have {n} photos, the most a portfolio can hold. Delete one to add another."; `n` is `maxPhotosPerStylist`); duplicate photo ("You've already uploaded this one.");
booking link not https ("Booking links need to start with https://. Copy it from your booking site's share button.").

---

## Admin: monitoring, not approval

Matches publish automatically (product decision, 2026-11-20). The admin watches what clients were shown and what
happened next, and steps in after the fact.

| # | Step | Decision | Design notes |
|---|---|---|---|
| 1 | Open the match log | Is anything off? | The **fairness summary sits above the log**: Book-click rate by the client's hair type for the last 30 days, a warning chip on any type falling behind, and a prominent "Open fairness report →". |
| 2 | Scan recent requests | Which to look at? | Table: time, client (hair type, service), shown (count, top stylist), viewed, Book clicks, booked. Chips mark rows worth a look: "Only 2 matches", "Near-type match", "Not opened". |
| 3 | Open a request | Did this list serve the client? | Detail: client photo and their words, the list as shown (rank, reason, score, 4 evidence photos tagged by hair type, near-type labelled), clicked/booked chips, and a "What happened" timeline (sent, published in 4 s, viewed, clicked, booking recorded). |
| 4 | Act, after the fact | Hide a match, or a stylist? | **Hide from Jordan** removes one match from one client's page. **Hide stylist…** opens an inline confirmation ("leaves every client's list and the directory until you show her again; clients who already booked are not affected"). Both reversible. |
| 5 | Switch review on (rare) | Is textured-hair quality poor? | "Review before publish" switch on the log, off by default. |

**Secondary state: review before publish (switch on).** A warning-tone banner says it is on and what clients see. A
"Waiting for review · n" tab appears first; its rows open the old review controls (up/down, hide, Publish n), and
publishing moves to the next waiting request. Clients see "A stylist is checking your matches" and get the email. The
side-nav count shows the waiting number only while review is on. Turning it off publishes nothing by itself: requests
already waiting stay waiting until published or the switch is off and they are re-ranked (decide which with the
auto-publish branch).

**Friction to remove:** today's queue and review screen assume every list needs approval; the log replaces it as the
landing page for `/admin/matches`. Keyboard: J/K move through rows, Enter opens, H hides the focused match.

**Empty states:** no requests today ("No requests yet today. Yesterday: 9 requests, 4 Book clicks."); waiting tab empty
("Nothing waiting. New requests appear here while review is on."). **Errors:** hiding a stylist who was hidden elsewhere
(`STYLIST_NOT_AVAILABLE`: "Already hidden. Reload to see the current list."); in review mode, someone else published
(409 `NOT_IN_REVIEW`: "Already published by another reviewer. Reload to see it.").
