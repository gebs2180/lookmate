# Beyond the hair: what clients want from a stylist besides the cut

Research and a recommendation, 2026-11-20. Nothing here is built: Zach decides first (open questions at the end).
Read with [brief.md](../brief.md), [modules/intake.md](../modules/intake.md), [modules/stylists.md](../modules/stylists.md)
and the principles, tone and inclusivity sections of [ux/design-direction.md](../ux/design-direction.md).

## The short answer

Clients care a lot about what happens in the chair besides the cut, but most of it is about **how the stylist treats
them** (listening, honesty, price up front, time, a quiet chair if they want one), not about **who the stylist is**.
Combpatible should:

1. Let stylists describe their chair with a short checklist of practices (quiet appointments, sensory-friendly,
   gender-neutral pricing, LGBTQ+-affirming, kids welcome, languages), shown on the profile and as directory filters.
   **Never used for ranking.**
2. Add prices a client can read before booking ("Cuts from $65", consultation offered or not).
3. Add **no new quiz screen.** Extend the existing "What should your next stylist get right?" step with two chips
   (a quiet appointment; honest advice about what my hair can do) and use the answers to highlight what's relevant on
   each match card.
4. **Not** add political views, religion, or the stylist's own identity (race, gender, sexuality, "-owned" labels) as
   fields, filters or ranking inputs. Stylists can still say what they like in their own bio.

Ranking stays what it is: portfolio work on similar hair. That's the product's promise and the thing the fairness
checks measure; self-reported soft attributes can't be verified and would muddy both.

## Ranked recommendation

| Rank | Attribute | Verdict | Who sets it | How it's used |
|---|---|---|---|---|
| 1 | Price up front (from-price per service, consultation) | **Add now** | Stylist | Profile and match card; existing budget tier stays the filter |
| 2 | Quiet appointments on request | **Add now** | Stylist offers; client can ask for it (chip in the existing step) | Profile, directory filter, highlighted on the match card when the client asked |
| 3 | Honest consultation | **Add now** (client side only) | Client (chip); stylist states whether consultations are offered (part of 1) | Echoed back to the client; consultation shown on the profile |
| 4 | Gender-neutral pricing (priced by service and time, not gender) | **Add now** | Stylist | Profile, directory filter |
| 5 | LGBTQ+-affirming | **Add now** | Stylist | Profile, directory filter |
| 6 | Languages spoken | **Add now** | Stylist | Profile, directory filter |
| 7 | Sensory-friendly on request (music down, unscented products, no clippers) | **Add now** | Stylist | Profile, directory filter |
| 8 | Kids welcome | **Add now** (low priority) | Stylist | Profile only |
| 9 | Step-free access | **Maybe later** | Admin, per Salon Lofts location (verified) | Location card |
| 10 | Punctuality and pace | **Maybe later**, measured not declared | Clients, through the follow-up email | Admin and stylist insight; never a public score |
| 11 | Textured-hair experience as a badge | **Don't add** a badge; the portfolio already proves it | (Portfolio tags) | Matching, as today |
| 12 | Chatty vs quiet personality matching | **Don't add** to ranking | — | Covered by 2 |
| 13 | Stylist identity labels (Black-owned, LGBTQ+-owned, women-owned, etc.) | **Don't add** as fields or filters now; bio free text is fine | — | — |
| 14 | Political views or values labels | **Don't add**, in any form | — | — |

"Add now" means: ready for Zach to decide and for a later session to build. Every "Add now" item except 2 and 3 is on
the stylist side and costs the client nothing.

## What the evidence says

### Why clients leave, and come back

- Square's 2024 survey of US and Canadian clients who had strayed from their stylist: hard to book (57%), too
  expensive (53%), someone moved (50%), **stylist didn't listen (41%)**, **incompatible personalities (34%)**. 57% went
  back to the original stylist, and easy rescheduling (82%) was the most-cited reason. [Square][square-loyalty]
- A 2026 survey of clients of high-earning stylists (secondhand summary only) says inconsistent experiences, not price,
  lose high-paying clients. [Modern Hairstylist podcast][modern-hairstylist]

Two of those reasons are ours already: "didn't listen" is the brief's founding finding and our quiz's `DIDNT_LISTEN`;
"incompatible personalities" at 34% is the evidence that the chair matters beyond the cut. Booking and scheduling we
deliberately leave to the stylist's own booking tool (no calendar, CLAUDE.md).

Our quiz's past-issues step (`web/src/pages/public/quiz.ts`) already captures several of the attributes below:
`RUSHED`, `PRICE_SURPRISE`, `HARD_TO_BOOK`, `NO_EXPERIENCE_WITH_MY_HAIR_TYPE`. That's the right place for more, not a
new screen.

### Conversation: chatty, quiet, or reads the room

- A UK survey of 2,000 consumers and 100 stylists (Ripe, an insurer, so treat cautiously): **53% of clients and 56%
  of stylists would prefer "silent" services**. [HJI][hji-silent]
- News coverage since 2022 shows quiet appointments spreading at independent salons in the US, UK and Canada, mostly
  as a booking option or intake question ("quiet chair", "silent service"). [Northeastern][northeastern-silent],
  [Fox][fox-silent], [CTV][ctv-silent], [CBS Minnesota][cbs-quiet-chair], [NewBeauty][newbeauty-silent]
