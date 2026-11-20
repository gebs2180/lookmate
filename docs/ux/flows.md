# Information architecture and key flows

Companion to [design-direction.md](design-direction.md). Screen numbers refer to the prototype
(<https://claude.ai/artifact/74ApqgXdGU52ULhyr6Cvfm>). Routes are today's (`web/src/App.tsx`); routes marked *new* don't
exist yet.

## Information architecture

```
Public (no sign-in, PublicShell)
├── /                     Landing (today: the signed-out welcome inside the app shell)        [screen 1]
├── /find                 Style quiz: 7 questions → photo → email                              [screens 2, 3]
│                         └── Sent: "Here's what we heard" + status                            [screen 4]
├── /m/:token             The client's request: status, echo, matches, photo and its deletion date [screens 4, 5]
│   └── /m/:token/all     Browse all stylists, filterable by hair type and service              (new)
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
├── Matching: /admin/matches (queue) → /admin/matches/:id (review)                              [screen 8]
│             /admin/matches/fairness
├── Stylists: /admin/stylists, /admin/locations, /admin/demo
└── Pilot:    /admin/metrics, /admin/bookings
    Left sidebar with these three groups, queue count beside "Review queue".                   (new layout)
```

Navigation principle: clients never see navigation chrome beyond the wordmark (one task, one path); stylists get a
four-tab bar; the admin gets a sidebar.

---

## Client: from Instagram to a stylist's booking page

**Entry.** A link in a stylist's or LookMate's Instagram bio or story, opened in Instagram's in-app browser on a phone.

| # | Step | Decision for the client | Design notes |
|---|---|---|---|
| 1 | Landing | "Is this for me, and is it safe?" | Hero collage across ≥3 hair types; one CTA "Find my stylist"; privacy line under it. If they arrive from a specific stylist's link (`?via=amara`, new), say "Amara uses LookMate to match new clients". |
| 2 | Service | What am I booking? | Chips, tap to advance. |
| 3 | Hair type | Which of four? | 2×2 equal cards with glyphs; "If it's a mix, pick what you see most"; "See photo examples". |
| 4 | Strand thickness | Fine / medium / thick / not sure | "Not sure" is a full answer, not a failure. |
| 5 | Length now | Four options | Shrinkage hint for curls and coils. |
| 6 | Budget | Four tiers or not sure | Show dollar ranges, not only $ signs (open question: confirm ranges with the stylist on the team). |
| 7 | Area | Optional | Quick chips for common Columbus areas plus free text. Skip is visible. |
| 8 | What should your next stylist get right? | Optional, multi | Positive chips + "In your own words". Skip is visible. |
| 9 | Inspiration photo | Which photo? | Privacy note under the picker; preview with "Change photo". |
| 10 | Email (+ first name) | Will I get spam? | "We email you a private link. No account, no password." Consent sentence states both retention periods. |
| 11 | Sent: "Here's what we heard" | none | The echo, the 3-stage timeline, "usually within a day", "save this page". |
| 12 | Email: "Your matches are ready" | Open it | Subject names the hair type and service: "3 stylists for your curly cut". |
| 13 | Matches | Which stylist? | 3–5 cards, reason first, evidence photos, "Why Priya?", "Book on Vagaro ↗". "Browse all stylists" at the end. |
| 14 | Book | Leave for the stylist's site | Opens in a new tab through tracking's redirect; no referrer. |

**Friction to remove (from today's `/find`):**

- *Next* after every single-choice answer: auto-advance instead (keeps Back).
- The first screen is a question with no context when someone arrives from Instagram: add the landing (step 1), which
  today exists only as the signed-out welcome inside the stylist shell.
- "Question 1 of 9" with a native `<progress>`: keep the count, style a thin bar.
- Instagram's in-app browser can't pick HEIC well and loses state on app switch: keep answers in `sessionStorage` until
  sent, so leaving to find a screenshot doesn't restart the quiz.
- The sent screen says "a LookMate stylist is matching you…": also show the echo and the timeline, so the wait feels
  attended to.

**Empty states**

| Where | State | Copy and action |
|---|---|---|
| `/m/:token`, MATCHING | No matches yet | Timeline + shimmer: "Usually within a day. We'll email you." |
| `/m/:token`, READY with 0 shown | Reviewer published nothing usable (shouldn't happen: publish needs one visible match) | "We're still choosing your stylists by hand. We'll email you." |
| Few candidates for the client's hair type | Matching found < 3 on the same type | Reviewer adds by hand; client copy for a neighbouring-type match says so in the reason ("…on wavy and curly hair"). Never pad the list with poor matches. |
| Photo deleted (after 30 days) | Photo gone | "Deleted on Dec 20, as promised." Matches stay visible. |
| Browse all (new), filters return nothing | | "No stylists show curly braids yet. Try braids on any hair type." |

**Error states**

| Error | Today | Proposed |
|---|---|---|
| Photo rejected (422 `IMAGE_REJECTED`) | Generic message | "We couldn't read that photo. Try a JPEG or PNG up to {maxPhotoBytes} MB, or take a screenshot of it." (the size comes from `/api/public/intake/settings`) Keep the answers; reopen the picker. |
| Upload fails after the request was created | Alert + link to request page | Same, plus "Your answers are saved". (Today's flow is right; restyle only.) |
| Rate limited (429) | Message | "You've sent a few requests today. Your existing link still works: check your email." |
| Link expired or wrong (404) | "This link doesn't work" | Same headline; "Links last 90 days. Start a new request; it takes two minutes." |
| Offline in the quiz | Fetch error | Inline: "You're offline. Your answers are saved here; send when you're back." |
| Booking site down | n/a (external) | Nothing to do on our side; the tracked redirect still records the click. |

---

## Stylist: invite to live

**Entry.** An invite email from Zach (or a QR card at a Salon Lofts location), opened on a phone.

| # | Step | Decision | Design notes |
|---|---|---|---|
| 1 | Invite → sign up (Auth0) | Trust the invite | Invite page shows who invited them and what LookMate does in two lines. |
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

## Admin: queue to published

| # | Step | Decision | Design notes |
|---|---|---|---|
| 1 | Queue | Which first? | Oldest first, waiting time on each row, hair type and service visible. FAILED and MATCHING in their own tabs. |
| 2 | Review (screen 8) | Is this list right for this client? | Left: photo + answers + the echo ("Get this right"). Middle: candidates with score, reason, 4 evidence photos tagged by hair type. Right: client preview, publish, fairness strip. |
| 3 | Adjust | Reorder, hide, add, edit reason | Up/down buttons and keyboard (J/K, ⌥↑/⌥↓, H). Drag is optional, not required. |
| 4 | Publish | Send | Button states the count; note states the email. After publishing the list can still change. |
| 5 | Next | | Moves to the next request in the queue. |

**Friction to remove:** publishing today returns to the queue; go straight to the next request. Reasons are generated;
let the reviewer edit them inline (the match `note` exists; a client-visible reason override is an open question).

**Empty states:** queue empty ("Nothing to review. 2 requests are still matching."); no candidates ("No stylists matched
this hair type and service. Add one by hand, or close the request with a note to the client."). **Errors:** someone else
published (409 `NOT_IN_REVIEW`: "Already published by another reviewer. Reload to see it."); stylist went hidden
(`STYLIST_NOT_AVAILABLE`).
