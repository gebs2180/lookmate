# Feature ideas: what to add next for clients and stylists

Research and a ranked list, 2026-11-25. **Decided the same day (Zach): build ideas 1–4, 6 and 7; idea 5 is dropped.**
The answers are under [Open questions](#open-questions-for-zach) and in CLAUDE.md's Decisions; where an idea's text
below disagrees with them, the answers win. Read with [CLAUDE.md](../../CLAUDE.md) (Decisions, Non-negotiables), [brief.md](../brief.md) (pilot
metrics), [beyond-the-hair.md](beyond-the-hair.md), [service-finder.md](service-finder.md),
[tracking.md](../tracking.md) and the module docs in [modules/](../modules/).

**Method.** Web searches across trade press (Salon Today, Behind the Chair, Modern Salon), vendor surveys (Square,
Boulevard, Phorest), product reviews (Trustpilot, Capterra), client-side writing (NaturallyCurly, theGrio, Refinery29)
and stylist forums. Most figures are vendor surveys or trade commentary, not independent studies; each is marked where
it matters. Ideas that the earlier research already covers (practice labels, from-prices, quiet appointments, the
service finder) aren't re-proposed; where an idea extends one, it says so.

## Summary

Combpatible already answers **"who should do my hair?"** (matching) and **"what do I book?"** (the finder). The
biggest gaps left in the pilot are on either side of that moment:

- **Before the first visit:** clients, especially curly, coily and colour clients, don't know how to arrive or what
  the stylist will need, and stylists lose time and money to wrong or unprepared first visits.
- **After the first visit:** the pilot's headline target is a **50% rebook rate**, but today a rebook only exists if a
  stylist or the admin types it in. Nothing prompts the client to go back or the stylist to record it.
- **For the stylist's own marketing:** stylists live on referrals and their own links, and comparable marketplaces
  anger them by charging for clients the stylist brought. Combpatible can be the opposite: a free page they share.

The top ideas are all small, live inside existing modules, and need no calendar, payments, messaging or public client
photos.

| Rank | Idea | Helps | Size | Moves |
|---|---|---|---|---|
| 1 | "Before your first visit": prep notes on the match page, partly written by the stylist | Both | S | Matched clients who book; rebook |
| 2 | Weekly stylist digest with one-tap "booked / rebooked" | Stylist (and our metrics) | S | Every metric's accuracy; stylists who'd keep using it |
| 3 | "Taking new clients" status with a typical wait | Both | S | Matched clients who book |
| 4 | A shareable stylist page and loft QR card, never charged | Stylist | S–M | Active stylists; keep using |
| 5 | Opt-in "time to rebook" reminder at the stylist's interval | Both | M | Rebook rate (50%+) |
| 6 | Private "did you get the look?" check-in | Both | M | Rebook; fairness evidence |
| 7 | Duplicate and reposted portfolio photo check | Both | S | Trust in matching; fairness |

S is about one session (a day or two); M is two sessions or a few days.

## The top ideas, ranked

### 1. "Before your first visit": prep notes on the match page

**The problem.**
- First visits go wrong before the client sits down. Curl specialists ask clients to arrive with hair dry, detangled
  and with little or no heavy product, and to bring their current products; coily first visits take longer because
  the stylist may need to wash and stretch-dry first. [OnlyCurls][onlycurls-prep], [FASHION][fashion-curly]
- Colour clients often can't give an accurate colour history, and professional guidance is to strand-test when it's
  uncertain or a big lift is planned. [Warehouse Salon][warehouse-strand], [Hair Colour for Hairstylists][opentext-consult]
- Inspiration photos are increasingly filtered or AI-made, and stylists end up as "reality checkers".
  [Axios][axios-ai-inspo], [CBC][cbc-ai-inspo], [Beauty Launchpad][bl-colour-expectations]
- NaturallyCurly's advice to curly clients is to ask, before booking, whether the stylist cuts curls often and what
  they need from you. [NaturallyCurly][nc-finding] Most clients don't know to ask.
- Clients who book online return for a second visit far more often than walk-ins (78% vs 39% in Boulevard's data,
  vendor figure): first-visit experience drives the second. [Salon Today on Boulevard][boulevard-retention]

**The idea.** On the client's match page (and the finder's result), a short "Before your first visit" block for each
recommended stylist:
- **Generic notes by service and hair type**, written with Jason or another partner stylist, not by us or by AI.
  For example: curly cut → "Arrive with dry hair, no heavy oils or butters. Bring the products you use." New colour →
  "Have your colour history ready: box dye, henna, lightener, the last two years. Expect a strand test." Coily →
  "Allow extra time for the first visit."