- A psychologist quoted in coverage notes the benefit for people with social anxiety. [CTV][ctv-silent]
- The booking platforms treat it as an intake-form question or a separate service, not a profile filter: Fresha's
  guidance is an intake form asking whether the client prefers silence or conversation [Fresha][fresha-silent];
  Setmore suggests a "silent" service or a custom intake field. [Setmore][setmore-silent] We found no chain (Supercuts,
  Great Clips, Drybar) offering it.
- Not everyone wants it: many clients value the chat, and stylists disagree that it'll become the norm.
  [CTV][ctv-silent]

So: a large minority to a majority would use it, it costs the stylist nothing, and it's not a personality type to
match on, it's a request. Salon Lofts suites are private rooms with one client at a time, which makes a quiet,
low-stimulus appointment easier to offer there than in an open salon: a point worth making in stylist onboarding.

### Consultation and honesty

The brief's founding finding ("didn't listen") and the design principle "Say it back" cover the listening half. The
honesty half ("tell me if my hair can't do this") is a frequent theme in stylist advice and forums but we found no
survey figure for it. It's not something a stylist can credibly self-declare ("I'm honest"), so we let the client say
it matters (a chip) and the stylist say whether they offer a consultation before the appointment, which is the
practical form honesty takes.

### Price transparency

- 53% of strayers cited cost [Square][square-loyalty]; in a UK survey only **42% of clients believed salon pricing was
  transparent**, and over half didn't know what they'd spend before a visit (insurer's blog, methodology not
  published). [Ripe][ripe-etiquette]
- Hidden add-ons ("additional styling time", products) are a common complaint in coverage, without survey numbers.
  [Fashion Journal][fashion-journal-pricing]
- `PRICE_SURPRISE` is already one of our quiz answers, and the design direction's open question 3 already asks whether
  to show dollar ranges per tier.

### Textured hair, as a cultural matter

- In a 2020 survey of 1,000 Black women (commissioned by TRESemmé, fielded by KRC Research), **86% had trouble finding
  consistent, quality salon care**, 47% only use one stylist and skip the appointment if that stylist is unavailable,
  and 85% said salons should serve Black clients better. 61% of stylists said it's fair to charge extra for textured,
  coily or kinky hair (63% of white stylists, 46% of Black stylists). [TRESemmé][tresemme-bias]
- The experience includes bias in the chair, not just skill. [TRESemmé][tresemme-bias], [Glossy][glossy-textured]

This is the problem Combpatible already solves with evidence instead of claims: portfolio photos tagged by hair type,
reasons that name the hair type, and fairness checks on curly and coily matches. A self-declared "textured hair
specialist" badge would undo that: anyone can tick it. The 61% figure is the reason to show a from-price clearly
and to let a stylist say if price varies by length or density (open question 4), rather than to add a label.

### LGBTQ+-affirming and gender-neutral pricing

- Vagaro's 2024 survey of 300 LGBTQIA+ beauty consumers (with Talker Research): about two-thirds feel at least somewhat
  represented; **30% said LGBTQIA+ staff representation mattered when booking hair**, most of all for Gen Z; 62% said
  hair is crucial to gender expression. [Vagaro][vagaro-lgbtq], [American Med Spa Association][amspa-vagaro]
- Vagaro created an "LGBTQIA+ friendly" tag for businesses. [GCI Magazine][gci-vagaro] Google Business Profiles offer
  self-set "LGBTQ+ friendly" and "Transgender safe space" attributes (190,000+ businesses had set them) and, since 2022,
  an "LGBTQ+ owned" one shown as "Identifies as…". [Google][google-lgbtq-attrs], [Google][google-lgbtq-owned] Yelp has
  an opt-in "Open to All" pledge (635,000+ businesses at one point) and an LGBTQ-owned filter.
  [Washington Blade][blade-open-to-all], [Business Insider NL][bi-yelp], [Restaurant Dive][restaurantdive-yelp]
- Gender-neutral pricing: in a UK trade body's survey 42% of salons price haircuts without gender, up from about 16%
  in 2016; the main barrier is client confusion (50%). [NHBF][nhbf-pricing] US salons that switched price by time or
  length. [Square][square-gender-neutral], [CNN via KRDO][krdo-pricing] California and New York ban charging more for a
  service because of gender; we found no such Ohio or Columbus law.

The well-used pattern is a **self-set, opt-in practice label, never a "no"**. Gender-neutral pricing is checkable
(prices are by service, not by "men's" and "women's" cut), which makes it the strongest of the inclusion labels.

### Sensory, neurodivergent and mobility needs

- Sensory-friendly practice is a recognised niche: dim lights, soft or no music, scissors instead of clippers, empty
  salon times, a private room. [NPR][npr-sensory], [Beaming Health][beaming-sensory], [HJI][hji-autism],
  [Delco Today][delco-sensory], [American Salon][americansalon-neuro] No survey figure; demand is mainly parents of
  autistic children and neurodivergent adults.
- Step-free access is a property of the building (and the suite), which a stylist may not know accurately. Better set
  per Salon Lofts location by the admin after checking.

