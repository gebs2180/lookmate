# Service finder: telling clients what to book

Research and a proposal, 2026-11-22. Nothing here is built yet: Zach decides first (see the open questions at the end).

**Where the idea came from.** A working stylist we know through Salon Lofts asked for it, for their own salon:

> Clients are expected to know what services to book, but most don't know the difference between balayage,
> highlights, all-over color, etc.

They want a short quiz about the client's hair, goals and colour history. It ends in the exact service to book, or
"book a consultation first". They'd write the hair logic themselves, and also see it as a cheap monthly membership for
independent stylists who work alone, because wrong bookings and unpaid consultations put them off taking new clients.

Read this with [brief.md](../brief.md), [architecture.md](../architecture.md),
[modules/intake.md](../modules/intake.md) and [modules/stylists.md](../modules/stylists.md).

## The short answer

- **Build it inside Combpatible as its own module, `consult`.** Draw its edges now so it can later run as its own
  product.
- **Keep the hair logic as data a stylist writes and tests, not as code.** That's what lets the stylist own the logic, and what
  lets the tool become a product other stylists can use.
- **Pilot it on their salon first.** They're the design partner: their questions, service menu and booking links. Only
  after that, open it to other independent stylists.
- **The pro membership is a business decision for later.** Don't build any billing until about 10 stylists use it every
  week.

The client tool and the matching app feed each other. The finder answers "what do I book?" and matching answers "who
should do it?". A finder result can lead straight into find-my-stylist with the service already chosen.

## What already exists

Salons already route clients with quizzes. Nobody sells a ready-made, editable finder to the solo stylist.

