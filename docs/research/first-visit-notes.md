# First-visit notes: our draft for Jason

**Status: first draft by us, 2026-11-25.** Jason (Salon Lofts) writes the real version (Zach, 2026-11-25). Like the
service finder, the app ships this draft until his arrives, and doesn't credit him until it's his text. Wrong advice is
worse than none, so for each note Jason can **keep**, **change** or **cut** it, and add his own.

This is idea 1 of [feature-ideas.md](feature-ideas.md). The notes appear under "Before your first visit" on the match
page, the portfolio dialog and the service finder's result.

## Where the notes live, and putting Jason's version in

The app reads `backend/app/src/main/resources/stylists/first-visit-notes.json`: one `"KEY": "note"` per line, keys
as in the tables below. To ship Jason's version:

1. Edit the JSON: change or cut a note, or add one with a new key (`any.<name>` for any visit). Leave a key out for no
   note. Keep this page's tables in step, with his answer in the Jason column.
2. Update the `_about` line to say it's his version and the date, and credit him in this page's status line once it's
   his text.
3. Run `./gradlew test --tests '*FirstVisitNotes*'` in `backend/`: a misspelt key fails it (and the app at start-up).

No migration or web change is needed. How the module uses the notes: [stylists.md](../modules/stylists.md).

## How a client's notes are picked

- The **stylist's own note** and their two facts come first, and win where they disagree with a generic note:
  - "New colour clients need a consultation first" (yes/no);
  - "Deposit for new clients" (yes/no; shown as a fact, never collected by us);
  - a short free-text note ("Park in the back lot; I'm in loft 12").
- Then the generic notes, most specific first: the **service and hair type together**, then the **service**, then the
  **hair type**, then **any visit**.
- At most four generic notes, so the list stays short. The key column is the ID in the content file.
- `ServiceType`: CUT, COLOR, BALAYAGE, HIGHLIGHTS, EXTENSIONS, BRAIDS, LOCS, SILK_PRESS, PERM, TREATMENT, STYLING,
  OTHER. `HairType`: STRAIGHT, WAVY, CURLY, COILY. A blank cell means no note.

Tone: second person, plain words, no product names, no promises about results. "May" and "ask" rather than "will".

## Any visit

| Key | Note | Jason |
|---|---|---|
| `any.photos` | Bring 2–3 photos of what you want, and one of what you don't. | |
| `any.routine` | Tell your stylist what you use at home and how long you spend on your hair most days. | |

## By service

| Key | Note | Jason |
|---|---|---|
| `CUT` | Come with your hair the way you usually wear it, so your stylist can see how it sits. | |
| `COLOR` | Have your colour history ready for the last two years: box dye, henna, salon colour, lightener. Your stylist may ask for a strand or skin test first, which can mean a short visit before the main one. | |
| `BALAYAGE` | Plan for a long appointment, often three hours or more. Bring your colour history; a big change can take more than one visit. | |
| `HIGHLIGHTS` | Bring your colour history. Going much lighter can take more than one visit. | |
| `EXTENSIONS` | Most stylists start with a consultation to match colour and choose a method. Ask whether the hair is included in the price. | |
| `BRAIDS` | Ask whether braiding hair is included or which kind to bring, and whether to arrive washed and detangled. Plan for a long appointment. | |
| `LOCS` | Say whether you're starting locs or maintaining them, and when they were last retwisted. | |
| `SILK_PRESS` | Tell your stylist about any colour, relaxer or heat damage in the last year. Bring a scarf or bonnet for the way home. | |
| `PERM` | Tell your stylist about any colour, lightener or relaxer in the last year: some chemical services can't safely go on top of others. Expect a strand test. | |
| `TREATMENT` | Say what you'd like it to help with (dryness, breakage, frizz) and what you use at home. | |
| `STYLING` | Bring a photo of the style and say when the event is, so your stylist can tell you how long it'll hold. | |
| `OTHER` | | |

## By hair type

| Key | Note | Jason |
|---|---|---|
| `STRAIGHT` | | |
| `WAVY` | Wear your hair as you usually style it, so your stylist can see how your waves behave. | |
| `CURLY` | Skip heavy oils and butters on the day, and avoid a tight ponytail or clips that leave kinks. Bring the products you use. | |
| `COILY` | Allow extra time for a first visit. Bring your products, and say how you usually wear your hair: wash-and-go, twist-out, stretched or protective styles. | |

## Service and hair type together

| Key | Note | Jason |
|---|---|---|
| `CUT+CURLY` | Ask whether your stylist cuts curls dry, wet or both, and whether to arrive with your curls washed and styled. | |
| `CUT+COILY` | Ask whether your stylist cuts dry, stretched or wet, and how to arrive: many want your hair detangled or stretched. | |
| `COLOR+CURLY` | Lightening can change how your curls behave. Ask how your stylist protects them. | |
| `COLOR+COILY` | Lightening can change how your coils behave. Ask how your stylist protects them. | |
| `SILK_PRESS+CURLY` | Ask how your stylist protects your curl pattern from heat. | |
| `SILK_PRESS+COILY` | Ask how your stylist protects your curl pattern from heat. | |

## Questions for Jason

1. Is anything here wrong, or something stylists would roll their eyes at?
2. What does a new client most often get wrong before a first visit with you?
3. Should any note depend on the stylist rather than be generic (dry cutting, washing in the appointment)?
4. Is four notes the right limit?