We should not ask clients about disability or neurodivergence. A client who wants a calmer appointment picks "a quiet
appointment"; no reason needed.

### Language

Columbus has sizeable Spanish-, Somali- and Nepali-speaking communities: in parts of Columbus 5–7% of households speak
Spanish and 3–5% an Afro-Asiatic language group that includes Somali; among Franklin County immigrants about 16% speak
Spanish, 9% Somali and 5% Nepali. [Data USA (east)][datausa-east], [Data USA (north)][datausa-north],
[Franklin County][franklin-languages] About 90% of the metro speaks only English at home. [Stacker][stacker-languages]
Languages are a fact the stylist knows, cost nothing to show, and matter a lot to the few who need them.

### Kids

Kid-friendly cuts are mostly a parent's search ("sensory-friendly kids' haircuts"), and many Salon Lofts stylists may
not want them. Low priority; profile line only. Note the legal point below: a label may say "kids welcome", never
"no kids".

### What the booking platforms do

| Platform | What we could confirm |
|---|---|
| Vagaro | Self-set "LGBTQIA+ friendly" tag [GCI Magazine][gci-vagaro] |
| Fresha | Quiet vs chat as an intake-form question [Fresha][fresha-silent]; languages on a professional's profile [Firecrawl listing][firecrawl-fresha] |
| StyleSeat | Ranks on distance, tagged photos, reviews and transaction volume; a "Top Pro" badge; no identity or language filters found [StyleSeat][styleseat-ranking], [StyleSeat][styleseat-top-pro] |
| Booksy | Category and service search; LGBTQ+ inclusion as blog advice to businesses, no filter found [Booksy][booksy-inclusivity] |
| Google, Yelp | Self-set inclusion and owner-identity attributes, shown as "Identifies as…" [Google][google-lgbtq-owned], [Washington Blade][blade-open-to-all] |

None of the booking platforms ranks on soft attributes; at most they label. Their consumer-side filter menus are not
well documented, so "not found" is not "doesn't exist".

## Values and politics

### Do clients want it?

- Partisanship does change who people buy from: in experiments, consumers were almost twice as likely to complete a
  transaction with a seller of their own party, and most partisans gave up money to avoid benefiting the other side.
  [McConnell et al. 2018][mcconnell]
- But most Americans don't want businesses to take sides: Gallup–Bentley's latest reading has 53% saying businesses
  should not take a public stance (28% of Republicans and 62% of Democrats say they should) [Gallup][gallup-stance];
  Ipsos has 57% wanting companies to stay neutral [Ipsos][ipsos-neutral]; Sprout Social found only 24% think it
  important that brands take a stand, down from 70% in 2019. [Sprout Social][sprout-activism]
- In the chair, the dominant advice from stylists and trade bodies is a politics-free appointment, and political talk
  is a common complaint, from both sides of the chair. [Associated Hair Professionals][ahp-politics],
  [Behind the Chair][btc-politics], [Yahoo][yahoo-hairdressers], [Fox 5][fox5-politics]

The demand that does exist is mostly a wish to **avoid political conversation** and to **feel safe** (for LGBTQ+
clients especially), not to pick a stylist by party.

### The law (not legal advice; the lawyer reviewing the stylist terms should look at this too)

- **Ohio:** a salon is a place of public accommodation (barbershops are named in the definition). It may not deny
  service because of race, colour, religion, sex, military status, national origin, disability, age (40+) or
  ancestry. [ORC 4112.01][orc-4112-01], [ORC 4112.02][orc-4112-02] Whether "sex" covers sexual orientation and gender
  identity in public accommodations is unsettled in Ohio: the Attorney General's 2023 opinion questions the Civil
  Rights Commission's reading, and the Ohio Fairness Act that would add them hasn't passed.
  [Ohio AG opinion 2023-006][ohio-ag-2023], [UC Law Review][uc-fairness-act]
- **Columbus** (City Code chapter 2331) goes further: race, sex, **sexual orientation, gender identity or expression**,
  colour, religion, ancestry, national origin, age, disability, **familial status** and **military status**, in
  employment, housing and public accommodation. [Columbus CRC][columbus-crc], [Columbus code 2331][columbus-2331]
- **Political affiliation is protected by neither.** So a politics label would be legal. That doesn't make it wise
  (below).
- **Platform filters by a protected class have a precedent.** Facebook settled five lawsuits in 2019 by removing
  age, gender and protected-class targeting from housing, job and credit ads. [ACLU][aclu-facebook],
  [CNBC][cnbc-facebook] Hair services aren't housing or jobs, but a directory filter that lets clients exclude
  stylists by race, sex or sexuality is the same shape, inside a city that protects all three.

What the law means for the labels we do add: **describe practices and services, never which people the stylist will
serve.** "Kids welcome" is fine; "No kids" sits badly with Columbus's familial-status protection. "Gender-neutral
pricing" is fine; "Women only" is not. Every stylist on Combpatible must serve every client the law protects, whatever
they tick.

### Risks of a values or politics label

- **To stylists' business.** Any political label loses roughly half the market for that stylist (the McConnell effect),
  and a blank invites guesses. Independent stylists at Salon Lofts live on rebooking; we'd be putting their politics
  on a page they didn't need it on.