- **The stylist's own first-visit notes**, three optional fields on their profile: "New colour clients need a
  consultation first" (yes/no), "Deposit for new clients" (yes/no, shown as a fact, never collected by us), and a
  short free-text note ("Park in the back lot; I'm in loft 12").
- Extends the existing booking-note tip (`QUIET_APPOINTMENT`, `HONEST_ADVICE`, [intake.md](../modules/intake.md)):
  "Bring 2–3 photos, including one of what you *don't* want" is a tip the client can copy too.

**Who it helps.** Both. Clients arrive prepared and less anxious; stylists get fewer wasted or mis-scoped first
visits, which is exactly what made Jason ask for the finder.

**Fit.** `stylists` (three optional profile fields, one migration), the web match page and portfolio dialog, and
`consult` (the same notes on a finder result, keyed by the outcome's `serviceType`). Generic notes are a small static
content file keyed by `ServiceType` × `HairType`, reviewed by the partner. No ranking change.

**Size.** S.

**Metric.** Matched clients who book (fewer second thoughts at the hand-off) and rebook (a better first visit).

**Risks.** Wrong advice is worse than none: every generic note needs a working stylist's sign-off, and the stylist's own
note overrides it. Deposit and consultation are shown as facts only; we never take money. No privacy impact (no client
data).

### 2. Weekly stylist digest with one-tap "booked / rebooked"

**The problem.**
- Our pilot metrics (100+ clients who book, 50%+ who rebook) depend on `BOOKED` and `REBOOKED` events, which only exist
  when a stylist or the admin enters them ([tracking.md](../tracking.md)). A Book click only proves interest; a rebook
  happens in the stylist's own booking tool where we can't see it.
- Stylists on marketplaces complain most about not knowing, or not agreeing with, which clients the platform sent:
  StyleSeat and Booksy reviewers describe being charged for clients they brought themselves.
  [StyleSeat on Trustpilot][styleseat-trustpilot], [Booksy on Trustpilot][booksy-trustpilot],
  [Capterra: StyleSeat][styleseat-capterra]
- "Stylists who'd keep using it: 75%+" needs stylists to *see* what Combpatible did for them.

**The idea.** A Monday email to each live stylist: "Last week Combpatible showed you to 9 clients; 3 tapped Book:
Maya, Dana, Sam." Each name has two buttons, "Booked" and "Rebooked", that record the event through the existing
stylist route (signed, single-use links that land on a sign-in if needed). Plus one line of their stats.

**Who it helps.** Stylists (proof of value, no dashboard visit needed) and the pilot's measurement.

**Fit.** `tracking` already has the per-org stats and the stylist `POST /api/orgs/{orgId}/matches/{matchId}/events`;
`notify` sends it through the outbox like the other emails. Directory picks (no match id) need the per-request route
opened to stylists too; [tracking.md](../tracking.md) lists that as not done.

**Size.** S.

**Metric.** Makes "clients who book" and "rebook rate" real numbers instead of undercounts; supports "stylists who'd
keep using it".

**Risks.**
- Stylists already see matched clients' first names (decided); the email adds nothing new, but email is less private
  than the dashboard: first names only, never contact details or photos.
- One-tap links must not let a forwarded email record events for someone else: short-lived signed tokens, and voiding
  stays available.
- An email a week is the most a solo stylist will tolerate; let them turn it off.

### 3. "Taking new clients" status with a typical wait

**The problem.**
- Availability is the **top** reason people pick a new stylist (33%), ahead of friends' recommendations (27%) and the
  online portfolio (22%). [Boulevard survey via Business Wire][boulevard-choose]
- Hard to book is the top reason clients stray (57%). [Square][square-loyalty]
- Fully booked stylists either close their books, which costs them referrals, or take new clients "with an explanation
  that there will be a wait". [Creative Head][creativehead-books], [Acuity][acuity-manage]
- A Combpatible match that leads to a booking page with nothing for six weeks is a dead end for the client and a lost
  booking for the metric.

**The idea.** On the stylist's profile: "Taking new clients" (default) / "Taking new clients, usually about N weeks
out" / "Not taking new clients right now". The match card shows the wait in plain words. A stylist who isn't taking
new clients stays in the directory (so returning clients can still find them) but **doesn't get new matches** until
they switch back. The setting asks to be confirmed every 30 days, from the digest (idea 2).

**Who it helps.** Both: clients avoid dead ends; stylists control their flow without vanishing.

**Fit.** `stylists` (one field and a timestamp); `matching` treats "not taking new clients" like "not LIVE" for new
requests only; web match card and directory.

**Size.** S.

**Metric.** Matched clients who book.

**Risks.**
- **Needs Zach's decision**: it changes who is *eligible* for matching (not the order). It's self-reported and can go
  stale; the 30-day confirm is the guard.
- **Fairness**: if textured-hair specialists are the most booked-out (plausible, given 86% of Black women report
  trouble finding consistent care [TRESemmé][tresemme-bias]), removing them could leave curly and coily clients with
  weaker matches. Watch the per-hair-type metrics, and consider showing a "books out ~6 weeks" stylist rather than
  hiding them.
- No calendar: the wait is a number the stylist types, never synced from a booking tool.

### 4. A shareable stylist page and loft QR card, never charged

**The problem.**
- Independent stylists build their own presence; word of mouth is often quoted as most new clients (a trade rule of
  thumb, about 70%, not a study). [Salon Today][salontoday-referrals]
- Salon Lofts already pushes loft owners to set up their own Google Business Profile, separate from the building.
  [Salon Lofts][salonlofts-gbp]
- The marketplaces' most hated feature is the new-client fee applied to clients the stylist sent there themselves
  (StyleSeat: 30% of the first visit up to $50 on Premium, per its own comparison; Booksy Boost: 30% up to $100).
  [StyleSeat][styleseat-vs-booksy], [StyleSeat on Trustpilot][styleseat-trustpilot], [Booksy on Trustpilot][booksy-trustpilot]

**The idea.** A public page per live stylist, `combpatible.app/s/<slug>`: their portfolio by hair type, labels,
from-prices, first-visit notes (idea 1), and two buttons: "Book" (their own link, tracked as `utm_content=profile`) and
"Not sure what to book?" (their finder link, where they have one). A printable QR card for the loft mirror and a
"link in bio" version. A Combpatible-branded social preview image built **only from the stylist's own portfolio**.

**Who it helps.** Stylists (a free, better portfolio page than an Instagram grid, sorted by hair type). Clients who
arrive through it see evidence, not just a feed.

**Fit.** The single-stylist endpoint is already a listed Lookbook follow-up ([orchestrator-handoff.md](../orchestrator-handoff.md));
`stylists` (slug, public read), `tracking` (a third source next to recommended and directory), web route. The
finder's `/c/<slug>` already exists for the partner.

**Size.** S–M.

**Metric.** Active stylists (a reason to finish onboarding) and stylists who'd keep using it.

**Risks.**
- **Business model:** whatever the later stylist membership looks like, it should never charge per client for
  clients who came through the stylist's own page. That is the single most common complaint about the competitors.
  Flag for Zach now so the tracking source is kept apart from the start.
- Portfolio photos are the stylist's and are already public in the directory; no client photo is ever on this page.
- Demo stylists must not get shareable pages.

### 5. Opt-in "time to rebook" reminder at the stylist's interval

**The problem.**
- 82% of clients who strayed said easy rescheduling would bring them back. [Square][square-loyalty]
- More than half of first-time salon visitors never return for a second appointment; top salons do much better.
  [Salon Today on Boulevard][boulevard-retention] (Phorest and others put new-client retention nearer 30%.)
- Cut and colour intervals are predictable: curly cuts roughly every 8–12 weeks, permanent colour touch-ups every 4–6.
  [StyleSeat][styleseat-howoften], [Marie Claire][marieclaire-howoften] Pre-booking the next visit is the retention
  lever most cited (vendor claim). [Mindbody][mindbody-retention]

**The idea.** When the client taps Book and has given an email, one extra tick: "Remind me when it's time to go back
(about 8 weeks)". The stylist sets the interval per service on their profile; the reminder links straight to **the
same stylist's** booking link, tracked as a rebook click. One reminder per booking, never a series.

**Who it helps.** Both: clients get the nudge they'd have needed; stylists get the client back rather than losing
them to "hard to book".

**Fit.** `intake` (a consent flag next to the existing email consent), `notify` (a scheduled outbox email), `tracking`
(a `CLICKED_REBOOK` or a rebook source on `CLICKED_BOOK`), `stylists` (interval per service).

**Size.** M.

**Metric.** Rebook rate (the 50%+ target), directly.

**Risks.**
- **Needs Zach's decision:** is a one-way reminder "messaging"? It isn't a conversation and the stylist sends nothing,
  but it is a new email type. Our proposal: it's allowed, the same as "matches ready".
- Separate opt-in, unticked by default; "No email means no emails, ever" still holds.
- It extends how long we keep the email (the reminder must outlive the 90-day link TTL and fall inside the 365-day
  contact retention). Check against [data-retention.md](../data-retention.md).
- We don't know the real appointment date, only the click. Anchor on the `BOOKED` date when the stylist marked one
  (idea 2), else on the click, and say "about".

### 6. Private "did you get the look?" check-in

**The problem.**
- "Didn't listen" (41%) and "incompatible personalities" (34%) are why clients leave. [Square][square-loyalty]
- Textured-hair clients report cancellations, unfinished styles and surprise charges, and are moving to chains like
  Ulta for consistency. [theGrio][thegrio-ulta], [NBC News][nbc-black-hair], [Refinery29][r29-black-hair]
- Our fairness check today only sees clicks and bookings by hair type. It can't tell whether a curly client who booked
  actually got what they asked for.

**The idea.** This is item 10 of [beyond-the-hair.md](beyond-the-hair.md) (open question 9 there), made concrete: one
email, about 10 days after a `BOOKED` event, only for clients who gave an email: "Did Jordan get the look you asked
for? Yes / Mostly / No", plus "Would you book Jordan again?". Answers go privately to the stylist (aggregated, after
several) and to the admin's fairness view by hair type. **No public score.** Optionally a link to the stylist's own
Google profile, offered to every respondent, not just happy ones.

