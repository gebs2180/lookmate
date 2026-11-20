# Redesign options: four directions for Combpatible

Status: **decided 2026-11-20 (Zach): A. Lookbook, with D. Strand's motion made prominent.** Applied to `web/` on
branch `claude/lookbook-redesign-232f2e`. This doc compares four full redesigns, each with a clickable phone prototype,
and recommends one; the audit below describes the app as it was before.

Why now: the running app still wears Rawmark's "Ledger" theme, and Zach's verdict is that it looks too close to
Rawmark. The goal is "very sleek, animated and right for the beauty industry", because "we are only going to get buy-in
if we wow". The audience for the wow is two groups: **stylists**, who decide whether to put their portfolio on it, and
**clients**, who decide whether to trust a match enough to book.

- [Research: how matching and beauty apps handle UX](#research)
- [Audit of what's live now](#audit)
- [The four directions](#directions)
- [Side by side](#side-by-side)
- [Recommendation](#recommendation)
- [Viewing the prototypes](#viewing-the-prototypes)

---

## Research

I looked at 14 products across dating, styling, beauty booking, services marketplaces and visual search. I borrowed
patterns from them, not their look: no logos, palettes or signature gestures. Swipe-to-reject, for instance, stays out.

| Product | Onboarding / quiz | Match reveal | Portfolio / photos | Motion | Type and colour | Trust signals | Borrow | Avoid |
|---|---|---|---|---|---|---|---|---|
| **Hinge** | Prompts and photos; profile built around answers | "Most Compatible": one daily pick, explained (Gale–Shapley) | Photos interleaved with prompts; you like a *specific* photo or answer | Restrained; cards slide | Editorial serif and black-and-white with one accent | Verified selfie badge | **Explain why** a match is a match; reacting to a *specific* piece of work | Daily scarcity mechanics |
| **Tinder** | Fast, photo-first | Full-screen "It's a match" moment | Full-bleed photo stack | Swipe physics, confetti-like celebration | Bold gradient | Photo verification | A *moment* for the match, which shouldn't feel like a search-results page | Swipe left/right (trade dress, and rejecting people is the wrong feeling here) |
| **Stitch Fix** | Long style quiz with images; "Style Shuffle" thumbs up/down | A box of picks, each with a stylist's note | Clean product grids; tests found a 2-column grid of 24 works on mobile | Calm | Warm neutrals, serif | A named stylist's note on every pick | **The note per pick** (our reason line); a picture-based quiz | Making the quiz feel like a long form |
| **Glossier** | — | — | Real skin, unretouched, lots of white | Soft | Millennial pink and sans | User photos | Unretouched, real-people imagery as the brand | Pastel-only palette (reads 2016) |
| **Rare Beauty** | Shade finder | Shade results with swatches | Swatches on many skin tones | Gentle hovers | Soft neutrals | Inclusive shade range shown up front | **Show every hair type up front**, as shade ranges show every skin tone | — |
| **Sephora Virtual Artist** | Try-on via camera | Look applied to your face | AR | Live | Black and white | — | — | Try-on/AR: realism complaints in reviews; we show real work instead |
| **Fresha** | — | Search results | Profiles with handles and media galleries | Minimal | Clean purple/black | Reviews, verified bookings | Portfolio galleries on profiles | Marketplace look where every salon feels the same |
| **Booksy** | — | Search list | Instagram-linked portfolios | Minimal | Teal | Reviews | Portfolio pulled from real work | — |
| **StyleSeat** | Search | Results by distance | Photo grids | Minimal | — | Reviews | — | Reviewers complain that clients must create a profile to book. We keep no sign-in |
| **Vagaro** | — | — | Business pages | — | — | — | Where "Book on Vagaro ↗" lands, so our hand-off must look deliberate | — |
| **Airbnb (2025)** | — | — | Big photos | Dimensional animated icons made in-house | Warm, rounded | Superhost, reviews | **Custom, ownable motion assets** as brand (our hair-type glyphs) | — |
| **Thumbtack** | Tailored questions plus photos, then AI-assisted matching | Pros ranked with a short reason | Project photos | Calm | Blue | Background-check badge, verified reviews | Short, tailored questions; a *reason* per pro | A generic marketplace feel |
| **Rover** | Pet profile quiz | Sitters ranked | Photo galleries | Calm | Green | Background-check badges, "Star Sitter" | Badge clarity (our "Sample stylist" chip must be as legible as theirs) | — |
| **Pinterest Lens / Houzz** | Visual search from a photo | Visually similar results; "Shop the look" dots | **Every photo links to the pro who made it** (Houzz); zoom-to-search | Smooth zooms | Neutral, image-first | Pro badges | **Photo in, matching photos out**: the core of our product, shown literally | — |
| **Bumble (2024 rebrand)** | — | — | — | — | — | — | — | Lesson: a loud rebrand that loses what users trusted hurt brand health |

### Patterns worth taking

1. **Photo in, photos out** (Pinterest Lens, Houzz). The matching moment should *show* the client's photo being
   compared with stylists' real work, not a spinner.
2. **One reason per pick** (Stitch Fix's note, Hinge's "Most Compatible", Thumbtack). We already have the reason line
   ("9 curly cuts like your photo"). Make it the hero of each card, in plain words, never a score.
3. **Echo the client back** (Hinge prompts). Quoting the client's own words above the picks ("Last time they
   straightened it…") shows we listened. It is the cheapest trust builder we have.
4. **A moment for the match** (Tinder, Hinge). Reveal the picks with ceremony, then get out of the way.
5. **Every hair type up front** (Rare Beauty's shade range). The landing page and quiz should show straight, wavy, curly
   and coily hair equally, from the first screen.
6. **Ownable motion assets** (Airbnb 2025). Our hair-type glyphs can become the brand's signature, animated.
7. **Clear badges** (Rover, Thumbtack). "Sample stylist" must be clear on every demo card, never small print.
8. **Never make clients sign up to book** (the StyleSeat complaint). Ours is already right; say it on the landing page.

Sources: mixpanel.com/blog/how-hinge-disrupted-online-dating; hinge.co; en.wikipedia.org/wiki/Hinge_(app);
multithreaded.stitchfix.com (Style Shuffle, 2016); qz.com on Style Shuffle; fresha.com/blog/searchable-professional-profiles;
Capterra and Trustpilot reviews of StyleSeat; itsnicethat.com, wallpaper.com and Bloomberg on Airbnb's 2025 icons;
thumbtack.com/guide/content/a-first-look-at-our-new-ai-powered-experience; thumbtack.com/safety;
rover.com/background-checks; rover.com/star-sitter; newsroom.pinterest.com (visual search features);
houzz.com/pro-learn/blog/insiders-guide-your-houzz-profile; fastcompany.com/91107932 and YouGov on Bumble's rebrand;
community.sephora.com and Business Insider on Virtual Artist. Tinder, Glossier, Rare Beauty and Vagaro come from
general product knowledge.

---

## Audit

What's live (`web/src`, run locally with the demo stylists):

- **It is Rawmark.** Manrope 800, navy and green, stacked full-width buttons, the Ledger card style. Nothing says
  beauty, and nothing says Combpatible.
- **The "Chair-side" direction was never applied.** [design-direction.md](design-direction.md) proposes Bodoni Moda +
  Hanken Grotesk and a garnet accent, with tokens in [tokens.css](tokens.css). None of it reached the app. This doc
  supersedes that look; its principles (privacy, tone, accessibility, inclusivity) still stand.
- **The quiz reads like a form.** Radio lists with labels. The hair-type glyphs exist but are small and static.
- **The matching moment is a spinner.** The biggest opportunity: it's the moment where we prove the product works.
- **The reveal (`/m/:token`) is a list of StylistCards** with small photos. The reason line is there but not the hero.
- **The directory shows broken or placeholder photos** for stylists without uploads, with select-box filters.
- **Stylist portfolio management** works, but looks like an admin tool. Stylists judge us on how *their* work looks.

---

## Directions

Every prototype runs the same story: Amara has curly, medium, shoulder-length hair and wants a cut. Her inspiration
photo is a curly shag. She told us that last time a stylist straightened her hair to cut it and it came out too short.
She gets three picks, all labelled **Sample stylist**:

- **Maya Brooks** (Short North): "9 curly cuts like your photo", books on Vagaro
- **Nia Carter** (Grandview): "6 cuts and colour on curly hair", books on GlossGenius
- **Priya Nair** (Easton): "4 layered cuts on wavy and curly hair", books on Booksy

Every prototype covers the landing, the hair-type question, the photo step, the matching moment, the reveal, a stylist
portfolio and the directory. Each has a "Reduced motion" toggle and follows `prefers-reduced-motion`. All keep the
fixed rules:

- Combpatible in all user-facing text.
- No sign-in for clients.
- "Book on {site} ↗" hand-offs.
- A "you can book anyone" box on the reveal.
- A private-photo note wherever a photo is involved.
- No scores or percentages.

### A. Lookbook: the editorial magazine

[Prototype](prototypes/lookbook/index.html)

**Concept and mood.** Combpatible as a beauty magazine that's about *you*. Warm paper, a serif display face, thin
rules and red editor's marker. The stylists' work is treated like an editorial shoot: big, cropped with intent,
captioned. It feels like *Allure*'s salon issue rather than an app. Credible, grown-up, photo-first.

**Key screens.**
- **Landing:** headline "The stylist who's already done *your* look." over a slowly drifting strip of work across all
  four hair types, then the call to action and the privacy line.
- **Quiz:** one question per page, set like a magazine questionnaire. Hair type is a 2×2 of large drawn glyphs with
  plain hints ("Defined loops or spirals").
- **Matching:** the **contact sheet**. The client's photo sits beside 16 greyscale frames of stylists' work. A red
  marker circles three of them, the rest dim, and the circled ones come up in colour. It's how an editor picks shots,
  and it shows the client what's actually happening.
- **Reveal:** her own words as a pull quote, then the picks as magazine covers: full-bleed photo, name as a masthead,
  the reason as the coverline, three evidence photos and Book. Covers scroll-snap sideways with a dots indicator;
  "Your 3 picks / All stylists" tabs sit above them.
- **Portfolio:** a feature spread: hero photo, a before/after pair, then a captioned grid with hair type on every caption.
- **Directory:** an index page: a two-column grid of covers, with filter chips.
- **Stylist studio (lighter touch):** the same spread layout as a live preview. Uploading a photo drops it into the
  stylist's own spread; tagging the hair type writes its caption. Stylists see their work presented beautifully, which
  is the pitch.

**Type.** Instrument Serif (display) with Inter Tight (UI), both free and self-hostable.

**Colour tokens.** Paper `#f5f0e8`, ink `#1a1614`, ink-2 `#4f4640`, rule `#d8cfc2`, oxblood accent `#8a1c2c`, marker
red `#c2263a` (drawing only, not text), sample chip `#6b4b12` on `#f3e3bf`. Ink on paper is about 16:1; oxblood on
paper is above 8:1.

**Motion (named).**

| Name | What it does | Timing |
|---|---|---|
| page-lift | clip-path wipe between screens, like turning a page | 680ms, cubic-bezier(.7,0,.2,1) |
| ink-rise | headline words rise out of a mask | 560ms, 45ms stagger |
| drift | hero strip moves slowly sideways | 38s loop |
| frame-in | contact-sheet frames appear | 40ms stagger |
| marker-draw | red circle drawn around a pick (SVG stroke) | 720ms |
| cover-in | reveal covers slide up in turn | 760ms, 140ms stagger |

**Photo treatment.** Full-bleed, tight editorial crops at 4:5 and 3:4; greyscale before selection, colour after;
captions always name the hair type and service.

**Effort and risk.** Low. CSS and SVG only; the Motion library (motion.dev, about 18 KB) or the View Transitions API
for page-lift in production. Mid-range phones: clip-path and transform animate on the compositor, and the drift strip
pauses off-screen. Accessibility: high contrast; reduced motion turns wipes into cuts and shows the marker circles
pre-drawn.

**Why it wows.** *Stylists:* their work looks like an editorial shoot, which is how they want to be seen and what they
already post. *Clients:* the contact sheet makes the matching visible and believable, and the pull quote shows we
listened.

### B. Afterglow: the salon after hours

[Prototype](prototypes/afterglow/index.html)

**Concept and mood.** A dark, warm room lit by vanity-mirror bulbs. Aubergine-black, champagne light and soft glass.
Luxurious and a little cinematic, like the moment the lights come up on a finished look.

**Key screens.**
- **Landing:** an arched vanity mirror ringed by breathing bulbs, cross-fading through work on every hair type, with
  captions. The headline sits beneath it, with "your look" glowing.
- **Quiz:** glass rows with a glyph tile; tapping sends a soft light out from your finger (glow-select).
- **Matching:** the **ring light**. The client's photo sits in a circle while a ring of light fills around it over about
  3.6s, stylists' work flickers inside, and a narrator line changes beneath it.
- **Reveal:** her quote in a glass panel, then three arched cards. Each photo comes up from fogged glass (surface) and
  a sheen sweeps across it.
- **Portfolio:** full-height hero fading into the dark, glass stat chips, before/after, masonry.
- **Directory:** arched portrait tiles.
- **Stylist studio:** the mirror frame as the upload preview: "this is how clients will see you".

**Type.** Gloock (high-contrast display serif) with Figtree (UI).

**Colour tokens.** Night `#120d10`, night-2 `#1c1519`, text `#f6ece4` (16:1), muted `#bfaea5` (8.6:1), champagne accent
`#ecc9a0` (11:1), rose `#e79a8a`, bulb `#ffe7c2`, glass `rgb(255 240 228 / .06)` with a 14% line.

**Motion (named).**

| Name | What it does | Timing |
|---|---|---|
| lights-down / lights-up | screen dims and blurs out; the next comes up from the dark | 520ms / 720ms (+160ms delay) |
| bulb | mirror bulbs breathe in sequence | 2.4s loop, 120ms offsets |
| sheen | band of light crosses the main button | every 4.5s |
| glow-select | radial light from the tap point | 700ms |
| ring-fill | ring light fills during matching | 3600ms, cubic-bezier(.45,.05,.3,1) |
| surface | evidence photo de-blurs from fogged glass | 1100ms, 220ms stagger |
| sweep | sheen across each pick photo | 1300ms |

**Photo treatment.** Arched crops (border-radius 999px at the top), which keep the full length of the hair, unlike a
circle; warm, slightly dimmed until revealed.

**Effort and risk.** Medium. `backdrop-filter` and animated `filter: blur()` are the most expensive things in any of the
four directions. On a mid-range Android, blur on several large images at once can drop frames, so production would
fade in pre-blurred low-quality placeholders rather than animate blur. A dark UI also makes photos with bright
backgrounds feel harsh, and many stylists' phone photos are taken in bright salons. Accessibility: contrast is good,
but low-light themes are harder for some users with low vision, so we'd need a light mode, which doubles the visual QA.

**Why it wows.** It's the most dramatic in a demo: the ring light and the photos surfacing *feel* premium. Stylists
who do glam, colour and evening work will love it. The risk is that it reads as a nightclub to clients who just want
a good curly cut, and it's furthest from "trust us with your photo".

### C. Swatch: the colour swatch book

[Prototype](prototypes/swatch/index.html)

**Concept and mood.** The swatch ring on a salon's back bar, made playful. Cream, copper, honey and plum; chunky
rounded type; cards that tilt, fan and spring. Friendly, confident and fun to share. It's the most "young beauty
brand" of the four.

**Key screens.**
- **Landing:** a fan of five swatch cards (one per hair type and service) springs open around a pivot, with a plum
  sticker reading "Real work by real stylists". The headline has a honey brushstroke under "their work".
- **Quiz:** four big, slightly tilted colour cards with chunky glyphs; selecting one squashes it, straightens it and adds
  a check sticker.
- **Matching:** **shuffle the deck**. The client's card is pinned to one side while stylists' swatch cards flick off
  the ring one by one, with a counter running to 214 photos. Then three cards are pulled out and stacked.
- **Reveal:** "Amara, meet your *curl people*", her quote, and the three picks **dealt as a deck**. Next and
  previous buttons (and the arrow keys) deal through it. There's no like or nope, deliberately: the deck is for
  browsing, not rejecting.
- **Portfolio:** a rounded cover, colour service chips, before/after and a mixed grid.
- **Directory:** tilted cards in a two-column grid.
- **Stylist studio:** each upload becomes a swatch card, and tagging it picks the card's colour. It's playful enough that
  stylists might screenshot it for their own Instagram.

**Type.** Bricolage Grotesque (display, 800) with DM Sans (UI).

**Colour tokens.** Cream `#fbf3e6`, card `#fffaf2`, ink `#23171c` (16:1), ink-2 `#5d4b52` (7.6:1), copper text
`#b24a1f` (5.3:1), copper fill `#c4572a` (white text 4.6:1), honey `#e2a33b` (fills only), plum `#5b2a5e` (10:1), blush
`#f2c9b8`, sage `#a9b79a`.

**Motion (named).**

| Name | What it does | Timing |
|---|---|---|
| card-slide | next screen slides over like the next swatch, with a slight tilt and overshoot | 620ms, cubic-bezier(.34,1.56,.64,1) |
| pop | content lands with a spring | 640ms, 70ms stagger |
| fan-open | landing swatches fan out around a pivot | 900ms spring, 70ms stagger |
| swipe-highlight | honey brushstroke paints under a word | 600ms, +700ms delay |
| squish-select | quiz card squashes and springs straight; the glyph wiggles | 380–700ms spring |
| flick | swatch cards flick off during matching | 210ms apart |
| deal | reveal cards dealt onto the table | 700ms spring, 140ms stagger |

**Photo treatment.** Rounded rectangles inset in a cream card with a caption strip, like a swatch; tilted ±1–4°.

**Effort and risk.** Low to medium. Transform-only animation, cheap on any phone. The risk is tonal: springy and
playful can read as less expert, and stylists in their 40s may find it young. The deck also hides picks 2 and 3 behind
a tap, which may lower how often clients look at them. We'd measure it, or show all three as a list on wider screens.

**Why it wows.** It's the most *shareable*: the fan and the deal are delightful and it doesn't look like any other
booking app. Clients aged 18–30 would screenshot it, and stylists would see a brand with energy.

### D. Strand: one line, drawn

[Prototype](prototypes/strand/index.html)

**Concept and mood.** A single copper line, a strand of hair, draws the whole experience. Bone paper, graphite,
generous whitespace and a light, high-contrast serif. Quiet and precise, like a gallery or a high-end atelier. The
line is the brand mark, the progress bar, the matching animation and the connector from reason to evidence.

**Key screens.**
- **Landing:** "Matched by *the work*, not the map." beside one strand that **morphs** straight → wavy → curly → coily
  on a loop. The matching photo in the strip below lights up in colour as the strand changes.
- **Quiz:** a hairline list of the four types. The strand above takes the shape of whichever you hover or choose;
  the chosen name turns copper and italic.
- **Matching:** the **thread**. A line runs out of the client's photo and loops through a field of greyscale work,
  touching each frame in turn. Then three frames come up in colour, outlined in copper, and the rest fade.
- **Reveal:** her quote in italic serif, then three picks as an editorial list. Each has a copper thread that draws from
  the reason line down into its evidence photos, literally connecting the claim to its proof.
- **Portfolio:** name, counts by hair type in large light numerals, a tall cover and a tight grid.
- **Directory:** a hairline list with a portrait thumbnail per row, scannable like an index.
- **Stylist studio:** the line becomes a checklist that draws itself as a stylist reaches the 3 tagged photos needed to
  go live.

**Type.** Fraunces (variable display serif, light weights) with Instrument Sans (UI).

**Colour tokens.** Bone `#f4f1ec`, paper `#fbfaf7`, graphite `#1e1d1b` (15.6:1), grey `#5f5b55` (6.2:1), hair-rule
`#cfc8bd`, copper `#a54a22` (5.6:1).

**Motion (named).**

| Name | What it does | Timing |
|---|---|---|
| thread-cut | copper line sweeps across between screens | 640ms, cubic-bezier(.65,0,.35,1) |
| strand-morph | strand changes hair type (CSS `d` transition) | 900ms |
| strand-draw | line draws root to tip (stroke-dashoffset) | 1600ms |
| thread | matching line travels through the field | 2600ms |
| tie-in | reason-to-evidence connector draws on each pick | 1100ms, 300ms stagger |

**Photo treatment.** Square-cornered 3:4 crops, no shadows; greyscale until chosen; captions as small paper labels.

**Effort and risk.** Low to medium. SVG strokes are cheap. Morphing `d` in CSS works in Chromium and Firefox but not
Safari, so production would use Motion's path interpolation (or precomputed keyframes) for iOS. The look depends on
whitespace and type, so it can feel cold if photos are weak. Accessibility: strong contrast; reduced motion shows
lines fully drawn and morphs as instant swaps.

**Why it wows.** It's the most *ownable*: no one else has a hair strand as an interface. The tie-in connector is the
clearest explanation of a match of the four. Stylists would read it as premium and serious about craft.

---

## Side by side

| | A. Lookbook | B. Afterglow | C. Swatch | D. Strand |
|---|---|---|---|---|
| Mood | Editorial magazine | Luxe, after hours | Playful swatch book | Quiet gallery line |
| Display / UI type | Instrument Serif / Inter Tight | Gloock / Figtree | Bricolage Grotesque / DM Sans | Fraunces / Instrument Sans |
| Base / accent | Paper / oxblood + marker red | Aubergine-black / champagne | Cream / copper, honey, plum | Bone / copper |
| Matching moment | Contact sheet with marker circles | Ring light filling | Swatch deck flicking | Thread through the field |
| Reveal | Magazine covers, sideways | Arched cards surfacing | Dealt deck | Editorial list with tie-in thread |
| Photo shape | Full-bleed editorial crops | Vanity arch | Inset swatch card | Square 3:4 |
| Signature motion | page-lift, marker-draw | surface, ring-fill | fan-open, deal | strand-morph, thread |
| Libraries | None (Motion optional) | None, but blur is costly | None (Motion optional) | Motion for iOS path morph |
| Mid-range phone | Good | Weakest (blur, backdrop-filter) | Good | Good |
| Contrast | Excellent | Good (dark mode only) | Good; honey fills only | Excellent |
| Stylists would say | "My work looks like a shoot" | "Glam" | "Fun" | "Classy, serious" |
| Clients would say | "They get it" | "Fancy" | "Cute, shareable" | "Calm, clear" |
| Risk | Could feel safe | Mood vs. trust, performance | May read as junior | Can feel cold with weak photos |
| Effort to ship | Lowest | Highest | Low–medium | Medium |

---

## Recommendation

**Build A, Lookbook, and borrow D's strand as its signature motion.**

- **It's the most credible to stylists**, the side we have to win first. It presents their work as an editorial shoot,
  and stylists already think in lookbooks and shoots. It's furthest from Rawmark: serif, paper, photo-led, with no
  dashboard feel.
- **The contact sheet is the best matching moment of the four**: it *shows* the work being compared with the client's
  photo, which is our whole product claim.
- **It's the cheapest to build and the safest on phones and contrast.** It needs no libraries beyond the optional
  Motion, and nothing that depends on blur.
- **The covers reveal keeps all three picks one swipe apart**, unlike Swatch's deck, and leads with the reason line.

What to take from the others:

- **From Strand:** the animated hair-type glyph that morphs between types. Use it in the quiz, as the loading mark and
  in the logo. Also take the tie-in thread from reason to evidence on each cover. Those make the brand ownable.
- **From Afterglow:** the "surface" de-blur as a one-off on the reveal hero only, using pre-blurred placeholders.
- **From Swatch:** nothing structural, but its warmth is a useful check. If Lookbook feels too serious in testing,
  copper and honey could replace oxblood.

Before building, show the four prototypes to two or three Salon Lofts stylists and two clients with curly or coily
hair. That takes five minutes each, on a phone, using the reduced-motion toggle too. If stylists pick Strand outright,
it's an equally safe build.

**If approved,** the next step is a token and component pass in `web/src`: fonts self-hosted, tokens replacing the
Ledger theme, and screens rebuilt in the order landing → reveal → matching → quiz → portfolio → directory → studio.
That should be its own session and PR, and it would replace the type and colour sections of
[design-direction.md](design-direction.md).

---

## Viewing the prototypes

The prototypes are self-contained HTML. They load fonts from Google Fonts and photos from `docs/ux/prototypes/photos/`,
which is **git-ignored**. The photos are Pexels demo photos: fine for mockups, never for ML, and never committed.
Without them, tinted placeholders show instead.

From the repo root:

```powershell
New-Item -ItemType Directory -Force docs/ux/prototypes/photos; Copy-Item web/demo-photos/*.jpg docs/ux/prototypes/photos/
```

```powershell
python -m http.server 5199 --directory docs/ux/prototypes
```

Then open http://localhost:5199/lookbook/ (or `afterglow/`, `swatch/`, `strand/`) on a phone-sized window. On a wide
screen, a bar at the bottom jumps between screens and toggles reduced motion. Tap through from the landing for the
real flow: the quiz → the photo step (tap the box to "choose" a photo) → matching → reveal.
