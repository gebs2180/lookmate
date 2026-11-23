# Service finder: telling clients what to book

Research and a proposal, 2026-11-22. Nothing here is built yet. Zach's decisions are at the end.

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
   saves the stylist the five-minute intake call. Decided: only when the client ticks a box on the Book screen, because it sends client data to a
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

## Later: photos fill in answers

Proposed 2026-11-22 (Zach asked; not decided). The client uploads "my hair now" and "what I want", and a vision model
**fills in quiz answers**. It never names the service: the stylist's rules still decide, so the result stays
something the stylist can test and stand behind.

1. The model reads only what a photo shows: how light the hair is now and in the goal photo (the template's
   `now_level` and `want_level`), colour all over or in pieces, root shadow or bright from the root, warm or cool,
   length.
2. The client confirms in plain words: "Looks like you want to go about 3 shades lighter, with soft painted
   brightness. Right?" They can change any answer.
3. The rules run as usual. The level-gap rules ("very dark to blonde → consultation") are where photos help most:
   clients rarely know the gap, and it's the classic mismatch
   ([Booksy](https://biz.booksy.com/blog/hairdressing-consultation-questions)).

**What photos can't do:**
- **Colour history is invisible.** Box dye, henna, relaxer and lightener drive the safety rules, so those questions
  stay whatever the photos show.
- **Inspiration photos lie.** Filters, ring lights and wigs make a level 7 look like a 9.
- **Fairness.** Vision models read dark and coily hair less well, especially in poor light. Accuracy is measured per
  hair type before the feature fills in anyone's answers.
- **Privacy.** A current-hair photo usually shows a face. Same private bucket, presigned links and 30-day retention
  as inspiration photos; the model runs on Bedrock in our own AWS account, nothing is used for training, and the
  upload says so in one line the client accepts.

**When:** not in step 1. In step 2, both uploads are optional. Photos reach the stylist only with the client's tick
(decision 4), and are kept with the stylist's "right service" mark. That's the labelled set to test a model
offline: do its levels agree with the stylist's, on every hair type? Only then does it fill in answers. Even if it
never ships, stylists get the photos they'd otherwise ask for by text.

## Pilot plan

| Step | What | Who | Size |
|---|---|---|---|
| 0 | Spreadsheet template ([service-finder-template.xlsx](service-finder-template.xlsx): questions, outcomes, rules, tests). They fill it in for their own menu | Us → partner | A day |
| 1 | `consult` module, the partner's ruleset loaded, the client flow at `/c/<their-slug>`, the landing-page "Not sure what to book?" button running the same ruleset as a general version, tracked hand-off | Us | About a week across two sessions (backend, then web) |
| 2 | Four weeks live on their link. They mark each finder client's booking as "right service / wrong service / needed a consult". Optional "my hair now" and "what I want" photos are collected for the photo test (see above) | Partner | 4 weeks |
| 3 | If the right-service rate is high, build the self-serve editor and invite 5 to 10 independent Salon Lofts stylists free | Us | About a week |
| 4 | Charge for it only once about 10 stylists use it every week. A Stripe payment link off-app at first, no billing code | Zach | — |

**What we measure:**
- the finder completion rate;
- the share of clients who press Book;
- the share sent to a consultation;
- **right service**, as the stylist marks it after the appointment;
- the stylist's time saved, by their own estimate;
- how many finder clients go on to use find-my-stylist.

## Decisions (Zach, 2026-11-22)

1. **Button text:** "Not sure what to book?" "Book a consultation" stays a possible result; nothing promises a person.
2. **Ownership:** the partner stylist owns their questions and rules. Combpatible gets a licence to use them as the
   default set, with credit in the tool. It goes in a one-page agreement before step 1 ships, whatever the fee.
3. **Both at once:** step 1 ships the partner's own link **and** the general version behind the Combpatible button,
   both running the partner's ruleset. The general version's results drop the partner's prices and booking links and
   hand off to find-my-stylist instead.
4. **Answers to the stylist: yes, only with the client's OK.** A tick box on the Book screen, unticked by default.
   Without it, nothing is sent.
5. **Charging stylists: yes, later.** It's recorded as a decision now. Build it only once about 10 stylists use the
   finder every week, and start with a Stripe payment link off the app. The "no payments" non-negotiable still covers
   clients.
6. **Salon Lofts only at first,** the same as matching.

Still open: a lawyer's look at the partner agreement, alongside the stylist terms.
