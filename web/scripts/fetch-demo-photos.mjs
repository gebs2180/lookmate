#!/usr/bin/env node
// Fills the /admin/demo photo slots (maya-1.jpg … nia-5.jpg) with salon photos from the Pexels API
// (https://www.pexels.com/api/). Writes them, CREDITS.md and a contact sheet (index.html) into web/demo-photos/, which
// is git-ignored: the images are never committed.
//
//   npm run demo:photos            # fetch the slots that don't have a file yet
//   npm run demo:photos -- --force # fetch every slot again (files you added by hand are kept)
//
// Needs a free Pexels API key: PEXELS_API_KEY in the environment, or a PEXELS_API_KEY= line in web/.env.local
// (git-ignored). Never give it a VITE_ prefix: Vite would bundle it into the app. The key is never printed.
//
// Pexels terms, which this script keeps to:
// - These photos are ONLY shown as demo portfolio photos on the local /admin/demo stylists. Never use them for machine
//   learning, embeddings, matching evaluation or labelled sets, or anything else: the Pexels terms forbid it without
//   permission. The real matcher is tuned on pilot stylists' own photos.
// - No bulk or systematic copying: at most a few searches per slot, and only the one chosen photo per slot is
//   downloaded. The API allows 200 requests an hour; this spaces its calls, reads X-Ratelimit-Remaining, caps a run
//   well under the limit and stops on 429.
// - Pexels asks for credit "when possible": CREDITS.md and index.html name each photo's photographer and Pexels page.
//
// The slots come from src/admin/demoStylists.ts (Node strips its types when importing it), so there's one list.