| What | Examples | Why it doesn't cover this case |
|---|---|---|
| Custom quizzes on salon websites | The Mill's [service finder](https://www.themillhair.com/new-guests), Reverie's [colour quiz](https://www.reveriesalon.com/pages/color-matcher), Glam Color Bar's [new client quiz](https://www.glamcolorbar.com/newclientquiz) (required before any colour), [1630 Hair's booking guide](https://1630hair.com/booking-guide), [Trios](https://www.triossalon.com/find-your-stylist/) and [Harlow](https://harlowaustin.com/stylist-matching-quiz/) stylist-matching quizzes | Each one is built by hand for one larger salon, often on a web agency's budget |
| Booking apps' intake forms | [StyleSeat Forms](https://www.styleseat.com/join/forms) (part of its $35/month Premium plan), GlossGenius and Koalendar [intake forms](https://koalendar.com/scheduling-software-for/hair-salon) | They collect answers **after** the client has booked. They don't decide what the client should book |
| Form builders and templates | [Jotform](https://www.jotform.com/241775275254159), [fomr.io](https://fomr.io/templates/hair-coloring-consultation-form-ombr-sombr), [QuestionPunk](https://www.questionpunk.com/template/hair-consultation-intake-and-goals-survey), Etsy packs | No logic tied to a service menu, so the stylist still has to read every answer |

The trade press confirms the problem:

- Clients mix up balayage and highlights in both directions
  ([Behind the Chair](https://behindthechair.com/articles/hand-this-to-the-next-client-that-asks-for-balayage)).
- A wrong booking costs either time the stylist needed or time they blocked for nothing
  ([1630 Hair](https://1630hair.com/booking-guide)).
- Many salons make a consultation compulsory for every new colour client
  ([Salonkee](https://salonkee.lu/search/friseure/olm/haarberatung)).
- [Salon Today](https://www.salontoday.com/articles/do-you-want-fries-with-that) suggests asking about dramatic
  changes at booking time to steer clients to a consultation.
- [Booksy's consultation guide](https://biz.booksy.com/blog/hairdressing-consultation-questions) gives the classic
  mismatch: a client with dark brown hair brings a dark blonde reference photo.

Pricing: the booking apps charge about $30 to $35 a month
([StyleSeat's comparison](https://www.styleseat.com/join/booksy-vs-styleseat); vendor figures, so check them). A
finder add-on has to sit well below that, at roughly $10 to $15 a month.

Salon Lofts already runs partner programmes for its loft owners
([EndureLash](https://www.salontoday.com/news/endurelash-and-salon-lofts-partner-to-empower-over-300-locations-nationwide)).
That's the obvious channel once the pilot works.

## How it works for a client

1. A button on the landing page, next to "Find my stylist": **"Not sure what to book?"**
   - "Free consultation" promises a person, so save that wording for when the result really is "book a consultation".
   - On a stylist's own link, the same flow carries their name and their menu.
2. Six to ten taps, one question per screen, like the existing quiz:
   - the hair today: type, length, density;
   - the goal: lighter, darker, brighter, covering grey, a fix, a refresh;
   - the colour history: box dye, lightener, henna, the last colour service;
   - how much upkeep they want;
   - an optional inspiration photo, reusing the existing upload and its privacy rules.
3. One result, in plain words. For example: "Book **Partial balayage + toner**, about 3 hours, from $180." Below it:
   - a short "why" in the stylist's words;
   - what to expect;
   - "Your stylist confirms at the appointment".
4. Or: **"Book a consultation first"**, with the reason ("box dye in the last year can react with lightener").
5. Next steps:
   - **On a stylist's link:** "Book this" opens that service in their own booking app, tracked with
     `utm_source=combpatible` like today.
   - **On Combpatible:** "Find a stylist for this" opens find-my-stylist with the service filled in.

No calendar, no payments, no messaging: the finder hands off to the stylist's own booking link, as matching already
does.

## How it works for a stylist

1. **Pilot (us):** our partner stylist writes their questions and rules in a spreadsheet template we provide, and we load it.
2. **Later (self-serve):** the stylist signs in (Auth0, as today) and edits:
   - **Questions:** single or multiple choice, with optional help text and photos (for example, what "dimension"
     looks like).
   - **Outcomes:** their own service names, with duration, a from-price, a booking deep link, and a "why" in their words.
     The consultation outcome carries the reason.
   - **Rules:** an ordered list of "when these answers → this outcome". The first match wins. A safety rule ("any
     sign of a colour correction → consultation") sits at the top.
   - **Test cases:** "these answers should give this outcome". The editor refuses to publish if a test fails or some
     combination of answers reaches no outcome. That's how a stylist changes their logic without breaking it.
3. The stylist gets **one link** for their Instagram bio and their booking page's "new clients" note.
4. **Optional:** a short summary of the client's answers emailed to the stylist when the client presses Book. This
   saves the stylist the five-minute intake call. Open question 4 asks whether to build it, because it sends client data to a
   third party.

**Why rules and not AI.** A stylist can read rules, test them, and stand behind them in the chair. An LLM's "you should
book balayage" can't be checked and would be wrong in ways nobody notices. Ask an LLM to help them **write** the
rules, if anything; never to decide.

## Design: built in, ready to break out

A new Spring Modulith module, **`consult`**, alongside `intake` and `matching`.

| Concern | Choice | Why it breaks out cleanly |
|---|---|---|
| Data | Its own tables, prefixed `consult_`, added in one new Flyway migration: `ruleset` (owner org, version, published), `question`, `outcome`, `rule`, `test_case`, and `consult_session` (answers, outcome, ruleset version, market) | Joins no other module's tables. A future split is a `pg_dump -t 'consult_*'` |
| Rules format | One JSON document per ruleset version, validated on save. Published versions are never changed, and every session records the version it used | The format is the product. It stands on its own and has its own tests |
| API | `/api/public/consult/**` (no sign-in, rate-limited per IP like intake) and `/api/orgs/{orgId}/consult/**` (the stylist's editor) | One path prefix to point at another server later |
| Dependencies | `identity` (who owns a ruleset) and `storage` (optional photo). It publishes a `ConsultFinished` event; `intake` may read it to prefill find-my-stylist. **No module depends on `consult`** | Removing or splitting it breaks nothing else (`ModularityTest`) |
| Web | Routes `/consult` and `/c/:slug` (a stylist's link), in their own folder `web/src/consult/`, sharing only the design tokens and the `quiz.ts` patterns | It can be built as its own Vite entry later if it moves to its own domain |
| Service codes | Its outcomes are free text (the stylist's menu). Each can map to one of the app's `ServiceType` codes (`BALAYAGE`, `HIGHLIGHTS`…) so the hand-off to matching works | No change to `V2`'s service list |
| Hosting | The same server, the same Caddy. A split later is a new site block and a new database | Nothing new to run now |

**Privacy follows the existing rules:**
- Answers are anonymous unless the client chooses to email the result to themselves, the way "Email me this list"
  works.
- Inspiration photos use the same private bucket, presigned links and retention.
- Store no health details: "allergy or sensitivity to colour?" only routes to a consultation, and the answer itself is
  not kept.
- Sessions are deleted after the client-contact retention period.

**Fairness:** the partner's rules should say what happens on curly and coily hair, not just straight hair. For example, a
lightening goal on coily hair with a recent relaxer must go to a consultation. The test cases cover each hair type,
the same commitment matching makes.

## Pilot plan

| Step | What | Who | Size |
|---|---|---|---|
| 0 | Spreadsheet template (questions, outcomes, rules, tests). They fill it in for their own menu | Us → partner | A day |
| 1 | `consult` module, the partner's ruleset loaded, the client flow at `/c/<their-slug>`, the landing-page button, tracked hand-off | Us | About a week across two sessions (backend, then web) |
| 2 | Four weeks live on their link. They mark each finder client's booking as "right service / wrong service / needed a consult" | Partner | 4 weeks |
| 3 | If the right-service rate is high, build the self-serve editor and invite 5 to 10 independent Salon Lofts stylists free | Us | About a week |
| 4 | Charge for it only once about 10 stylists use it every week. A Stripe payment link off-app at first, no billing code | Zach | — |

**What we measure:**
- the finder completion rate;
- the share of clients who press Book;
- the share sent to a consultation;
- **right service**, as the stylist marks it after the appointment;
- the stylist's time saved, by their own estimate;
- how many finder clients go on to use find-my-stylist.

## Open questions for Zach

1. **Name and button text:** "Not sure what to book?" (proposed), "Find my service", or "Free consultation"?
2. **Partner terms:** a one-page agreement covering who owns their questions and rules (the stylist, with a licence for Combpatible
   to use the rules as the default set), and credit in the tool. "Free haircuts for life" is a fine fee; write the
   ownership down anyway.
3. **Generic or per stylist first?** This proposal runs their ruleset on their link, plus a generic version on Combpatible
   based on theirs. Should the generic version wait until they're happy with theirs?
4. **Answers emailed to the stylist:** that's client data going to a third party. It needs the client's explicit OK on
   the Book screen. Do it in the pilot or not?
5. **Charging stylists:** CLAUDE.md's "no payments" non-negotiable is about clients. Charging pros a membership is a
   new decision for the CLAUDE.md Decisions list, and not needed until step 4.
6. **Beyond Salon Lofts:** a finder for any independent stylist reaches past the Columbus Salon Lofts pilot. Keep it
   Salon Lofts-only at first, as matching is?
