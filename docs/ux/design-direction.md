# Design direction: "Chair-side"

Proposed 2026-11-20. The look and voice for Combpatible (named 2026-11-20; working name LookMate until then) across client, stylist and admin screens.
Prototype: <https://claude.ai/artifact/74ApqgXdGU52ULhyr6Cvfm> (source: [prototype.html](prototype.html)). Tokens:
[tokens.css](tokens.css). Flows: [flows.md](flows.md). How to get there: [implementation-plan.md](implementation-plan.md).

> **Redesign under review (2026-11-20):** four fuller directions with clickable prototypes, and a recommendation, are in
> [redesign-options.md](redesign-options.md). If one is approved, it replaces the type, colour and motion sections
> below; the principles, tone, accessibility and inclusivity sections still stand.

The name comes from the consultation: the minute in the chair before anything is cut, when a good stylist looks at your
photo, asks about your hair and repeats back what you want. The whole product is that minute, done before you book.

## Principles

1. **Show the work.** Photos of finished hair carry every important screen. Words explain the photos and never stand in
   for them. A match without evidence photos is not shown.
2. **Say it back.** Whatever the client tells us comes back to them in their own words (the *echo*): after sending, on
   the match page, and in the admin's match log. This answers the brief's core finding that clients leave stylists who "didn't
   listen".
3. **Every hair type, equally.** Straight, wavy, curly and coily get the same size, the same drawing language, the same
   number of examples and the same quality of copy. Equality is checked per screen, not assumed (see Inclusivity).
4. **Private by default, and say so early.** The privacy promise appears where the worry starts (the landing page and the
   photo picker), in a calm tone, with facts that are true in the code.
5. **Clients choose; we recommend.** Matches publish automatically in seconds, recommendations first with their reasons
   and evidence, and "See all stylists" is a tab beside them, filterable by service and hair type. "Book" names the site
   it leaves for. Nobody approves a list before the client sees it (unless the admin turns review on; see flows.md).
6. **Thumb-first for stylists.** One-handed, between clients, often with wet hands: big targets, few decisions per
   screen, nothing that needs typing to finish tagging.

## Brand feel

Editorial and confident like a good salon's own Instagram; warm like the stylist who remembers your last cut; never
clinical (no medical blues, no hair-loss-clinic diagrams) and never a marketplace (no star ratings, price sorting or
"deals"). Quiet surfaces so the photography supplies the colour.

**Wordmark: Combpatible.** The ring mark (two overlapping rings, one in ink and one in the accent: two people, one
match) followed by the name. Proposed setting:

- **Name:** "Combpatible", sentence case, in Hanken Grotesk 700, tracking +0.01em (tighter than the +0.02em first
  proposed: at 11 letters the word gets long, and looser tracking reads as spaced-out). Ink colour, never the accent.
- **Not Bodoni.** The header wordmark sits at 18–20px, below Bodoni Moda's 24px floor, where its hairlines vanish.
  Bodoni stays for screen titles and the hero; the wordmark is the same face at every size, so it doesn't change
  between header, email and icon.
- **One word, not split.** Don't set "Comb" and "patible" in different weights or colours: split, it reads as a typo
  or a different name. The pun lives in the copy, not the letterforms.
- **Ring mark** at the cap height plus a little (26px beside 18px text), 0.45rem gap. It works alone for the app icon
  and favicon, where the full word is too long.
- One component (`<Wordmark/>`, name from one constant), used by both shells, the emails' header text and the PWA
  manifest (`name` "Combpatible"; `short_name` "Combpatible" fits under a home-screen icon on most phones, test it).
- Check it at 320px wide with the "Private link" chip beside it in the client app bar.

**The pun ("comb" + "compatible").** Use it lightly, at most once per screen and only where the client is relaxed and
choosing: the landing line ("Find your Combpatible stylist" is enough; no "hair-raising", "shear" or "mane" jokes),
a stylist's share card, the results heading. **Never** in errors, privacy copy, the wait, "no close match", consent
or anything about deleting data: a joke there reads as not taking the client's photo or problem seriously. When in
doubt, write the sentence as if the name had no pun.

**Spelling.** People will type "compatible". Say the name out loud in onboarding videos and spell it in emails' footer;
if a domain is bought, consider the misspelling too.

## Visual language

### Type