import { mkdir, readFile, writeFile, access, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const { DEMO_STYLISTS } = await import('../src/admin/demoStylists.ts')

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(WEB, 'demo-photos')
const CREDITS = join(OUT, 'CREDITS.md')
const SHEET = join(OUT, 'index.html')
const API = 'https://api.pexels.com/v1/search'

// What the upload path accepts (backend storage ImageGuard, storage.max-upload-bytes). The demo page uploads the
// picked file as it is, so the file itself has to pass. We stay well under the byte limit to keep the demo quick.
const MAX_BYTES = 12 * 1024 * 1024
const MAX_SIDE_PX = 16_000
const MAX_PIXELS = 100_000_000
const MIN_SIDE_PX = 400

const API_GAP_MS = 2_000
const MAX_API_CALLS = 90 // per run, well under the 200 an hour Pexels allows; queries are cached and shared by slots
const QUERIES_PER_SLOT = 3
const PER_PAGE = 80 // one search returns plenty of candidates; only the chosen photo is downloaded

const force = process.argv.includes('--force')

// --- API key --------------------------------------------------------------------------------------------------------

async function apiKey() {
  const fromEnv = process.env.PEXELS_API_KEY?.trim()
  if (fromEnv) return fromEnv
  try {
    const text = await readFile(join(WEB, '.env.local'), 'utf8')
    const m = /^\s*PEXELS_API_KEY\s*=\s*(.*?)\s*$/m.exec(text)
    const value = m?.[1].replace(/^(['"])(.*)\1$/, '$2').trim()
    if (value) return value
  } catch {
    // no .env.local: fall through to the message below
  }
  return null
}

const KEY = await apiKey()
if (!KEY) {
  console.error(`No Pexels API key found.

The demo photos come from the Pexels API, which needs a free key:
  1. Sign in at https://www.pexels.com/api/ and request a key (free, instant).
  2. Add this line to web/.env.local (git-ignored; create the file if it isn't there):
       PEXELS_API_KEY=your-key
     or set PEXELS_API_KEY in the environment. Don't use a VITE_ prefix: Vite would bundle it into the app.
  3. Run npm run demo:photos again.`)
  process.exit(1)
}

// --- Searching ------------------------------------------------------------------------------------------------------

// Short phrases people use in Pexels descriptions. Searches end with "salon" or "hairstylist" so the stylist-at-work
// and finished-in-the-salon shots come first.
const SERVICE_WORDS = {
  CUT: 'haircut',
  COLOR: 'hair color',
  BALAYAGE: 'balayage',
  HIGHLIGHTS: 'hair highlights',
  BRAIDS: 'braids',
  LOCS: 'dreadlocks',
  SILK_PRESS: 'hair straightening',
  EXTENSIONS: 'hair extensions',
  TREATMENT: 'hair wash',
  STYLING: 'hair styling',
}
const HAIR_WORDS = { STRAIGHT: 'straight hair', WAVY: 'wavy hair', CURLY: 'curly hair', COILY: 'afro hair' }
// Braids and locs say the texture already; adding "afro hair" pulls in loose afros instead.
const OWN_TEXTURE = new Set(['BRAIDS', 'LOCS'])

/** Search phrases for a slot, most specific first. */
function queries(photo) {
  const service = SERVICE_WORDS[photo.service]
  const hair = OWN_TEXTURE.has(photo.service) ? 'black woman' : HAIR_WORDS[photo.hairType]
  const caption = photo.caption
    .replace(/^(before|after):\s*/i, '')
    .replace(/\b3C\b/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  return [...new Set([`${service} ${hair} salon`, `hairstylist ${service} ${hair}`, `${caption} ${OWN_TEXTURE.has(photo.service) ? '' : hair}`.trim()])]
}

let lastCall = 0
let apiCalls = 0
let remaining = null
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const searched = new Map()

class StopRun extends Error {}

async function search(q) {
  if (searched.has(q)) return searched.get(q)
  if (remaining !== null && remaining <= 3) throw new StopRun('Pexels says the request quota is nearly used up; run again in an hour.')
  if (apiCalls >= MAX_API_CALLS) throw new StopRun(`Stopped after ${MAX_API_CALLS} Pexels searches (the per-run cap); run again in an hour for the rest.`)
  const wait = lastCall + API_GAP_MS - Date.now()
  if (wait > 0) await sleep(wait)
  const params = new URLSearchParams({ query: q, per_page: String(PER_PAGE), locale: 'en-US' })
  lastCall = Date.now()
  apiCalls++
  const res = await fetch(`${API}?${params}`, { headers: { Authorization: KEY, Accept: 'application/json' }, signal: AbortSignal.timeout(30_000) })
  const left = res.headers.get('x-ratelimit-remaining')
  if (left !== null && Number.isFinite(Number(left))) remaining = Number(left)
  if (res.status === 429) {
    const reset = Number(res.headers.get('x-ratelimit-reset'))
    const when = Number.isFinite(reset) && reset > 0 ? ` (resets ${new Date(reset * 1000).toLocaleTimeString()})` : ''
    throw new StopRun(`Pexels rate limit reached${when}; run again later. Photos saved so far are kept.`)
  }
  if (res.status === 401 || res.status === 403) throw new StopRun(`Pexels refused the API key (HTTP ${res.status}). Check PEXELS_API_KEY in web/.env.local.`)
  if (!res.ok) throw new StopRun(`Pexels search failed: HTTP ${res.status}`)
  const photos = (await res.json()).photos ?? []
  searched.set(q, photos)
  return photos
}

// --- Picking from the alt text --------------------------------------------------------------------------------------

// Pexels has no tags, so the description (alt) is all there is to go on. A placeholder is better than a photo that
// contradicts the tags the demo stylist shows, so the rules below reject rather than guess.

// Not a woman's salon look: children, men's cuts and barbershops, products and flat lays, dolls, wigs on stands.
const NEVER =
  /\b(kids?|child|children|toddler|baby|babies|boys?|little girl|young girl|teen\w*|daughter|son|school ?girl|barber\w*|beard\w*|mustache|moustache|gentleman|guys?|men's|his hair|bottles?|products?|flat ?lay|jars?|tubes?|packag\w+|mock ?up|mannequin|dolls?|wigs?|wig stand|toy|illustration|drawing|painting|cartoon|text|logo|dog|cat|horse|animal)\b/i
// "man" alone is fine only next to a woman (a male stylist on a female client).
const MALE = /\b(man|men|male|he)\b/i
const FEMALE = /\b(woman|women|female|lady|ladies|girl|her|she|bride)\b/i
// Someone is in the picture.
const PERSON = /\b(woman|women|lady|girl|person|people|client|customer|model|hairdresser|hairstylist|stylist|bride|she|her)\b/i
// The photo is about hair.
const HAIR = /\b(hair\w*|braid\w*|cornrows?|locs|dreadlocks?|dreads|curls?|curly|afro|coils?|bob|pixie|balayage|highlights?|ponytail|twists?|blowout|bangs|fringe|extensions?|ringlets)\b/i
// Taken in a salon, or a stylist at work.
const SALON = /\b(salon|hairdresser\w*|hairstylist\w*|stylist|hair ?dresser|beauty parlou?r|styling chair|cape|client|customer)\b/i
const AT_WORK = /\b(cutting|trimming|styling|braiding|dyeing|coloring|colouring|washing|blow[- ]?drying|straightening|applying|combing|brushing|doing)\b/i

const TEXTURE_WORDS = /\b(curly|curls?|afro|coils?|coily|kinky|braid\w*|cornrows?|locs|dreadlocks?|dreads|twists?)\b/i
const STRAIGHT_WORDS = /\b(straight|sleek|straightened|flat ?iron\w*|pressed|silk press)\b/i

/** Words a photo must carry for the slot, and words that contradict it. */
const NEEDS = {
  hairType: {
    CURLY: /\b(curl\w*|ringlets?|wavy curls)\b/i,
    COILY: /\b(afro|coil\w*|kinky|natural hair|4[abc]|twists?|black hair|african)\b/i,
  },
  service: {
    BRAIDS: /\b(braid\w*|cornrows?)\b/i,
    LOCS: /\b(locs|dreadlocks?|dreads|locks)\b/i,
    BALAYAGE: /\b(balayage|ombr\w*|highlight\w*|foils?|bleach\w*|blonde|dyed|dye\w*|colou?r\w*)\b/i,
    HIGHLIGHTS: /\b(highlight\w*|foils?|streaks?|balayage|bleach\w*)\b/i,
    COLOR: /\b(dye\w*|colou?r\w*|red|copper|ginger|auburn|burgundy|brunette|blonde|pink|purple|blue|bleach\w*|foils?|tint\w*)\b/i,
    EXTENSIONS: /\b(extensions?|weave|long hair|clip[- ]in)\b/i,
    SILK_PRESS: /\b(straight\w*|flat ?iron\w*|press\w*|sleek|blow[- ]?dr\w*|blowout)\b/i,
    TREATMENT: /\b(wash\w*|shampoo\w*|condition\w*|treatment|mask|rinse\w*|sink|basin|steam\w*|moisturi\w*)\b/i,
    STYLING: /\b(styl\w*|blow[- ]?dr\w*|blowout|twist\w*|curling|round brush|updo|comb\w*|brush\w*)\b/i,
    CUT: /\b(cut\w*|haircut|scissors|trim\w*|bob|pixie|layers?|bangs|fringe|shag|salon|hairdresser|hairstylist)\b/i,
  },
  length: {
    PIXIE: /\b(pixie|short hair|cropped|short)\b/i,
  },
}

function contradicts(alt, photo) {
  const silkPress = photo.service === 'SILK_PRESS'
  switch (photo.hairType) {
    case 'STRAIGHT':
      if (TEXTURE_WORDS.test(alt) || /\bwavy\b/i.test(alt)) return 'textured hair in a straight-hair slot'
      break
    case 'WAVY':
      if (/\b(afro|coils?|coily|kinky|braid\w*|cornrows?|locs|dreadlocks?|dreads)\b/i.test(alt)) return 'coily or braided hair in a wavy-hair slot'
      break
    case 'CURLY':
    case 'COILY':
      if (!silkPress && STRAIGHT_WORDS.test(alt)) return 'straight hair in a textured-hair slot'
      break
  }
  if (photo.service !== 'BRAIDS' && /\b(braid\w*|cornrows?)\b/i.test(alt) && photo.service !== 'STYLING') return 'braids in a slot that is not braids'
  if (photo.service !== 'LOCS' && /\b(dreadlocks?|dreads|locs)\b/i.test(alt)) return 'locs in a slot that is not locs'
  if (photo.service === 'BRAIDS' && /\b(dreadlocks?|dreads|locs)\b/i.test(alt)) return 'locs in a braids slot'
  if ((photo.length === 'SHORT' || photo.length === 'PIXIE') && /\blong\b/i.test(alt)) return 'long hair in a short-hair slot'
  if (photo.length === 'LONG' && /\b(short|pixie|cropped)\b/i.test(alt)) return 'short hair in a long-hair slot'
  return null
}

/** A score for how well a Pexels photo fits the slot, or null if it doesn't fit at all. */
function score(p, photo) {
  const alt = (p.alt ?? '').trim()
  if (alt.length < 12) return null // no description: nothing to check it against
  if (NEVER.test(alt)) return null
  if (MALE.test(alt) && !FEMALE.test(alt)) return null
  if (!PERSON.test(alt) || !HAIR.test(alt)) return null
  if (contradicts(alt, photo)) return null
  // The texture word is implied by braids and locs, which Pexels describes without it.
  const hairType = NEEDS.hairType[photo.hairType]
  if (hairType && !OWN_TEXTURE.has(photo.service) && !(photo.service === 'SILK_PRESS' && photo.hairType === 'COILY') && !hairType.test(alt)) return null
  if (photo.service === 'SILK_PRESS' && photo.hairType === 'COILY' && !/\b(black|african|afro)\b/i.test(alt)) return null
  const service = NEEDS.service[photo.service]
  if (service && !service.test(alt)) return null
  const length = NEEDS.length[photo.length]
  if (length && !length.test(alt)) return null
  if (p.width && p.height && Math.min(p.width, p.height) < MIN_SIDE_PX) return null
  let s = 1
  if (SALON.test(alt)) s += 4
  if (AT_WORK.test(alt) && SALON.test(alt)) s += 2
  if (/\bhair\b/i.test(alt)) s += 1
  if (p.height >= p.width) s += 1 // portraits are more often a person than a room
  return s
}

const isSalonShot = (alt) => SALON.test(alt ?? '')

// --- Downloading ----------------------------------------------------------------------------------------------------

/** Width and height from a JPEG's SOF marker, or null if the bytes aren't a readable JPEG. */
function jpegSize(buf) {
  if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) return null
  let i = 2
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xff) return null
    const marker = buf[i + 1]
    if (marker === 0xff) {
      i++
      continue
    }
    if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      i += 2
      continue
    }
    const len = buf.readUInt16BE(i + 2)
    const isSof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc
    if (isSof) return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) }
    if (marker === 0xda) return null
    i += 2 + len
  }
  return null
}

