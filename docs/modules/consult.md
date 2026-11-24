# Module: `consult`

The service finder, "Not sure what to book?" A short quiz that ends in the service to book, or in "book a
consultation first" with the reason. It runs on rules a partner stylist writes and tests in a spreadsheet, never on
AI. Research and decisions: [research/service-finder.md](../research/service-finder.md).

`consult` depends only on `api` (errors) and `identity` (the admin's id). No other module depends on it, so it can
break out later. `ModularityTest` checks both. It publishes no events. A `ConsultFinished` event was left out because
nothing would listen to it yet.

## How it works

1. **The sheet.** The partner fills in the yellow columns of
   [service-finder-template.xlsx](../research/service-finder-template.xlsx): Questions, Outcomes, Rules and Tests.
   The sheet's own grey columns show whether each test passes in Excel.
2. **The converter** turns the sheet into the ruleset JSON (below). It reads only the yellow input cells.
3. **The engine** (`Ruleset.evaluate`) goes through the rules in order, and the first rule whose conditions all hold
   wins. A rule with no conditions is a catch-all and belongs last. A skipped question matches no condition.
4. **Checks on load and on upload** (`RulesetValidator`). A ruleset is refused, with every problem listed, when:
   - a rule names an unknown question, answer or outcome;
   - an outcome has an unknown `serviceType` or a booking link that isn't https;
   - a rule on a question that isn't stored (`reaction`) leads to a booking instead of a consultation;
   - some answers reach no rule (a catch-all settles this);
   - a hair-type answer has no test;
   - **any test case fails.**

   The sheet's tests pass in Excel. If one fails here, find out why (a typo in an answer, a renamed outcome) rather
   than editing the data.
5. **Storage.** `consult_ruleset` keeps every published version, and a database trigger refuses any change to one.
   The newest version of the `default` key is live for both links. `consult_session` keeps one row per finished
   quiz:
   - the ruleset version, `market`, channel, answers, the outcome and the winning rule;
   - Book clicks;
   - the "send my answers to the stylist" tick (unticked by default, and once ticked it stays ticked);
   - whether the client went on to find a stylist.

   The `reaction` answer is never stored, only used to route. Sessions are deleted after
   `lookmate.retention.client-contact-days` (365), daily with the other retention jobs.
6. **Channels.**
   - **Partner** (`/c/<slug>`): full outcomes, with the partner's from-price and booking link, tagged
     `utm_source=combpatible&utm_medium=referral&utm_campaign=<market>&utm_content=consult`.
   - **General** (the Combpatible button): no price and no link, because the prices are one stylist's. It carries the
     `serviceType`, so the web can hand off to find-my-stylist.

   Both use the partner's ruleset. The slug is `lookmate.consult.partner-slug` (`CONSULT_PARTNER_SLUG`, default
   `partner`).

**Not built yet:** emailing the stylist the answers when the client ticks the box. The flag is stored, and there's a
TODO in `ConsultService.book`. There's no web UI yet either.

## The converter

```
python tools/consult/sheet_to_ruleset.py docs/research/service-finder-template.xlsx backend/app/src/main/resources/consult/ruleset-seed.json
```

It needs `openpyxl` (`pip install openpyxl`), or you can run it with `uv run`. Options:

- `--credit "Name, Salon"`: who wrote the rules, shown to clients. Leave it out for our example answers.
- `--name "..."`: the finder's title.

The checked-in seed has no credit. To check a converted file before loading it, run
`./gradlew :app:test --tests '*SeedRulesetTest'` against the seed, or upload it: the upload is refused, with the
problems listed, if anything fails.

The question id `reaction` is hard-coded as not stored (`NOT_STORED` in the script). If the partner renames that
question, update the script.

## The seed

`backend/app/src/main/resources/consult/ruleset-seed.json` is our own example ruleset, converted from the template.
It has 9 questions, 11 outcomes, 26 rules and 28 tests. `ConsultSeed` publishes it as version 1 at startup when no
ruleset exists, and never again after that.

The example data has gaps, left as they are:

- No booking links, so Book is never offered.
- Consultation prices are 0.
- Rule 4 ("Fix a colour I don't like") has no `why`.
- The help texts are notes to us (photo ideas), not client copy.

## Loading Jason's version

1. Convert his filled-in sheet, with his credit:

   ```
   python tools/consult/sheet_to_ruleset.py jason.xlsx jason.json --credit "Jason ..., Salon Lofts"
   ```

2. As admin, `POST /api/admin/consult/rulesets` with the JSON as the body. A 201 means it's live at once for both
   links. A 422 `RULESET_INVALID` lists the problems in `problems`. Fix them in his sheet, with him, not in the
   JSON.
3. Check `GET /api/admin/consult/rulesets`: his version is `current: true` and shows his credit.

Don't replace `ruleset-seed.json` with his file: the seed is our example, and his rules are his (the licence
agreement is Zach's). Earlier versions stay in the table. To roll back, upload the older document again
(`GET /api/admin/consult/rulesets/{version}` returns it), which makes it a new version.

## API

Public endpoints need no sign-in. They are rate-limited per IP:
`lookmate.consult.rate-limit.submits-per-hour` (30) for POSTs to `/sessions`, and `reads-per-minute` (60) for
everything else. Over the limit they answer 429 `RATE_LIMITED` with `Retry-After`.

| Method and path | Body | Response |
|---|---|---|
| `GET /api/public/consult/finder`, `GET /api/public/consult/c/{slug}/finder` | none | `Finder` (an unknown slug gives 404) |
| `POST /api/public/consult/sessions`, `POST /api/public/consult/c/{slug}/sessions` | `{"answers": {"<questionId>": ["<answer>", ...]}}` | 201 `Result`, or 400 `INVALID_ANSWERS` |
| `POST /api/public/consult/sessions/{id}/book` | optional `{"shareAnswers": true}` | 204, or 409 `NOT_BOOKABLE` (general channel, or the outcome has no link; a consultation with a link is bookable) |
| `POST /api/public/consult/sessions/{id}/find-stylist` | none | 204 |
| `GET /api/admin/consult/rulesets` | none | `RulesetVersion[]`, newest first |
| `GET /api/admin/consult/rulesets/{version}` | none | the ruleset document |
| `POST /api/admin/consult/rulesets` | the ruleset document | 201 `RulesetVersion`, or 422 `RULESET_INVALID` with `problems` |

The shapes are in `web/openapi.json`. The OpenAPI spec doesn't record which fields can be null:

- In a `Finder` question, `help` can be null.
- In an outcome, `hours`, `whatToExpect` and `why` can be null.
- `fromPrice` and `bookingUrl` are null on the general channel, and `bookingUrl` is also null when the stylist gave
  no link.
- `credit` is null until a partner's version is loaded.

Answers are the answer texts exactly as `Finder` lists them. Skipped questions are left out, and only `multiple`
questions take more than one answer.