- **Polarisation.** It turns a hair app into a sorting machine for a culture war, and it would be screenshotted.
- **Fairness.** Party preference correlates strongly with race and religion in the US, so a politics filter is
  close to a race and religion filter by another name: exactly what our hair-type fairness checks exist to prevent,
  and a protected-class problem in Columbus.
- **Privacy.** Political opinions are special-category data under GDPR.
  We'd hold it about stylists, for no matching benefit, before expanding beyond Ohio.
- **Small pool.** With 20–40 pilot stylists, any identity or values filter can single out one or two named people.
  An "LGBTQ+-owned" filter on 30 stylists is close to a list of who's out.

### Recommendation on values and politics

- **Don't include political views, party, religion or a generic "values" field**, as a profile field, a quiz question,
  a filter or a ranking signal.
- **Do include opt-in practice labels** that describe what happens in the chair and that every client can hold the
  stylist to: LGBTQ+-affirming, gender-neutral pricing, quiet appointments, sensory-friendly. These are what
  "values" mostly means to clients in this setting, and they are the pattern Vagaro, Google and Yelp already use.
- **Don't add identity labels about the stylist** ("Black-owned", "LGBTQ+-owned", "woman-owned") as structured fields
  or filters for the pilot, for the small-pool and fairness reasons above. A stylist who wants to say it can, in their
  own bio, in their own words. Revisit once there are hundreds of stylists per market (open question 6).
- **For clients who just want politics out of the chair**, the quiet-appointment option covers it without anyone
  having to name a side.

## Each attribute in detail

"Ranking" below always means: **no** for the pilot. Ranking stays portfolio similarity on compatible hair. The reasons,
once: self-reported attributes can't be verified; they'd reward ticking boxes over real work; they'd confound the
fairness checks (a curly-hair gap could hide behind a soft-attribute boost); and with 20–40 stylists any boost moves
named people. The match card highlights a stylist's attribute when it answers something the client asked for, which
gets the benefit without touching the order.

### 1. Price up front — add now

- **How much clients care:** high. Cost is the second reason clients stray (53%) [Square][square-loyalty]; 42% think
  salon pricing is transparent [Ripe][ripe-etiquette]; `PRICE_SURPRISE` is already a quiz answer.
- **Who sets it:** the stylist: a from-price per specialty ("Cut from $65"), an optional note when price depends on
  length or density, and whether a consultation is free, paid or included.
- **Use:** profile and match card. The existing `price_tier` and the quiz's budget stay the filter.
- **Risk:** low privacy risk (business information). Fairness: the 61% of stylists who think a textured-hair surcharge
  is fair [TRESemmé][tresemme-bias] means some from-prices will differ by hair type; show them plainly rather than hide
  them, and watch Book-click rates by hair type as we already do.
- **Wording:**
  - Stylist form: "What does a client pay? Give a starting price for each service you offer."
  - Profile: "Cuts from $65 · Color from $120" / "Free 15-minute consultation" / "Price depends on length and density.
    Ask at your consultation."

### 2. Quiet appointments — add now

- **How much clients care:** 53% of UK clients said they'd prefer silent services [HJI][hji-silent]; widely covered in
  the US since 2022. Also partly covers sensory needs and the wish to avoid political talk.
- **Who sets it:** both. The stylist ticks "Quiet appointments on request". The client can pick a chip in the
  existing step. **Decided 2026-11-20 (Zach): client side only; not a stylist label** (open question 1).