/** The large2x size (about 1300 px on the short side), forced to JPEG: plenty for a demo and well inside the upload limits. */
function downloadUrl(p) {
  const url = new URL(p.src?.large2x ?? p.src?.large ?? p.src?.original)
  url.searchParams.set('fm', 'jpg')
  return url.toString()
}

async function download(p) {
  try {
    // Images come from the Pexels CDN, not the API, so they don't count against the request limit; the key isn't sent.
    const res = await fetch(downloadUrl(p), { redirect: 'follow', signal: AbortSignal.timeout(30_000) })
    if (!res.ok) return { error: `HTTP ${res.status}` }
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length > MAX_BYTES) return { error: `too large (${(buf.length / 1048576).toFixed(1)} MB)` }
    const size = jpegSize(buf)
    if (!size) return { error: 'not a JPEG' }
    const { width, height } = size
    if (Math.min(width, height) < MIN_SIDE_PX) return { error: `too small (${width}×${height})` }
    if (Math.max(width, height) > MAX_SIDE_PX || width * height > MAX_PIXELS) return { error: `too big (${width}×${height})` }
    return { buf, width, height }
  } catch (e) {
    return { error: e instanceof Error ? e.message : String(e) }
  }
}

const exists = (path) =>
  access(path).then(
    () => true,
    () => false,
  )