| Role | Face | Use |
|---|---|---|
| Display | **Bodoni Moda** (variable, optical sizes, OFL) | Screen titles from 30px up, the landing hero, and the echo quote in *italic*. Weight 500, never bold. Never below 24px: its hairlines vanish. |
| Text and UI | **Hanken Grotesk** (variable, OFL) | Everything else: body 16px, labels, buttons (650), numbers (tabular figures). |

Both are on Google Fonts and Fontsource (`@fontsource-variable/bodoni-moda`, `@fontsource-variable/hanken-grotesk`), so
they self-host like Manrope today; no third-party font requests in production. Why this pair: a Didone is the
typography of fashion print, which tells a client from Instagram they are somewhere about looks. Bodoni is used only for
big, few words, while a warm, open grotesk does all the reading and tapping work, so legibility never pays for style.

Scale (1.25 ratio): 12 / 14 / 16 / 18 / 22 / 30 / 40 px. Line height 1.55 for text, 1.1–1.15 for display. Inputs are
always 16px or larger (smaller makes iOS zoom). Headings use `text-wrap: balance`; uppercase eyebrows get 0.08em
tracking.

### Colour

Porcelain and plum ink with one garnet accent. Garnet was chosen over the usual coral or terracotta because it reads as
lipstick and lacquer (beauty) rather than as a home-decor brand, and it holds strong contrast in text. On dark
surfaces it lifts to rose.

| Token | Light | Dark | Use |
|---|---|---|---|
| `--color-bg` | `#fbf8f7` porcelain | `#140b17` | Page |
| `--color-surface` | `#ffffff` | `#1d1221` | Cards, inputs |
| `--color-text` | `#23142a` plum ink | `#f7eef3` | Text |
| `--color-muted` | `#6a5870` | `#bca9bf` | Hints, meta |
| `--color-accent` | `#8c1d47` garnet | `#f28db2` rose | Primary button, selection, progress |
| `--color-accent-soft` | `#f6e3ea` blush | `#3b1a2a` | Selected option fill, the echo on admin |
| `--color-info-soft` / `-text` | `#ece6f3` / `#47306a` | `#2a2140` / `#cdbdf0` | The privacy note (calm lilac, deliberately not a warning colour) |

Success, warning and danger are separate from the accent and only signal state. Full set, with contrast ratios measured
for every text pair, in [tokens.css](tokens.css). The neutrals have a slight rose bias so the page feels warm without
going cream.

### Spacing, radii, elevation

- Spacing on a 4px base (4, 8, 12, 16, 24, 32, 48). Phone side gutter 18px; cards pad 16px.
- Radii: 6px on chips and photo tags, 12px on inputs, buttons and photos, 20px on cards and the bottom sheet's top. The
  primary button and choice chips are pill-shaped; nothing else is.
- Elevation: two shadows, tinted with the ink, not grey. Cards get `--shadow-1`; sheets and the device-level surfaces get
  `--shadow-2`. Borders are for controls (3:1 contrast); dividers use `--color-line`.

### Motion

Short and soft, and only where it explains something:

- Quiz step change: the new question rises 8px and fades in over 220ms.
- Single-choice answers advance on tap after a 260ms pause, so the selection is seen before it moves on.
- The tag sheet slides up over 360ms with a decelerating curve.
- **The instant-results transition** (about 3–4 seconds, while ranking runs): the client's photo with a slow scan line,
  three real steps ticking off ("Reading your photo", "Looking through 286 photos of curly cuts", "Choosing your closest
  stylists"), and shimmer tiles that turn into stylists' photos. Then the same page becomes the results: the cards rise
  in one by one, 120ms apart. The step text is in an `aria-live` region so screen-reader users hear the progress.

Under `prefers-reduced-motion: reduce` all durations drop to zero; the scan, pulse and shimmer stop and the results
appear without the rise. Auto-advance in the quiz stays (it is a convenience, not an animation). The wait is never
lengthened for effect: if ranking finishes in one second, the results show after one second.

### Imagery and photo treatment

- **Portrait 4:5**, the way stylists already shoot for Instagram, with 12px corners. No circular crops of hair work (a
  circle cuts off length, which is the point of many photos).
- **Tags sit on a dark scrim** at the bottom-left of the photo ("After · curly shag"), white 11px bold, so they read on
  any hair colour. The hair-type word is always present on evidence photos.
- **Before/after pairs** show side by side with "Before" and "After" tags, never as a slider (sliders hide one half and
  need a gesture).
- **No filters** of our own over stylists' photos. Colour work must look like what the client will get.
- **The client's inspiration photo** is only shown to the client and the admin, never beside stylists' photos where a
  third party could see it.
- **Placeholders** (prototype, demo stylists, loading): duotones from real hair colours (espresso, copper, honey, plum,
  ash, jet, auburn) with the texture drawn in, so even an empty state represents all four hair types.
- **Marketing and landing photography**: equal representation of straight, wavy, curly and coily hair across a range of
  skin tones, ages and genders, including short and natural styles, locs and braids. Each hero collage shows at least
  three hair types and never leads with straight hair by default.

### Hair-type glyphs

One line drawing per type, the same size and stroke: parallel lines (straight), S-bends (wavy), loops (curly), tight
zig-zags (coily). They appear on the quiz cards, the tag sheet, match reasons and admin evidence, so both sides of the
marketplace see the same picture for the same word.

## Tone of voice

Like a good stylist at the consultation: warm, specific, brief, and never salesy. Second person, plain words, short
sentences. We talk about hair, not "users", "profiles" or "requests" (except where a stylist expects that word).

| Moment | Write | Not |
|---|---|---|
| Landing headline | The stylist who's already done your look. | Revolutionize your salon experience |
| Hair-type question | How does your hair grow naturally? Think wash day, no heat or products. | Select your hair type |
| Past stylists | What should your next stylist get right? | List your complaints |
| Photo prompt | Show us the look you want. The hair matters, not the face. | Upload image |
| Privacy | Only you and the Combpatible team see it. We delete it after 30 days. | Your data is secured with industry-standard encryption |
| Waiting (seconds) | Looking through 286 photos of curly cuts | Our AI is working its magic ✨ |
| Results | Found in 4 seconds. Jordan, these stylists have done your look on curly hair. | We found your perfect match! |
| Next to the picks | See all stylists · Pick anyone. Our recommendations are only a starting point. | Top-rated stylists near you |
| Match reason | 14 similar cuts on curly hair | 94% match |
| Book | Book on Vagaro ↗ | Book now! |
| No close match | We couldn't find stylists with work close to your photo on curly hair yet. Here's everyone who cuts curly hair. | No results |
| Review switched on | A stylist is checking your matches. We'll email you when they're ready, usually within a few hours. | Pending approval |
| Bad photo | We couldn't read that photo. Try a JPEG or PNG up to 10 MB, or a screenshot of it. (The number comes from the server's settings.) | Error 422 |
| Stylist tagging | 6 photos to tag. Clients are matched on tagged photos only. | Incomplete profile |
| Go live, blocked | Tag 1 more photo to go live | Requirements not met |

Rules: no percentages or scores shown to clients (a "94% match" invites distrust and argument); no emoji in product copy;
numbers as digits; dates as "Dec 20"; "Book" always names the platform; the client's own words are quoted exactly, never
paraphrased.

## Accessibility

- **Contrast:** every text pair meets WCAG AA (4.5:1) in both themes, most exceed 6:1; control borders meet 3:1.
  Measured values are in the header of [tokens.css](tokens.css). Text on photos always sits on the scrim (6:1 or better).