**Who it helps.** Both. The stylist gets feedback they'd rarely hear; the client gets a voice without a public review;
we get the best ground truth for matching quality on curly and coily hair.

**Fit.** `notify`, `tracking` (a new private event or table), `matching` (fairness view), web (a one-page answer form
on the client's link token).

**Size.** M.

**Metric.** Rebook (stylists act on feedback); and the fairness non-negotiable gets outcome data instead of clicks.

**Risks.**
- Design direction rules out star ratings; keep it three buttons, private.
- A "No" about a named stylist is sensitive: show stylists only aggregates once there are, say, 5 answers.
- Offering a Google review link only to happy clients would be review-gating; check Google's current policy before
  building ([Google guidelines][google-gbp-guidelines]).
- Depends on idea 2 (booked events) to send at the right time.

### 7. Duplicate and reposted portfolio photo check

**The problem.**
- The whole product rests on portfolio honesty. Photo theft is common enough that Booksy has a guide on it, and the
  tell-tale is inconsistent or stock-looking work. [Booksy][booksy-plagiarism]
- AI-generated "hair photos" now circulate as if they were real work. [Axios][axios-ai-inspo]
- The brief already says: "Later, consider flagging duplicate or reposted images."

**The idea.** When a portfolio photo is embedded, compare it with every other stylist's photos (and the market's
demo photos). Near-duplicates above a high threshold go to an admin queue: "Looks like Maya's photo of 3 May". The
admin decides; nothing is hidden automatically. Exact duplicates (same SHA) are refused at upload with a friendly
message.