- **Use:** profile; directory filter; on the match card, highlighted when the client asked ("Offers quiet
  appointments"). Since booking leaves us, the results page tells the client how to ask: "Mention it when you book."
- **Risk:** none for privacy (the client gives no reason). Fairness: none expected; check chip use by hair type anyway,
  since all quiz codes feed the fairness metrics.
- **Wording:**
  - Client chip: "A quiet appointment"
  - Stylist checkbox: "Quiet appointments on request. You'll do the consultation, then let them relax."
  - Profile: "Quiet appointments on request"
  - Results line, when asked: "Jordan offers quiet appointments. Mention it when you book."

### 3. Honest consultation — add now (client chip)

- **How much clients care:** "didn't listen" is 41% of strayers' reasons [Square][square-loyalty] and the brief's core
  finding; honesty about what hair can do is a common forum theme, no survey figure.
- **Who sets it:** the client (chip). The stylist side is the consultation line in item 1; we don't let stylists
  claim "honest".
- **Use:** echoed back ("You asked for honest advice about what your hair can do"); appears in the admin's match log.
  No ranking, no filter.
- **Risk:** none.
- **Wording:** client chip "Honest advice about what my hair can do".

### 4. Gender-neutral pricing — add now

- **How much clients care:** moderate and growing; 42% of UK salons already price this way [NHBF][nhbf-pricing]; it
  matters most to women with short hair, men with long hair, and trans and nonbinary clients.
- **Who sets it:** stylist.
- **Use:** profile, directory filter.
- **Risk:** checkable: if a stylist ticks it, their from-prices (item 1) shouldn't be split by gender. The admin can
  spot-check. No privacy issue.
- **Wording:** stylist checkbox "My prices depend on the service and your hair, not your gender." Profile:
  "Gender-neutral pricing".

### 5. LGBTQ+-affirming — add now

- **How much clients care:** 30% of LGBTQIA+ consumers weighed staff representation when booking hair
  [Vagaro][vagaro-lgbtq]; a widely used label on Google, Yelp and Vagaro.
- **Who sets it:** stylist, opt-in.
- **Use:** profile, directory filter. Never shown as "No"; an unticked box shows nothing.
- **Risk:** it's about the stylist's practice, not their identity, so it doesn't out anyone. It can't be verified;
  the admin can remove it on a credible complaint (the same as hiding a profile). Fairness: a blank must not read as a
  refusal; every stylist must serve everyone (Columbus 2331).
- **Wording:** stylist checkbox "LGBTQ+-affirming: I use clients' names and pronouns and style for how they want to
  look." Profile: "LGBTQ+-affirming".

### 6. Languages — add now

- **How much clients care:** a small share of clients, but decisive for them (Columbus's Spanish, Somali and Nepali
  speakers above).
- **Who sets it:** stylist, from a list plus "Other". English assumed.
- **Use:** profile, directory filter. Not asked in the quiz (the quiz itself is English; translating it is a separate
  question).
- **Risk:** language is close to national origin; it's fine as a service the stylist offers, not as a proxy filter
  for the stylist's background. Show it only when the stylist chose to.
- **Wording:** stylist "Languages you can do a consultation in". Profile: "Also speaks Spanish".

### 7. Sensory-friendly on request — add now

- **How much clients care:** a niche with clear, documented demand (parents of autistic children, neurodivergent
  adults). [NPR][npr-sensory], [HJI][hji-autism]
- **Who sets it:** stylist. The client side is the quiet chip (no diagnosis asked).
- **Use:** profile, directory filter.
- **Risk:** don't collect anything about the client's condition. The label must describe what the stylist will
  actually do.
- **Wording:** stylist checkbox "Sensory-friendly on request: music down or off, unscented products, scissors instead of
  clippers." Profile: "Sensory-friendly on request".

### 8. Kids welcome — add now (low priority)

- **How much clients care:** for parents, high; for most Combpatible clients, irrelevant.
- **Who sets it:** stylist.
- **Use:** profile only for the pilot.
- **Risk:** offer only the positive ("Kids welcome"), never "Adults only" (familial status, Columbus 2331).
- **Wording:** "Kids' cuts welcome".

### 9. Step-free access — maybe later

- **How much clients care:** essential for wheelchair users; no survey figure found.
- **Who sets it:** the admin, per location, after checking (entrance, lift, suite, restroom). Stylists may add "I can
  work with a client who stays in their wheelchair."
- **Use:** location card and profile.
- **Risk:** a wrong "accessible" is worse than none. Needs someone to check each Salon Lofts location.
- **Wording:** "Step-free entrance and accessible restroom" (only what's been checked).

### 10. Punctuality and pace — maybe later

- **How much clients care:** high (`RUSHED` is already a quiz answer; uneven experiences lose high-paying clients
  [Modern Hairstylist][modern-hairstylist]). Everyone would self-declare "on time", so a field is useless.
- **Who sets it:** clients, after the appointment, through the follow-up the results page already mentions ("We may
  ask how your appointment went"), only for clients who gave an email.
- **Use:** private feedback to the stylist and the admin. No public score (the design direction rules out star
  ratings).
- **Wording:** "Did Jordan listen? Did you get the look? Was the appointment on time?"

### 11–14. Don't add

- **Textured-hair badge:** the portfolio and tags prove it better than a checkbox; a badge lets anyone claim it.
- **Chatty/quiet personality matching:** a request, not a type; covered by item 2.
- **Stylist identity labels and politics:** see "Values and politics". Bio free text stays open for a stylist's own
  words; the admin's normal moderation applies.

## What changes in the quiz

**No new screen.** The design direction already proposes rewording the past-issues step as "What should your next
stylist get right?" with positive labels mapped 1:1 to codes. Add two chips to it:

| Chip | New code |
|---|---|
| A quiet appointment | `QUIET_APPOINTMENT` |
| Honest advice about what my hair can do | `HONEST_ADVICE` |

That's a V-migration on the `past_issues` check constraint and an API change (OpenAPI and `api:types`). The answers
are quiz codes like the others: kept after contact anonymisation for the fairness metrics, never shown to stylists by
name. Languages, LGBTQ+-affirming and the rest are **directory filters**, not quiz questions: the client who needs one
uses it; nobody else is slowed down.

## What changes for stylists

One new profile section, "What to expect in my chair", on one screen with checkboxes, plus from-prices per specialty.
Optional; it doesn't count toward going live. Thumb-first: tick boxes, no typing needed (from-prices are number
inputs).

## Measuring it (fairness)

- Add each new chip to the admin's fairness view by hair type, like the existing past-issue codes.
- Check that directory filters aren't systematically hiding stylists who specialise in curly and coily hair (for
  example, if most of them don't tick boxes, filtered results would lose them). If so, prompt every stylist equally
  during onboarding.
- Nothing in this doc changes ranking, so the existing Book-click-by-hair-type check stays valid.

## Open questions for Zach

1. ~~**Which "Add now" items?**~~ **Decided 2026-11-20 (Zach):** stylist labels for gender-neutral pricing,
   LGBTQ+-affirming, sensory-friendly on request, kids welcome and languages, plus from-prices and consultation.
   Quiet appointments are **not** a stylist label: the client asks for one (the chip). All eight items otherwise.
2. ~~**Directory filters or profile only?**~~ **Decided 2026-11-20 (Zach): profile only.** No filters, nothing in
   ranking or matching. Revisit filters once there are more stylists.
3. **Ask the Salon Lofts advisory-board stylist** which labels stylists would actually tick, and whether any would feel
   pressured to tick "LGBTQ+-affirming" or "gender-neutral pricing" to avoid looking unwelcoming.
4. **Textured-hair pricing.** Show from-prices as stylists set them, including any surcharge by hair type, or ask
   stylists to price by length and density instead (the same question as the design direction's open question 3)?
5. **Who removes a false label?** Proposal: the admin, on a credible client complaint, with a note to the stylist.
   Agree before launch.
6. **Identity labels later?** Revisit "-owned" labels once a market has enough stylists that a filter can't single
   people out (say 200+)? Or never as filters, only bio text?
7. ~~**"Politics-free chair" as a stylist option?**~~ **Decided 2026-11-20 (Zach): no.** The client's quiet
   appointment covers it.
8. **Lawyer.** Add to the terms review: practice labels and Columbus 2331, and whether Combpatible itself counts as a
   public accommodation for its directory filters.
9. **Follow-up feedback (item 10)** needs the post-appointment email to exist first. Pilot it with the first 100
   matched clients who gave an email?

## Sources

The link labels used in the text, in the order they first appear:

- square-loyalty: <https://squareup.com/us/en/press/client-loyalty>
- modern-hairstylist: <https://www.buzzsprout.com/1896666/episodes/18993165-the-2026-salon-consumer-behavior-survey-results-are-in>
- hji-silent: <https://hji.co.uk/56-of-hairstylists-in-favour-of-silent-services-according-to-research>
- northeastern-silent: <https://news.northeastern.edu/2024/05/23/silent-appointments-salon-trend>
- fox-silent: <https://fox9.com/news/silent-salons-quiet-hair-appointments-popular>
- ctv-silent: <https://edmonton.ctvnews.ca/cut-the-chat-the-rise-in-popularity-of-a-silent-service-at-the-salon-1.5831579>
- cbs-quiet-chair: <https://www.cbsnews.com/amp/minnesota/news/quiet-chair-salon>
- newbeauty-silent: <https://www.newbeauty.com/silent-hair-salons>
- fresha-silent: <https://www.fresha.com/blog/give-your-clients-the-silent-treatment-with-fresha-forms>
- setmore-silent: <https://setmore.com/blog/silent-services>
- ripe-etiquette: <https://www.ripeinsurance.co.uk/small-business/hair-and-beauty-insurance/blog/salon-etiquette-the-uks-awkward-hair-salon-misunderstandings>
- fashion-journal-pricing: <https://fashionjournal.com.au/beauty/hidden-hair-pricing/>
- tresemme-bias: <https://www.tresemme.com/us/en/about-us/future-stylists-fund/about-future-stylists-fund/hair-bias.html>
- glossy-textured: <https://www.glossy.co/beauty/the-next-frontier-of-inclusive-beauty-is-textured-hair-styling/>
- vagaro-lgbtq: <https://www.vagaro.com/news/press-release/lgbtqia-representation-hair-and-beauty-industry>
- amspa-vagaro: <https://americanmedspa.org/news/vagaro-research-highlights-lgbtqia-hair-beauty-consumer-perspectives>
- gci-vagaro: <https://www.gcimagazine.com/home/news/22914552/vagaro-do-lgbtqia-consumers-feel-represented-in-the-hair-and-beauty-industry>
- google-lgbtq-attrs: <https://blog.google/company-news/outreach-and-initiatives/small-business/adding-lgbtq-friendly-and-transgender-safe-space-attributes-google-my-business/>
- google-lgbtq-owned: <https://blog.google/company-news/outreach-and-initiatives/diversity/lgbtq-owned-businesses/>
- blade-open-to-all: <https://www.washingtonblade.com/2018/08/03/yelp-releases-open-to-all-feature-to-battle-discrimination/>
- bi-yelp: <https://businessinsider.nl/how-yelp-is-innovating-its-product-and-using-its-voice-to-drive-social-change-2/>
- restaurantdive-yelp: <https://www.restaurantdive.com/news/yelp-adds-lgbtq-owned-business-search-filter/600649>
- nhbf-pricing: <https://nhbf.co.uk/news-and-blogs/blog/major-shift-towards-gender-neutral-pricing>
- square-gender-neutral: <https://squareup.com/us/en/townsquare/gender-neutral-pricing-beauty-business>
- krdo-pricing: <https://krdo.com/news/2024/07/27/why-do-womens-haircuts-cost-more-than-mens/>
- npr-sensory: <https://www.npr.org/2025/03/05/nx-s1-5310635/a-nebraska-barbershop-is-trying-to-accommodate-customers-with-sensory-sensitivities>
- beaming-sensory: <https://beaminghealth.com/article/sensory-friendly-haircuts-the-complete-checklist>
- hji-autism: <https://www.hji.co.uk/business/eight-ways-to-make-your-salon-autism-friendly/>
- delco-sensory: <https://delco.today/2026/06/sensory-friendly-haircuts-wild-horse-salon/>
- americansalon-neuro: <https://www.americansalon.com/ask-experts/7-dos-3-big-donts-serving-neurodivergent-salon-clients>
- datausa-east: <https://datausa.io/profile/geo/79500US3903408>
- datausa-north: <https://datausa.io/profile/geo/79500US3903402>
- franklin-languages: <https://d101vc9winf8ln.cloudfront.net/documents/48365/original/Spreadsheet_Franklin_County_OH_2016_to_2020_Final.pdf?1702662926>
- stacker-languages: <https://stacker.com/ohio/columbus/most-common-languages-spoken-home-greater-columbus-and-surrounding-regions>
- firecrawl-fresha: <https://www.firecrawl.dev/alexandria/agents/providers/fresha-com>
- styleseat-ranking: <https://www.styleseat.com/blog/styleseat-search-ranking-factors/>
- styleseat-top-pro: <https://help.styleseat.com/articles/13612495-how-to-become-a-top-pro>
- booksy-inclusivity: <https://biz.booksy.com/blog/5-tips-on-how-to-promote-inclusivity-inside-the-shop>
- mcconnell: <https://web.stanford.edu/~neilm/Economic_Consequences_Final_Identified.pdf>
- gallup-stance: <https://news.gallup.com/poll/714836/support-businesses-taking-public-stance-cools.aspx>
- ipsos-neutral: <https://www.ipsos.com/en-us/more-people-want-brands-stay-out-political-and-social-issues>
- sprout-activism: <https://sproutsocial.com/insights/brand-activism-accountability/>
- ahp-politics: <https://www.associatedhairprofessionals.com/node/1017>
- btc-politics: <https://behindthechair.com/articles/what-would-you-do-politics-in-the-salon>
- yahoo-hairdressers: <https://sports.yahoo.com/hairdressers-revealing-helpful-unhelpful-things-034602466.html>
- fox5-politics: <https://www.fox5dc.com/news/talking-politics-at-the-hair-salon>
- orc-4112-01: <https://codes.ohio.gov/ohio-revised-code/section-4112.01>
- orc-4112-02: <https://codes.ohio.gov/ohio-revised-code/section-4112.02>
- ohio-ag-2023: <https://ohioattorneygeneral.gov/getattachment/f10767a2-2cf3-4844-b943-90a503dc4c52/2023-006.aspx>
- uc-fairness-act: <https://uclawreview.org/2022/09/20/the-ohio-fairness-act/amp/>
- columbus-crc: <https://www.columbus.gov/neighborhoods/community-relations-commission/Discrimination-and-Protected-Classes-in-Columbus>
- columbus-2331: <https://new.columbus.gov/files/sharedassets/city/v/1/public-health/columbus_codeofordinances_2331-2.pdf>
- aclu-facebook: <https://www.aclu.org/press-releases/facebook-agrees-sweeping-reforms-curb-discriminatory-ad-targeting-practices>
- cnbc-facebook: <https://www.cnbc.com/2019/03/19/facebook-overhauls-ad-targeting-to-prevent-discrimination.html>

[square-loyalty]: https://squareup.com/us/en/press/client-loyalty
[modern-hairstylist]: https://www.buzzsprout.com/1896666/episodes/18993165-the-2026-salon-consumer-behavior-survey-results-are-in
[hji-silent]: https://hji.co.uk/56-of-hairstylists-in-favour-of-silent-services-according-to-research
[northeastern-silent]: https://news.northeastern.edu/2024/05/23/silent-appointments-salon-trend
[fox-silent]: https://fox9.com/news/silent-salons-quiet-hair-appointments-popular
[ctv-silent]: https://edmonton.ctvnews.ca/cut-the-chat-the-rise-in-popularity-of-a-silent-service-at-the-salon-1.5831579
[cbs-quiet-chair]: https://www.cbsnews.com/amp/minnesota/news/quiet-chair-salon
[newbeauty-silent]: https://www.newbeauty.com/silent-hair-salons
[fresha-silent]: https://www.fresha.com/blog/give-your-clients-the-silent-treatment-with-fresha-forms
[setmore-silent]: https://setmore.com/blog/silent-services
[ripe-etiquette]: https://www.ripeinsurance.co.uk/small-business/hair-and-beauty-insurance/blog/salon-etiquette-the-uks-awkward-hair-salon-misunderstandings
[fashion-journal-pricing]: https://fashionjournal.com.au/beauty/hidden-hair-pricing/
[tresemme-bias]: https://www.tresemme.com/us/en/about-us/future-stylists-fund/about-future-stylists-fund/hair-bias.html
[glossy-textured]: https://www.glossy.co/beauty/the-next-frontier-of-inclusive-beauty-is-textured-hair-styling/
[vagaro-lgbtq]: https://www.vagaro.com/news/press-release/lgbtqia-representation-hair-and-beauty-industry
[amspa-vagaro]: https://americanmedspa.org/news/vagaro-research-highlights-lgbtqia-hair-beauty-consumer-perspectives
[gci-vagaro]: https://www.gcimagazine.com/home/news/22914552/vagaro-do-lgbtqia-consumers-feel-represented-in-the-hair-and-beauty-industry
[google-lgbtq-attrs]: https://blog.google/company-news/outreach-and-initiatives/small-business/adding-lgbtq-friendly-and-transgender-safe-space-attributes-google-my-business/
[google-lgbtq-owned]: https://blog.google/company-news/outreach-and-initiatives/diversity/lgbtq-owned-businesses/
[blade-open-to-all]: https://www.washingtonblade.com/2018/08/03/yelp-releases-open-to-all-feature-to-battle-discrimination/
[bi-yelp]: https://businessinsider.nl/how-yelp-is-innovating-its-product-and-using-its-voice-to-drive-social-change-2/
[restaurantdive-yelp]: https://www.restaurantdive.com/news/yelp-adds-lgbtq-owned-business-search-filter/600649
[nhbf-pricing]: https://nhbf.co.uk/news-and-blogs/blog/major-shift-towards-gender-neutral-pricing
[square-gender-neutral]: https://squareup.com/us/en/townsquare/gender-neutral-pricing-beauty-business
[krdo-pricing]: https://krdo.com/news/2024/07/27/why-do-womens-haircuts-cost-more-than-mens/
[npr-sensory]: https://www.npr.org/2025/03/05/nx-s1-5310635/a-nebraska-barbershop-is-trying-to-accommodate-customers-with-sensory-sensitivities
[beaming-sensory]: https://beaminghealth.com/article/sensory-friendly-haircuts-the-complete-checklist
[hji-autism]: https://www.hji.co.uk/business/eight-ways-to-make-your-salon-autism-friendly/
[delco-sensory]: https://delco.today/2026/06/sensory-friendly-haircuts-wild-horse-salon/
[americansalon-neuro]: https://www.americansalon.com/ask-experts/7-dos-3-big-donts-serving-neurodivergent-salon-clients
[datausa-east]: https://datausa.io/profile/geo/79500US3903408
[datausa-north]: https://datausa.io/profile/geo/79500US3903402
[franklin-languages]: https://d101vc9winf8ln.cloudfront.net/documents/48365/original/Spreadsheet_Franklin_County_OH_2016_to_2020_Final.pdf?1702662926
[stacker-languages]: https://stacker.com/ohio/columbus/most-common-languages-spoken-home-greater-columbus-and-surrounding-regions
[firecrawl-fresha]: https://www.firecrawl.dev/alexandria/agents/providers/fresha-com
[styleseat-ranking]: https://www.styleseat.com/blog/styleseat-search-ranking-factors/
[styleseat-top-pro]: https://help.styleseat.com/articles/13612495-how-to-become-a-top-pro
[booksy-inclusivity]: https://biz.booksy.com/blog/5-tips-on-how-to-promote-inclusivity-inside-the-shop
[mcconnell]: https://web.stanford.edu/~neilm/Economic_Consequences_Final_Identified.pdf
[gallup-stance]: https://news.gallup.com/poll/714836/support-businesses-taking-public-stance-cools.aspx
[ipsos-neutral]: https://www.ipsos.com/en-us/more-people-want-brands-stay-out-political-and-social-issues
[sprout-activism]: https://sproutsocial.com/insights/brand-activism-accountability/
[ahp-politics]: https://www.associatedhairprofessionals.com/node/1017
[btc-politics]: https://behindthechair.com/articles/what-would-you-do-politics-in-the-salon
[yahoo-hairdressers]: https://sports.yahoo.com/hairdressers-revealing-helpful-unhelpful-things-034602466.html
[fox5-politics]: https://www.fox5dc.com/news/talking-politics-at-the-hair-salon
[orc-4112-01]: https://codes.ohio.gov/ohio-revised-code/section-4112.01
[orc-4112-02]: https://codes.ohio.gov/ohio-revised-code/section-4112.02
[ohio-ag-2023]: https://ohioattorneygeneral.gov/getattachment/f10767a2-2cf3-4844-b943-90a503dc4c52/2023-006.aspx
[uc-fairness-act]: https://uclawreview.org/2022/09/20/the-ohio-fairness-act/amp/
[columbus-crc]: https://www.columbus.gov/neighborhoods/community-relations-commission/Discrimination-and-Protected-Classes-in-Columbus
[columbus-2331]: https://new.columbus.gov/files/sharedassets/city/v/1/public-health/columbus_codeofordinances_2331-2.pdf
[aclu-facebook]: https://www.aclu.org/press-releases/facebook-agrees-sweeping-reforms-curb-discriminatory-ad-targeting-practices
[cnbc-facebook]: https://www.cnbc.com/2019/03/19/facebook-overhauls-ad-targeting-to-prevent-discrimination.html

Every link above is cited in the text. Caveats: several surveys are commissioned by companies with an interest
(Ripe, TRESemmé, Vagaro, Square); the UK figures may not carry over to Columbus; platform filter menus weren't
checked inside the apps.