- **Tap targets:** 48px minimum (up from 44 in pokemans' tokens); primary buttons 52px; chips 44px with 8px gaps. The tag
  sheet's segmented options are 64px tall.
- **Selection is never colour alone:** selected options get a 1.5px accent border *and* a fill, and use `aria-checked` /
  `aria-pressed`; the quiz's hair-type cards have words and a hint as well as the glyph.
- **Reduced motion:** see Motion. Nothing important is conveyed only by animation.
- **Screen readers:** each quiz screen is a `fieldset` whose `legend` is the question (keep today's structure); progress
  is announced ("Question 3 of 9"); evidence photos get alt text from their tags ("Priya's work: after, curly shag").
  Glyphs are decorative (`aria-hidden`) because the word is always beside them.
- **Focus:** a 2px accent outline with 2px offset on every focusable element; the tag sheet traps focus and returns it
  to the photo it opened from.
- **Dark mode** is a full theme, not an inversion: the accent changes hue (garnet to rose) to keep contrast.
- **Zoom and text size:** layouts reflow to one column at 320px wide and 200% text.

## Inclusivity

Hair-type equality is a product non-negotiable (CLAUDE.md). Review of the current wording, 2026-11-20:

| Where | Today | Issue | Proposal |
|---|---|---|---|
| Quiz, hair type (`web/src/pages/public/quiz.ts`) | Vertical list, Straight first; hints "Lies flat from root to tip", "Loose S-shaped bends", "Defined loops or spirals", "Tight coils or zig-zags" | The hints are good and neutral. A vertical list with Straight on top reads as a default and the others as departures from it. | A 2×2 grid of equal cards with glyphs (order stays straight → coily, as a spectrum). Hint "Falls flat from root to tip". Add a secondary line with the familiar codes ("Often called 3A–3C") so people who know their type find it, without making the numbered system the primary label. |
| Quiz, hair type, no "mixed" | Single choice, no help | Many people have more than one pattern. Forcing a pick without guidance causes anxiety and bad data. | Hint: "If it's a mix, pick what you see most." Add "Not sure? See photo examples" (one photo per type, drawn from the stylists' tagged work). Consider a `MIXED`/`NOT_SURE` value later (open question). |
| Quiz and tagging, "texture" | Client sees "How thick are your strands?"; stylist sees "Texture" | In textured-hair care, "texture" usually means *curl pattern*. The stylist label invites stylists to tag curl pattern here. | Call it **strand thickness** everywhere people read it; keep `texture` in code and the API. |
| Quiz, length | "Pixie or cropped / Short / Medium / Long" | Curly and coily hair shrinks; clients don't know whether to measure stretched. "Pixie" alone excludes short natural styles. | "Cropped: pixie, buzz or TWA". Hint: "Measure curls and coils as they usually sit. Either way is fine." |
| Quiz, past issues | "Has a stylist let you down before?" with negative labels | Fine as data; as copy it starts the relationship with grievances. | "What should your next stylist get right?" with positive labels mapped 1:1 to the same codes ("Know my hair type" = `NO_EXPERIENCE_WITH_MY_HAIR_TYPE`), plus "In your own words" (the existing `pastIssuesNote`), which feeds the echo. |
| Tag sheet (`web/src/stylists/TagSheet.tsx`, `types.ts`) | Labels from `words()` ("Straight", "Fine"), no hints, different from the client's words | The two sides of the match can mean different things by the same word. | One shared label set (words, hints, glyphs) used by both the quiz and the tag sheet. |
| Services | Cut and Color first; Braids, Locs and Silk press present | Good coverage. | Keep; on the client quiz show all as equal chips (no "popular" ordering). |
| Match reason | "12 similar cuts on wavy hair" | Good: names the hair type. | Always include the client's hair type in the reason; if the evidence is from a neighbouring type, say so ("…on wavy and curly hair"). |

Imagery rules are under *Imagery and photo treatment*. Fairness is also made visible in the product: the admin match log
opens with Book-click rates by the client's hair type (flagging one that falls behind) and shows each list's evidence
photos tagged by hair type, and the stylist's go-live screen shows "Your work by hair type" as
information, never as a score or a nudge to tag differently from what they actually do.

## Open questions

1. **Photography.** Placeholders work for the pilot, but the landing page needs real photos of real work across all four
   hair types. Ask pilot stylists for permission to feature specific portfolio photos (a consent flag per photo would be
   a stylists-module change), or commission a small shoot in Columbus?
2. **Mixed or unsure hair type.** Add `MIXED`/`NOT_SURE` to the hair-type codes, or keep one pick with "pick what you see
   most"? A new code changes matching's compatibility weights and the fairness report.
3. **Budget ranges.** The prototype shows dollar ranges per tier ($60–$120 …). They differ by service; confirm with the
   stylist on the team or drop the numbers.
4. ~~Browse all stylists~~ **Decided 2026-11-20:** yes, "See all stylists" beside the recommendations, filterable by
   service and hair type. Open: does it show stylists from every market or only the request's (`columbus-oh`)?
5. **When to switch review on.** The "review before publish" switch exists for poor early quality on textured hair.
   Agree the trigger in advance (for example, the coily or curly Book-click rate under two-thirds of the overall rate
   after 30 requests of that type), so turning it on is a rule, not a mood.
6. **Stylist-specific entry links** (`/find?via=<stylist>`), so a stylist can share Combpatible from their own Instagram.
   Tracking would need to record it.
7. ~~The name~~ **Decided 2026-11-20: Combpatible.** At 11 letters it's over the length where the wordmark needed
   testing again: the setting above (Hanken Grotesk 700, not Bodoni) is the proposal; check it in the app bar at 320px.
8. **Instant-results copy uses real counts** ("286 photos of curly cuts by 23 stylists"). The client page needs those
   two numbers from the API, or the step falls back to "Looking through stylists' work on curly hair".