// --- CREDITS.md and index.html --------------------------------------------------------------------------------------

const md = (s) => String(s).replace(/([\\`*_[\]<>|])/g, '\\$1')
const html = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const comment = (value) => JSON.stringify(value).replace(/--/g, '-\\u002d')

const TERMS =
  'Pexels photos, shown only as demo portfolio photos on the local /admin/demo stylists. Never use them for machine learning, embeddings, evaluation or labelled sets: the Pexels terms forbid it.'

/** Rewritten after every saved photo, so a stopped run never leaves a photo without its credit. */
async function writeCredits(slots, credits, rejected) {
  const lines = [
    '# Demo photo credits',
    '',
    'Photos for the `/admin/demo` stylists, from [Pexels](https://www.pexels.com) via `npm run demo:photos`',
    '(`web/scripts/fetch-demo-photos.mjs`). Local demos only: these files are git-ignored and not part of Combpatible.',
    '',
    `${TERMS} Pexels asks for photographer credit when possible: show it with the photo.`,
    '',
    "They stand in for a stylist's portfolio and show nobody's actual work. Review them in `index.html`; to replace a",
    'poor pick, delete its file and run the script again: it remembers the deleted photo and picks a different one.',
    '',
    '| Slot | Demo caption | Photographer | Pexels page | Description |',
    '| --- | --- | --- | --- | --- |',
  ]
  for (const { photo } of slots) {
    const c = credits.get(photo.file)
    if (!c) {
      lines.push(`| ${photo.file} | ${md(photo.caption)} | (empty: placeholder) | | |`)
      continue
    }
    const by = c.photographerUrl ? `[${md(c.photographer)}](${c.photographerUrl})` : md(c.photographer)
    lines.push(`| ${c.file} | ${md(c.caption)} | ${by} | [Photo ${c.id}](${c.page}) | ${md(c.alt)} |`)
  }
  lines.push('', '<!-- Read by the script on re-runs: one line per file, then photos deleted as poor picks. -->')
  for (const { photo } of slots) {
    const c = credits.get(photo.file)
    if (c) lines.push(`<!-- credit ${comment(c)} -->`)
  }
  for (const id of rejected) lines.push(`<!-- rejected ${comment(id)} -->`)
  await writeFile(CREDITS, lines.join('\n') + '\n')
}

/** A local contact sheet: every slot with its tags, the chosen photo or "empty", and the credit. */
async function writeSheet(slots, credits, handAdded) {
  const filled = slots.filter(({ photo }) => credits.has(photo.file) || handAdded.has(photo.file)).length
  const sections = DEMO_STYLISTS.map((s) => {
    const cards = s.photos.map((photo, i) => {
      const c = credits.get(photo.file)
      const prev = s.photos[i - 1]
      const pair = photo.beforeOfNext ? 'before of the next photo' : prev?.beforeOfNext ? 'after of the previous photo' : ''
      const tags = [photo.service, photo.hairType, photo.texture, photo.length].map((t) => `<span class="tag">${html(t.toLowerCase().replace('_', ' '))}</span>`).join('')
      let media
      let credit
      if (c) {
        media = `<a href="${html(c.page)}"><img src="${html(photo.file)}" alt="${html(c.alt)}" loading="lazy"></a>`
        credit = `<p class="alt">${html(c.alt)}</p>
          <p class="credit">Photo by <a href="${html(c.photographerUrl ?? c.page)}">${html(c.photographer)}</a> on <a href="${html(c.page)}">Pexels</a>${c.salon ? '' : ' · <b>not a salon shot</b>'}</p>
          <p class="meta">search: “${html(c.query)}” · ${c.width}×${c.height}</p>`
      } else if (handAdded.has(photo.file)) {
        media = `<img src="${html(photo.file)}" alt="" loading="lazy">`
        credit = '<p class="credit">Added by hand: no Pexels credit recorded.</p>'
      } else {
        media = '<div class="empty">empty</div>'
        credit = photo.beforeOfNext
          ? '<p class="credit">Before photos stay empty unless one photographer has the same client before and after.</p>'
          : '<p class="credit">No photo fit the tags; the demo page draws a placeholder.</p>'
      }
      return `<figure>${media}<figcaption><p><b>${html(photo.file)}</b> ${html(photo.caption)}</p><p>${tags}</p>${pair ? `<p class="pair">${html(pair)}</p>` : ''}${credit}</figcaption></figure>`
    })
    return `<section><h2>${html(s.displayName)}</h2><div class="grid">${cards.join('\n')}</div></section>`
  })
  const page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Demo photos</title>
<style>
  :root { color-scheme: light dark; --bg: #faf8f5; --fg: #1f1b16; --muted: #6b6259; --card: #fff; --line: #e6e0d8; --tag: #f0ebe4; }
  @media (prefers-color-scheme: dark) { :root { --bg: #171512; --fg: #f1ece6; --muted: #a59b90; --card: #221f1b; --line: #36312b; --tag: #2e2a25; } }
  body { margin: 0; padding: 24px 16px 48px; background: var(--bg); color: var(--fg); font: 14px/1.45 system-ui, sans-serif; }
  main { max-width: 1200px; margin: 0 auto; }
  h1 { margin: 0 0 4px; font-size: 22px; } h2 { margin: 32px 0 12px; font-size: 17px; }
  .note { color: var(--muted); margin: 4px 0; max-width: 70ch; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
  figure { margin: 0; background: var(--card); border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
  img, .empty { display: block; width: 100%; aspect-ratio: 4 / 5; object-fit: cover; }
  .empty { display: grid; place-items: center; color: var(--muted); background: repeating-linear-gradient(45deg, var(--tag) 0 8px, transparent 8px 16px); }
  figcaption { padding: 8px 10px 10px; } figcaption p { margin: 4px 0; }
  .tag { display: inline-block; margin: 0 4px 2px 0; padding: 1px 6px; border-radius: 99px; background: var(--tag); font-size: 12px; }
  .pair, .meta, .alt { color: var(--muted); font-size: 12px; } .credit { font-size: 12px; }
  a { color: inherit; }
</style>
</head>
<body>
<main>
<h1>Demo photos: ${filled} of ${slots.length} slots filled</h1>
<p class="note">${html(TERMS)}</p>
<p class="note">Delete any file that doesn't fit its tags and run <code>npm run demo:photos</code> again to replace it. Then pick the files on <code>/admin/demo</code>.</p>
${sections.join('\n')}
</main>
</body>
</html>
`
  await writeFile(SHEET, page)
}

// --- main -----------------------------------------------------------------------------------------------------------

await mkdir(OUT, { recursive: true })

const slots = DEMO_STYLISTS.flatMap((s) => s.photos.map((photo, i) => ({ stylist: s, photo, prev: s.photos[i - 1] })))

// Credits from earlier runs, so skipped files keep theirs, and the photos deleted since: never picked again.
const credits = new Map()
const rejected = new Set()
const openverse = []
if (await exists(CREDITS)) {
  const text = await readFile(CREDITS, 'utf8')
  for (const m of text.matchAll(/<!-- credit (.+?) -->/g)) {
    const c = JSON.parse(m[1])
    if (c.source === 'pexels') credits.set(c.file, c)
    else openverse.push(c.file)
  }
  for (const m of text.matchAll(/<!-- rejected (.+?) -->/g)) {
    const id = JSON.parse(m[1])
    if (typeof id === 'number') rejected.add(id)
  }
}
// Photos from the old Openverse fetcher: their CC BY credits are replaced by this file, so they go too.
for (const file of openverse) {
  if (await exists(join(OUT, file))) {
    await rm(join(OUT, file))
    console.log(`${file}: removed the old Openverse photo`)
  }
}
for (const [file, c] of [...credits]) {
  if (!(await exists(join(OUT, file)))) {
    rejected.add(c.id)
    credits.delete(file)
  }
}
// A file with no Pexels credit was added by hand. Kept, never overwritten.
const handAdded = new Set()
for (const { photo } of slots) if (!credits.has(photo.file) && (await exists(join(OUT, photo.file)))) handAdded.add(photo.file)

const used = new Set([...rejected, ...[...credits.values()].map((c) => c.id)])

function creditFor(p, photo, stylist, q, got, pairedWith) {
  return {
    source: 'pexels',
    file: photo.file,
    id: p.id,
    page: p.url,
    photographer: p.photographer || 'Unknown',
    photographerUrl: p.photographer_url || null,
    alt: p.alt,
    salon: isSalonShot(p.alt),
    query: q,
    stylist: stylist.displayName,
    caption: photo.caption,
    pairedWith,
    width: got.width,
    height: got.height,
    bytes: got.buf.length,
  }
}

/**
 * A "before" for an "after" just picked: only a photo by the same photographer whose description says it's the
 * before of the same look. Pexels rarely has one, and a stranger's photo would misrepresent the pair, so usually none.
 */
function findBefore(after, beforeSlot) {
  if (!/\bafter\b/i.test(after.alt ?? '')) return null
  for (const results of searched.values()) {
    for (const p of results) {
      if (p.id === after.id || used.has(p.id) || p.photographer_id !== after.photographer_id) continue
      if (!/\bbefore\b/i.test(p.alt ?? '')) continue
      if (NEVER.test(p.alt) || contradicts(p.alt, beforeSlot)) continue
      return p
    }
  }
  return null
}

const empty = []
let fetched = 0
let stopped = null

try {
  for (const { stylist, photo, prev } of slots) {
    const path = join(OUT, photo.file)
    if (handAdded.has(photo.file)) {
      console.log(`${photo.file}: added by hand, kept`)
      continue
    }
    if (!force && credits.has(photo.file)) {
      console.log(`${photo.file}: already there, skipped`)
      continue
    }
    // Before photos are only filled together with their after (see findBefore), never searched for on their own.
    if (photo.beforeOfNext) {
      console.log(`${photo.file}: before photo, filled only with a matching after`)
      continue
    }
    const old = credits.get(photo.file)
    if (old) used.delete(old.id)
    let saved = false
    for (const q of queries(photo).slice(0, QUERIES_PER_SLOT)) {
      const ranked = (await search(q))
        .filter((p) => !used.has(p.id))
        .map((p) => ({ p, s: score(p, photo) }))
        .filter(({ s }) => s !== null)
        .sort((a, b) => b.s - a.s)
      // The best few only; a photo that fails to download is skipped, not retried.
      for (const { p } of ranked.slice(0, 3)) {
        const got = await download(p)
        if (got.error) {
          console.log(`  ${photo.file}: skipped Pexels ${p.id} (${got.error})`)
          continue
        }
        await writeFile(path, got.buf)
        used.add(p.id)
        credits.set(photo.file, creditFor(p, photo, stylist, q, got, prev?.beforeOfNext ? 'previous' : null))
        console.log(`${photo.file}: "${p.alt}" by ${p.photographer} (${got.width}×${got.height}) for "${q}"`)
        fetched++
        saved = true
        if (prev?.beforeOfNext && !handAdded.has(prev.file) && (force || !credits.has(prev.file))) {
          // A before from an earlier run belonged to the old after: it goes unless a new match is found.
          const oldBefore = credits.get(prev.file)
          const before = findBefore(p, prev)
          const gotBefore = before && (await download(before))
          if (gotBefore && !gotBefore.error) {
            await writeFile(join(OUT, prev.file), gotBefore.buf)
            used.add(before.id)
            credits.set(prev.file, creditFor(before, prev, stylist, q, gotBefore, 'next'))
            console.log(`${prev.file}: before photo "${before.alt}" by the same photographer`)
            fetched++
          } else if (oldBefore) {
            await rm(join(OUT, prev.file), { force: true })
            credits.delete(prev.file)
            console.log(`${prev.file}: removed, it no longer matches its after photo`)
          }
        }
        await writeCredits(slots, credits, rejected)
        break
      }
      if (saved) break
    }
    if (!saved) {
      if (old) {
        used.add(old.id)
        console.log(`${photo.file}: nothing better found, kept the old photo`)
      } else {
        if (await exists(path)) await rm(path)
        empty.push(photo.file)
        console.log(`${photo.file}: no photo fit the tags; the demo page draws a placeholder`)
      }
    }
  }
} catch (e) {
  if (!(e instanceof StopRun)) throw e
  stopped = e.message
}

await writeCredits(slots, credits, rejected)
await writeSheet(slots, credits, handAdded)

const filled = (list) => list.filter((photo) => credits.has(photo.file) || handAdded.has(photo.file)).length
const allPhotos = slots.map(({ photo }) => photo)
console.log(`\n${fetched} fetched this run; ${filled(allPhotos)} of ${allPhotos.length} slots filled; ${apiCalls} Pexels searches${remaining !== null ? `, ${remaining} requests left` : ''}.`)
console.log('By stylist:   ' + DEMO_STYLISTS.map((s) => `${s.slug.split('-')[0]} ${filled(s.photos)}/${s.photos.length}`).join(', '))
console.log(
  'By hair type: ' +
    ['STRAIGHT', 'WAVY', 'CURLY', 'COILY']
      .map((t) => {
        const of = allPhotos.filter((p) => p.hairType === t)
        return `${t.toLowerCase()} ${filled(of)}/${of.length}`
      })
      .join(', '),
)
if (stopped) console.log(`\n${stopped}`)
console.log(`\nContact sheet: ${SHEET}`)
console.log('Review it before a demo: delete any poor pick and run again to replace it.')
