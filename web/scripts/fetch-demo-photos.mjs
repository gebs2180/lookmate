#!/usr/bin/env node
// Fills the /admin/demo photo slots (maya-1.jpg … nia-5.jpg) with openly licensed photos from Openverse
// (https://api.openverse.org, anonymous, mostly Flickr and Wikimedia Commons). Writes them and CREDITS.md into
// web/demo-photos/, which is git-ignored: the images are never committed.
//
//   npm run demo:photos            # fetch the slots that don't have a file yet
//   npm run demo:photos -- --force # fetch every slot again
//
// The slots come from src/admin/demoStylists.ts (Node strips its types when importing it), so there's one list.
// Anonymous Openverse allows 20 requests a minute and 200 a day; this spaces its calls and makes at most a few per slot.

import { mkdir, readFile, writeFile, access, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const { DEMO_STYLISTS } = await import('../src/admin/demoStylists.ts')

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(WEB, 'demo-photos')
const CREDITS = join(OUT, 'CREDITS.md')
const API = 'https://api.openverse.org/v1/images/'
const USER_AGENT = 'combpatible-demo-fetch/1.0'
const LICENSES = ['cc0', 'pdm', 'by', 'by-sa']

// What the upload path accepts (backend storage/ImageGuard.java, storage.max-upload-bytes). The demo page uploads the
// picked file as it is, so the file itself has to pass. We stay well under the byte limit to keep the demo quick.
const MAX_BYTES = 12 * 1024 * 1024
const MAX_SIDE_PX = 16_000
const MAX_PIXELS = 100_000_000
const MIN_SIDE_PX = 400

const API_GAP_MS = 3_500 // ≈17 calls a minute, under the 20/min burst limit
const MAX_API_CALLS = 120 // three queries a slot at most, fewer with the cache; under the 200/day sustained limit
const QUERIES_PER_SLOT = 3

const force = process.argv.includes('--force')

// Openverse matches words loosely, so short phrases people use in photo titles find more than the demo captions do.
const SERVICE_WORDS = {
  CUT: 'haircut',
  COLOR: 'dyed hair',
  BALAYAGE: 'balayage hair',
  HIGHLIGHTS: 'highlighted hair',
  BRAIDS: 'box braids',
  LOCS: 'dreadlocks hair',
  SILK_PRESS: 'straightened hair',
  EXTENSIONS: 'hair extensions',
  TREATMENT: 'natural hair',
  STYLING: 'hairstyle',
}
const HAIR_WORDS = { STRAIGHT: 'straight hair', WAVY: 'wavy hair', CURLY: 'curly hair', COILY: 'afro hair' }
const LENGTH_WORDS = { PIXIE: 'short', SHORT: 'short', MEDIUM: '', LONG: 'long' }

// Words in a title or tag that mean the photo is about hair on a person.
const GOOD = /\b(hair|hairstyle|haircut|braid|braids|braided|locs|dreadlocks|dreads|curls|curly|afro|bob|pixie|balayage|highlights|blonde|brunette|redhead|ponytail|twist|twists|salon|hairdresser|stylist|portrait|woman|girl|model)\b/i
// Obvious non-matches: not a person's real hair, or not a photo.
const BAD =
  /\b(dog|cat|horse|pony|bird|animal|doll|barbie|mannequin|statue|sculpture|painting|drawing|illustration|cartoon|anime|logo|poster|diagram|map|toy|bread|challah|food|knot|rope|car|wig shop|baby|child|kid|toddler|boy|plant|flower|tree|landscape|building|beach|sunset|screenshot|meme|text|sign|book|cover|vintage|antique|engraving|drawing|sketch|fabric|wool|yarn|sexy|lingerie|bikini|nude|topless|selfie|tongue|microscope|scan|magazine|advert|packages?|tubes?|bottles?|shampoo|products?|wig|wigs|weft|mullet)\b/i

/** Search phrases for a slot, most specific first. */
function queries(photo) {
  const caption = photo.caption
    .replace(/^(before|after):\s*/i, '')
    .replace(/\b(on|and|the)\b/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  const hair = HAIR_WORDS[photo.hairType]
  const service = SERVICE_WORDS[photo.service]
  const length = LENGTH_WORDS[photo.length]
  const withHair = /hair|braid|locs|curl|coil|bob|pixie|bang|blowout|press|extension|balayage|highlight/.test(caption)
    ? caption
    : `${caption} hair`
  return [...new Set([withHair, service, `${length} ${hair}`.trim(), hair])]
}

let lastCall = 0
let apiCalls = 0
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const searched = new Map()
let dailyLeft = null

async function search(q) {
  if (searched.has(q)) return searched.get(q)
  if (dailyLeft !== null && dailyLeft <= 5) throw new Error('Openverse daily limit nearly used up; run again tomorrow.')
  if (apiCalls >= MAX_API_CALLS) throw new Error(`Stopped after ${MAX_API_CALLS} Openverse calls to stay under its daily limit.`)
  const wait = lastCall + API_GAP_MS - Date.now()
  if (wait > 0) await sleep(wait)
  // No category or extension filter: Openverse leaves both empty on most Flickr photos, so they would hide nearly all
  // of them. The download checks the bytes are a JPEG instead.
  const params = new URLSearchParams({ q, license: LICENSES.join(','), mature: 'false', page_size: '20' })
  for (let attempt = 0; attempt < 3; attempt++) {
    lastCall = Date.now()
    apiCalls++
    const res = await fetch(`${API}?${params}`, { headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' } })
    if (res.status === 429) {
      const retry = Number(res.headers.get('retry-after')) || 60
      if (retry > 120) throw new Error(`Openverse rate limit reached; try again in ${Math.ceil(retry / 60)} minutes.`)
      console.log(`  rate limited, waiting ${retry}s`)
      await sleep(retry * 1000)
      continue
    }
    if (!res.ok) throw new Error(`Openverse search failed: ${res.status} ${res.statusText}`)
    const left = Number(res.headers.get('x-ratelimit-available-anon_sustained'))
    if (Number.isFinite(left) && res.headers.has('x-ratelimit-available-anon_sustained')) dailyLeft = left
    const results = (await res.json()).results ?? []
    searched.set(q, results)
    return results
  }
  throw new Error('Openverse kept rate limiting; try again later.')
}

// Hard rejects in the title or any tag, matched inside words because Flickr tags run together ("vintagehair",
// "secondlifeavatar"). Each one let through a poor pick in a reviewed run: old salon-magazine scans, 19th-century
// portraits, virtual-world renders, origami, children, celebrity and salon-ad shots, men's cuts and barbershops (the
// demo slots are women's styles), and gallery scenes.
const NOT_A_CURRENT_PHOTO =
  /(19[0-9]0s|retro|vintage|antique|histor|century|victorian|daguerreotype|tintype|archive|museum|library of congress|magazine|scan|cover|secondlife|second life|virtual|avatar|render|\b3d\b|cgi|daz3d|imvu|sims|game|fantasy|\belf\b|origami|paper|craft|illustration|painting|drawing|cartoon|anime|doll|mannequin|statue|\bkids?\b|child|toddler|baby|\bteen|celebrit|actress|red ?carpet|premiere|\bads?\b|advert|download|promo|\bm[ae]n\b|\bmens\b|\bguys?\b|beard|barber|buzz|crew ?cut|gallery|exhibit|selective ?colou?r|palm)/i

// The slot's own words that a photo must carry, so a "coily" or "braids" slot doesn't get straight hair. A
// placeholder is better than a photo that contradicts the tags the demo stylist shows.
const MUST_HAVE = {
  hairType: { CURLY: /curl|ringlet|3[abc]\b/i, COILY: /afro|coil|kink|natural ?hair|4[abc]\b|twa\b|black ?hair/i },
  service: {
    BRAIDS: /braid|cornrow/i,
    LOCS: /\blocs?\b|dread|loc ?style/i,
    BALAYAGE: /balayage|ombre|sombre|highlight/i,
    HIGHLIGHTS: /highlight|balayage|streak/i,
    EXTENSIONS: /extension|long hair/i,
    SILK_PRESS: /straight|silk ?press|flat ?iron|blowout|pressed/i,
  },
}

function score(result, photo) {
  const tags = (result.tags ?? []).map((t) => t.name)
  const text = [result.title, ...tags].join(' ')
  if (BAD.test(result.title ?? '')) return -1
  if (NOT_A_CURRENT_PHOTO.test(text)) return -1
  // Keyword-stuffed tag lists belong to catalogue uploads (scanned books, stock dumps), not a photo of one person.
  if (tags.length > 40) return -1
  const hairType = MUST_HAVE.hairType[photo.hairType]
  if (hairType && !hairType.test(text)) return -1
  const service = MUST_HAVE.service[photo.service]
  if (service && !service.test(text)) return -1
  // A hair word in the title or tags, or it's likely a photo of something else.
  if (!/\b(hair\w*|braid\w*|locs|dreadlocks|curls|curly|afro|bob|pixie|balayage|highlights|ponytail|twist\w*|blowout|bangs|fringe|extensions)\b/i.test(text)) return -1
  if (result.mature || result.unstable__sensitivity?.length) return -1
  if (!LICENSES.includes(result.license)) return -1
  if (result.width && result.height) {
    if (Math.min(result.width, result.height) < MIN_SIDE_PX) return -1
    if (Math.max(result.width, result.height) > MAX_SIDE_PX || result.width * result.height > MAX_PIXELS) return -1
  }
  if (result.filesize && result.filesize > MAX_BYTES) return -1
  let s = 0
  const goodHits = text.match(new RegExp(GOOD.source, 'gi'))?.length ?? 0
  s += Math.min(goodHits, 6)
  if (BAD.test(text)) s -= 3
  if (/\bhair\b/i.test(result.title ?? '')) s += 3
  // Portrait-shaped photos are more often a person than a scene.
  if (result.width && result.height && result.height >= result.width) s += 1
  return s
}

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

/** Wikimedia Commons originals can be 20+ MP and are throttled; its 1280px thumbnails are plenty for a demo. */
function downloadUrl(result) {
  const m = /^https:\/\/upload\.wikimedia\.org\/wikipedia\/commons\/([0-9a-f]\/[0-9a-f]{2})\/([^/]+\.jpe?g)$/i.exec(result.url)
  if (m && result.width > 1280) return `https://upload.wikimedia.org/wikipedia/commons/thumb/${m[1]}/${m[2]}/1280px-${m[2]}`
  return result.url
}

async function download(result) {
  try {
    let res
    // One short retry when the host throttles; after that, move on to the next candidate rather than wait minutes.
    for (let attempt = 0; attempt < 2; attempt++) {
      res = await fetch(downloadUrl(result), { headers: { 'User-Agent': USER_AGENT }, redirect: 'follow', signal: AbortSignal.timeout(30_000) })
      if (res.status !== 429) break
      await sleep(Math.min(Number(res.headers.get('retry-after')) || 5, 10) * 1000)
    }
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

// --- CREDITS.md -----------------------------------------------------------------------------------------------------

const LICENSE_NAMES = { cc0: 'CC0', pdm: 'Public Domain Mark', by: 'CC BY', 'by-sa': 'CC BY-SA' }
const md = (s) => String(s).replace(/([\\`*_[\]<>|])/g, '\\$1')
const comment = (value) => JSON.stringify(value).replace(/--/g, '-\\u002d')

/** Rewritten after every saved photo, so a stopped run never leaves a photo without its credit. */
async function writeCredits(slots, credits, rejected) {
  const lines = [
    '# Demo photo credits',
    '',
    'Openly licensed photos for the `/admin/demo` stylists, fetched from [Openverse](https://openverse.org) by',
    '`npm run demo:photos` (`web/scripts/fetch-demo-photos.mjs`). Local demos only: these files are git-ignored and',
    'not part of Combpatible. CC BY and CC BY-SA photos need this attribution wherever they are shown.',
    '',
    'The "before" and "after" photos of a pair are **different people** from different photographers: real before/after',
    "pairs are not available under open licenses. They stand in for a stylist's portfolio and show nobody's actual work.",
    '',
    'Look through the photos before a demo. To replace a poor pick, delete its file and run the script again: it',
    'remembers the deleted photo and fetches a different one.',
    '',
    '| File | Demo caption | Title | Creator | License | Source |',
    '| --- | --- | --- | --- | --- | --- |',
  ]
  for (const { photo } of slots) {
    const c = credits.get(photo.file)
    if (!c) {
      lines.push(`| ${photo.file} | ${md(photo.caption)} | (none found: placeholder) | | | |`)
      continue
    }
    const creator = c.creatorUrl ? `[${md(c.creator)}](${c.creatorUrl})` : md(c.creator)
    const license = `[${LICENSE_NAMES[c.license] ?? c.license} ${c.licenseVersion}](${c.licenseUrl})`.replace(' ]', ']')
    const pair = c.pairedWith ? ` (pair with the ${c.pairedWith} photo; not the same person)` : ''
    lines.push(`| ${c.file} | ${md(c.caption)}${pair} | ${md(c.title)} | ${creator} | ${license} | [${c.provider}](${c.source}) |`)
  }
  lines.push('', '<!-- Read by the script on re-runs: one line per file, then photos deleted as poor picks. -->')
  for (const { photo } of slots) {
    const c = credits.get(photo.file)
    if (c) lines.push(`<!-- credit ${comment(c)} -->`)
  }
  for (const id of rejected) lines.push(`<!-- rejected ${comment(id)} -->`)
  await writeFile(CREDITS, lines.join('\n') + '\n')
}

// --- main -----------------------------------------------------------------------------------------------------------

await mkdir(OUT, { recursive: true })

const slots = DEMO_STYLISTS.flatMap((s) => s.photos.map((photo, i) => ({ stylist: s, photo, prev: s.photos[i - 1] })))

// Credits from earlier runs, so skipped files keep theirs, and the photos deleted since: never fetched again.
const credits = new Map()
const rejected = new Set()
if (await exists(CREDITS)) {
  const text = await readFile(CREDITS, 'utf8')
  for (const m of text.matchAll(/<!-- credit (.+?) -->/g)) {
    const c = JSON.parse(m[1])
    credits.set(c.file, c)
  }
  for (const m of text.matchAll(/<!-- rejected (.+?) -->/g)) rejected.add(JSON.parse(m[1]))
}
for (const [file, c] of [...credits]) {
  if (!(await exists(join(OUT, file)))) {
    rejected.add(c.id)
    credits.delete(file)
  }
}
const used = new Set([...rejected, ...[...credits.values()].map((c) => c.id)])

const failed = []
let fetched = 0

for (const { stylist, photo, prev } of slots) {
  const path = join(OUT, photo.file)
  // A file without a credit can't be attributed (a run stopped before writing it, or it was added by hand): fetch again.
  if (!force && credits.has(photo.file) && (await exists(path))) {
    console.log(`${photo.file}: already there, skipped`)
    continue
  }
  const old = credits.get(photo.file)
  if (old) used.delete(old.id)
  let saved = false
  for (const q of queries(photo).slice(0, QUERIES_PER_SLOT)) {
    const results = await search(q)
    const ranked = results
      .filter((r) => !used.has(r.id))
      .map((r) => ({ r, s: score(r, photo) }))
      .filter(({ s }) => s >= 2)
      .sort((a, b) => b.s - a.s)
    for (const { r } of ranked.slice(0, 4)) {
      const got = await download(r)
      if (got.error) {
        console.log(`  ${photo.file}: skipped "${r.title}" (${got.error})`)
        continue
      }
      await writeFile(path, got.buf)
      used.add(r.id)
      credits.set(photo.file, {
        file: photo.file,
        id: r.id,
        title: r.title || 'Untitled',
        creator: r.creator || 'Unknown',
        creatorUrl: r.creator_url || null,
        license: r.license,
        licenseVersion: r.license_version || '',
        licenseUrl: r.license_url,
        source: r.foreign_landing_url || r.url,
        provider: r.provider,
        query: q,
        stylist: stylist.displayName,
        caption: photo.caption,
        pairedWith: photo.beforeOfNext ? 'next' : prev?.beforeOfNext ? 'previous' : null,
        width: got.width,
        height: got.height,
        bytes: got.buf.length,
      })
      await writeCredits(slots, credits, rejected)
      console.log(`${photo.file}: "${r.title}" by ${r.creator ?? 'unknown'} (${r.license}, ${got.width}×${got.height}) for "${q}"`)
      fetched++
      saved = true
      break
    }
    if (saved) break
  }
  if (!saved) {
    if (old) used.add(old.id)
    else if (await exists(path)) await rm(path) // uncredited: can't be shown with attribution
    failed.push(photo.file)
    console.log(`${photo.file}: no usable photo found; the demo page draws a placeholder for it`)
  }
}
await writeCredits(slots, credits, rejected)

console.log(`\n${fetched} fetched, ${slots.length - fetched - failed.length} already there, ${failed.length} not found; ${apiCalls} Openverse calls.`)
console.log(`Photos and CREDITS.md: ${OUT}`)
console.log('Look through them before a demo: delete any poor pick and run again to replace it.')