**Who it helps.** Both: clients trust that the work is real; stylists are protected from someone else using theirs.

**Fit.** `matching` already computes embeddings per photo; `stylists` owns the photo and moderation; the admin gets
one list. A nearest-neighbour query over `pgvector`.

**Size.** S (after real embeddings are wired: it waits on the AWS account, like matching).

**Metric.** Indirect: trust in the match list; protects the fairness checks from inflated portfolios.

**Risks.** False positives (two stylists photographing the same model at an education event). Admin-only, never
automatic. Embedding similarity on dark and coily hair may behave differently; check the threshold with the labelled
textured-hair set.

## The longer list

| Idea | Helps | Fit | Size | Note |
|---|---|---|---|---|
| Ask for 2–3 inspiration photos, including "what I don't want" | Both | `intake`, `storage`, `matching` | M | Stylists ask for several references when photos are filtered or AI ([Daily Dot][dailydot-ai]). Matching on several photos needs design; the copy tip in idea 1 is the cheap version |
| Finder result → find-my-stylist with the service filled in | Client | `consult`, `intake` | S | Already in the finder plan ([service-finder.md](service-finder.md)); listed so it isn't forgotten |
| Email the finder answers to the stylist (with the client's tick) | Stylist | `consult`, `notify` | S | Decided; still a TODO in `ConsultService.book` ([consult.md](../modules/consult.md)) |
| Salon Lofts location card: parking, entrance, which suite | Client | `stylists` (locations) | S | Admin-entered per location; step-free access stays "maybe later" until checked ([beyond-the-hair.md](beyond-the-hair.md) item 9) |
| Quiz and match page in Spanish (then Somali, Nepali) | Client | web | M | Columbus language data is in [beyond-the-hair.md](beyond-the-hair.md); stylists' languages already shown |
| "Save" stylists on the client's link page | Client | web (local) or `intake` | S | No sign-in needed; the link already holds the list |
| "Share my list" with a friend (no photo) | Client | `intake` | S | Shares stylist cards only; never the inspiration photo |
| Portfolio import from Instagram | Stylist | `stylists` | M–L | Meta retired the Instagram Basic Display API in December 2024; check what's still possible before scoping. Imports would still need hair-type tags |
| "Newest first" and "featured" portfolio order set by the stylist | Stylist | `stylists` | S | A recurring GlossGenius review request ([Capterra][glossgenius-capterra]) |
| Stylist "new clients this month" goal shown only to them | Stylist | `tracking` | S | Motivation, private |
| Visual colour-level picker in the finder ("which of these is your hair now?") | Both | `consult` | S–M | Helps the level-gap rules; content from the partner |
| Bridal and event requests | Client | `intake` | M | Different flow (dates, trials, groups); out of the pilot |
| Educational pages: "what is a curly cut", "balayage vs highlights" | Client | web | S | Content only; SEO; written with the partner |
| Swipe browsing; AI try-on | Client | — | L | Already Phase 2 in the brief |
| Stylist membership billing | — | — | — | Decided: later, about 10 weekly finder users, Stripe link off-app |

## Rejected: they break a non-negotiable

So nobody re-proposes them.

| Idea | Breaks |
|---|---|
| Live availability, open slots, or syncing with Booksy/Vagaro/GlossGenius calendars | **No calendar.** Idea 3's typed "about N weeks" is the allowed version |
| Booking inside Combpatible, even "request a time" | **No calendar** (and messaging) |
| Taking deposits, no-show fees, gift cards, tips, or a client subscription | **No payments** (clients never pay us; stylists' membership later is off-app) |
| Charging stylists per new client sent | Not a non-negotiable, but it's the competitors' most hated feature; see idea 4 |
| Chat or "ask the stylist a question" between client and stylist | **No messaging.** The one-way, opt-in emails (finder answers, idea 5) are the limit |
| Sending the client's inspiration photo or selfie to the stylist automatically | **Client photos are private.** Only with the client's tick, as the finder decided, and through presigned links |
| A public "results" gallery of clients' after photos, or reviews with photos | **Client photos are private** |
| Keeping inspiration photos so a client can "re-match later" past 30 days | **Inspiration photos are deleted on schedule** |
| Auto-booking the top match, or hiding stylists who weren't recommended | **Recommendations are suggestions;** the directory stays complete |
| Paid "boost" or sponsored placement in the match list | **Recommendations are suggestions** (they'd stop being honest suggestions) and **fairness** (money, not work on similar hair, would move the order) |
| Ranking by star ratings, popularity, bookings or response speed | **Fairness measured across hair types:** in a 20–40 stylist pool, popularity signals entrench whoever is already busy and can't be checked per hair type the way portfolio similarity can |
| A self-declared "textured-hair specialist" badge that affects ranking | **Fairness** (already rejected in [beyond-the-hair.md](beyond-the-hair.md): the portfolio proves it) |
| An AI that picks the service, or answers clients' hair questions | Not a non-negotiable but a decision (2026-11-22): the finder runs on the partner's rules, never AI |

## Open questions for Zach

Answered 2026-11-25 by Zach: "I look moving forward with all of these."

1. **Which to build first?** All of them, except idea 5. Order: 1 and 3 together (both are profile fields in
   `stylists`) alongside 2; then 4, 6 (needs 2) and 7.
2. **Idea 3: may "not taking new clients" remove a stylist from new matches?** **No.** Zach: "Not taking clients
   should not prevent them from matching." The stylist stays in matching and the directory; the match card shows the
   status and the wait. The 30-day confirm still applies.
3. **Idea 4: never charge per client for clients from a stylist's own page?** **Agreed.** Zach: "never charge the
   stylists". Whether that also rules out the later membership (CLAUDE.md, 2026-11-22) is still to confirm.
4. **Idea 5: rebook reminder.** **Dropped.** Zach: "no reminder emails, that should come from the stylist directly."
5. **Idea 6: who gets the check-in?** **All clients,** not a pilot of 100. Clients who gave an email get one email
   about 10 days after `BOOKED`; clients without one see the question on their link page instead, and still get no
   email. Stylists see aggregates of 5 or more (proposed, not contradicted).
6. **Who writes the generic first-visit notes?** **Jason.** Ours is the first draft:
   [first-visit-notes.md](first-visit-notes.md). Like the finder, ship our draft and don't credit Jason until his
   version is in.
7. **Would stylists open a weekly digest?** **Yes,** per Zach.

## Sources

The link labels used in the text. Several are vendor surveys or user reviews; treat them as direction, not proof.

- onlycurls-prep: <https://onlycurls.com/blogs/tutorials/how-to-prepare-for-a-curly-haircut>
- fashion-curly: <https://fashionmagazine.com/beauty-grooming/texture-talk-curly-cut/>
- warehouse-strand: <https://thewarehousesalon.com/blogs/hair-blogs/why-hair-stylists-may-perform-a-test-strand-before-your-color-service>
- opentext-consult: <https://opentextbc.ca/haircolourforhairstylistslevel2/chapter/colour-assessment/>
- axios-ai-inspo: <https://axios.com/2026/04/18/ai-generated-hair-makeup-inspo-pictures>
- cbc-ai-inspo: <https://amp.cbc.ca/news/canada/edmonton/ai-generated-images-creating-unrealistic-expectations-hairdressers-say-1.7378463>
- bl-colour-expectations: <https://www.beautylaunchpad.com/business/education/article/21151359/how-to-manage-clients-color-expectations>
- nc-finding: <https://www.naturallycurly.com/curlreading/salons/a-cut-above-the-rest-finding-a-stylist-for-curly-hair>
- boulevard-retention: <https://www.salontoday.com/1088748/boulevard-report-reveals-top-performing-salons-retain-56-more-first-time-visitor>
- styleseat-trustpilot: <https://nl.trustpilot.com/review/styleseat.com>
- booksy-trustpilot: <https://www.trustpilot.com/review/booksy.com>
- styleseat-capterra: <https://capterra.com/p/176521/StyleSeat/reviews/>
- boulevard-choose: <https://www.businesswire.com/news/home/20220125005455/en>
- square-loyalty: <https://squareup.com/us/en/press/client-loyalty>
- creativehead-books: <https://creativeheadmag.com/documents/is-not-accepting-new-clients-a-badge-of-honour-or-a-death-sentence>
- acuity-manage: <https://www.acuity.com/the-focus/stylist/how-to-efficiently-manage-clients>
- tresemme-bias: <https://www.tresemme.com/us/en/about-us/future-stylists-fund/about-future-stylists-fund/hair-bias.html>
- salontoday-referrals: <https://www.salontoday.com/articles/bumping-up-referrals>
- salonlofts-gbp: <https://salonlofts.com/blog/setting-up-your-google-business-profile>
- styleseat-vs-booksy: <https://www.styleseat.com/join/booksy-vs-styleseat>
- styleseat-howoften: <https://www.styleseat.com/blog/how-often-should-you-get-haircut/>
- marieclaire-howoften: <https://marieclaire.com/beauty/a34426134/how-often-to-cut-your-hair>
- mindbody-retention: <https://www.mindbodyonline.com/en-au/business/education/blog/client-retention-salon-spa>
- thegrio-ulta: <https://thegrio.com/2026/10/19/why-more-black-women-are-turning-to-ulta-beauty-for-hair-services/>
- nbc-black-hair: <https://www.nbcnews.com/news/us-news/black-hair-industry-stylists-frustration-instagram-tiktok-rcna139041>
- r29-black-hair: <https://refinery29.com/en-us/2022/04/10944760/black-hair-salons-stylists-bad-experiences>
- google-gbp-guidelines: <https://support.google.com/business/answer/3038177>
- booksy-plagiarism: <https://biz.booksy.com/en-gb/blog/photo-plagiarism-when-someone-steals-your-work-v>
- dailydot-ai: <https://dailydot.com/news/ai-hair-inspo-photos/>
- glossgenius-capterra: <https://www.capterra.com/p/174830/GlossGenius/reviews/>

[onlycurls-prep]: https://onlycurls.com/blogs/tutorials/how-to-prepare-for-a-curly-haircut
[fashion-curly]: https://fashionmagazine.com/beauty-grooming/texture-talk-curly-cut/
[warehouse-strand]: https://thewarehousesalon.com/blogs/hair-blogs/why-hair-stylists-may-perform-a-test-strand-before-your-color-service
[opentext-consult]: https://opentextbc.ca/haircolourforhairstylistslevel2/chapter/colour-assessment/
[axios-ai-inspo]: https://axios.com/2026/04/18/ai-generated-hair-makeup-inspo-pictures
[cbc-ai-inspo]: https://amp.cbc.ca/news/canada/edmonton/ai-generated-images-creating-unrealistic-expectations-hairdressers-say-1.7378463
[bl-colour-expectations]: https://www.beautylaunchpad.com/business/education/article/21151359/how-to-manage-clients-color-expectations
[nc-finding]: https://www.naturallycurly.com/curlreading/salons/a-cut-above-the-rest-finding-a-stylist-for-curly-hair
[boulevard-retention]: https://www.salontoday.com/1088748/boulevard-report-reveals-top-performing-salons-retain-56-more-first-time-visitor
[styleseat-trustpilot]: https://nl.trustpilot.com/review/styleseat.com
[booksy-trustpilot]: https://www.trustpilot.com/review/booksy.com
[styleseat-capterra]: https://capterra.com/p/176521/StyleSeat/reviews/
[boulevard-choose]: https://www.businesswire.com/news/home/20220125005455/en
[square-loyalty]: https://squareup.com/us/en/press/client-loyalty
[creativehead-books]: https://creativeheadmag.com/documents/is-not-accepting-new-clients-a-badge-of-honour-or-a-death-sentence
[acuity-manage]: https://www.acuity.com/the-focus/stylist/how-to-efficiently-manage-clients
[tresemme-bias]: https://www.tresemme.com/us/en/about-us/future-stylists-fund/about-future-stylists-fund/hair-bias.html
[salontoday-referrals]: https://www.salontoday.com/articles/bumping-up-referrals
[salonlofts-gbp]: https://salonlofts.com/blog/setting-up-your-google-business-profile
[styleseat-vs-booksy]: https://www.styleseat.com/join/booksy-vs-styleseat
[styleseat-howoften]: https://www.styleseat.com/blog/how-often-should-you-get-haircut/
[marieclaire-howoften]: https://marieclaire.com/beauty/a34426134/how-often-to-cut-your-hair
[mindbody-retention]: https://www.mindbodyonline.com/en-au/business/education/blog/client-retention-salon-spa
[thegrio-ulta]: https://thegrio.com/2026/10/19/why-more-black-women-are-turning-to-ulta-beauty-for-hair-services/
[nbc-black-hair]: https://www.nbcnews.com/news/us-news/black-hair-industry-stylists-frustration-instagram-tiktok-rcna139041
[r29-black-hair]: https://refinery29.com/en-us/2022/04/10944760/black-hair-salons-stylists-bad-experiences
[google-gbp-guidelines]: https://support.google.com/business/answer/3038177
[booksy-plagiarism]: https://biz.booksy.com/en-gb/blog/photo-plagiarism-when-someone-steals-your-work-v
[dailydot-ai]: https://dailydot.com/news/ai-hair-inspo-photos/
[glossgenius-capterra]: https://www.capterra.com/p/174830/GlossGenius/reviews/
