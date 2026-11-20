#!/usr/bin/env node
// Helps fill the /admin/demo photo slots (maya-1.jpg … nia-5.jpg) with photos Zach picks by hand on pexels.com.
// No network calls: Pexels' licence allows downloading single photos by hand, but its terms forbid automated or bulk
// copying. Everything goes into web/demo-photos/, which is git-ignored: the images are never committed.
//
//   npm run demo:photos                             # write demo-photos/index.html: every slot, its tags, search links
//   npm run demo:photos -- import <folder>          # assign downloaded photos to slots, one prompt per file
//   npm run demo:photos -- import <folder> --force  # replace filled slots without asking
//
// These photos are ONLY for showing on the demo stylists' profiles. Never use them for machine learning, embeddings,
// matching evaluation or labelled sets: the Pexels terms forbid it. The real matcher is tuned on pilot stylists' own
// photos. The slots come from src/admin/demoStylists.ts (Node strips its types when importing it), so there's one list.

import { copyFile, mkdir, readdir, readFile, stat, writeFile, access } from 'node:fs/promises'
import { createInterface } from 'node:readline'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const { DEMO_STYLISTS } = await import('../src/admin/demoStylists.ts')

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..')
const OUT = join(WEB, 'demo-photos')
const CREDITS = join(OUT, 'CREDITS.md')
const SHEET = join(OUT, 'index.html')

// What the upload path accepts (backend storage ImageGuard, storage.max-upload-bytes). The demo page uploads the
// picked file as it is, so the file itself has to pass. We stay well under the byte limit to keep the demo quick.
const MAX_BYTES = 12 * 1024 * 1024
const MAX_SIDE_PX = 16_000
const MAX_PIXELS = 100_000_000
const MIN_SIDE_PX = 400

const TERMS =
  'Picked by hand on pexels.com and shown only as demo portfolio photos on the local /admin/demo stylists. Never use them for machine learning, embeddings, evaluation or labelled sets, and never copy from Pexels automatically or in bulk: its terms forbid both.'

const slots = DEMO_STYLISTS.flatMap((s) => s.photos)
const slotByName = new Map(slots.map((photo) => [photo.file.replace(/\.jpg$/, ''), photo]))

const exists = (path) =>
  access(path).then(
    () => true,
    () => false,
  )

// --- Pexels search links --------------------------------------------------------------------------------------------

const SERVICE_WORDS = {
  CUT: 'haircut',
  COLOR: 'hair color',
  BALAYAGE: 'balayage',
  HIGHLIGHTS: 'hair highlights',
  BRAIDS: 'braids',
  LOCS: 'dreadlocks',
  SILK_PRESS: 'silk press',
  EXTENSIONS: 'hair extensions',
  TREATMENT: 'hair wash',
  STYLING: 'hair styling',
}
const HAIR_WORDS = { STRAIGHT: 'straight hair', WAVY: 'wavy hair', CURLY: 'curly hair', COILY: 'afro hair' }
// Braids, locs and silk presses say the texture already; "afro hair" would pull in loose afros instead.
const OWN_TEXTURE = new Set(['BRAIDS', 'LOCS', 'SILK_PRESS'])

/** One or two Pexels search phrases for a slot: service and hair type in a salon, then the caption's own words. */
function searchPhrases(photo) {
  const service = SERVICE_WORDS[photo.service]
  const hair = OWN_TEXTURE.has(photo.service) && photo.hairType === 'COILY' ? 'black woman' : HAIR_WORDS[photo.hairType]
  const caption = photo.caption
    .replace(/^(before|after):\s*/i, '')
    .replace(/\b3C\b/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
  const own = /braid|loc|curl|coil|press/.test(caption) ? caption : `${caption} ${HAIR_WORDS[photo.hairType]}`
  return [...new Set([`${service} ${hair} salon`, own])]
}

const searchUrl = (phrase) => `https://www.pexels.com/search/${encodeURIComponent(phrase)}/`

// --- Checking a file ------------------------------------------------------------------------------------------------

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

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** Why the file can't be a demo photo, or its size if it can. PNG isn't converted: that needs a dependency. */
function check(buf) {
  if (buf.length > MAX_BYTES) return { error: `too large (${(buf.length / 1048576).toFixed(1)} MB; download a smaller size)` }
  if (buf.subarray(0, 8).equals(PNG)) return { error: 'a PNG; download the photo from Pexels as JPEG instead' }
  if (buf.subarray(0, 4).toString('latin1') === 'RIFF' && buf.subarray(8, 12).toString('latin1') === 'WEBP') return { error: 'a WebP; download the photo from Pexels as JPEG instead' }
  const size = jpegSize(buf)
  if (!size) return { error: 'not a readable JPEG' }
  const { width, height } = size
  if (Math.min(width, height) < MIN_SIDE_PX) return { error: `too small (${width}×${height}; needs ${MIN_SIDE_PX} px on the short side)` }
  if (Math.max(width, height) > MAX_SIDE_PX || width * height > MAX_PIXELS) return { error: `too big (${width}×${height}; download a smaller size)` }
  return size
}

/** A credit from a Pexels download name: pexels-<photographer>-<id>.jpg, or pexels-photo-<id>.jpeg. */
function creditFromName(name) {
  const m = /^pexels-(.+?)-(\d{3,})(?:-\d+)?(?: \(\d+\))?\.(?:jpe?g|png|webp)$/i.exec(name)
  if (!m) return {}
  const photographer = m[1].toLowerCase() === 'photo' ? null : m[1].replace(/[-_]+/g, ' ')
  return { pexelsId: Number(m[2]), page: `https://www.pexels.com/photo/${m[2]}/`, photographer }
}

// --- CREDITS.md and the contact sheet -------------------------------------------------------------------------------

async function readCredits() {
  const credits = new Map()
  if (await exists(CREDITS)) {
    for (const m of (await readFile(CREDITS, 'utf8')).matchAll(/<!-- credit (.+?) -->/g)) {
      const c = JSON.parse(m[1])
      credits.set(c.file, c)
    }
  }
  return credits
}

const md = (s) => String(s ?? '').replace(/([\\`*_[\]<>|])/g, '\\$1')
const html = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const comment = (value) => JSON.stringify(value).replace(/--/g, '-\\u002d')
// Credits left by the old Openverse fetcher carry a licence (some CC BY, which needs this attribution): kept until
// the photo is replaced.
const isOpenverse = (c) => Boolean(c?.license)

async function writeCredits(credits) {
  const lines = [
    '# Demo photo credits',
    '',
    `Photos for the \`/admin/demo\` stylists, imported with \`npm run demo:photos -- import\`. ${TERMS}`,
    'Local demos only: these files are git-ignored and not part of Combpatible. Pexels asks for photographer credit',
    "when possible: show it with the photo. They stand in for a stylist's portfolio and show nobody's actual work.",
    '',
    '| Slot | Demo caption | Photographer | Pexels page | Original file |',
    '| --- | --- | --- | --- | --- |',
  ]
  for (const photo of slots) {
    const c = credits.get(photo.file)
    if (!c) lines.push(`| ${photo.file} | ${md(photo.caption)} | | | |`)
    else if (isOpenverse(c)) lines.push(`| ${photo.file} | ${md(photo.caption)} | ${md(c.creator ?? 'Unknown')} (old Openverse photo, ${md(c.license)}) | [source](${c.source}) | |`)
    else lines.push(`| ${photo.file} | ${md(photo.caption)} | ${md(c.photographer)} | ${c.page ? `[Photo ${c.pexelsId}](${c.page})` : ''} | ${md(c.original)} |`)
  }
  lines.push('', '<!-- Read by the script: one line per slot. -->')
  for (const photo of slots) if (credits.has(photo.file)) lines.push(`<!-- credit ${comment(credits.get(photo.file))} -->`)
  await writeFile(CREDITS, lines.join('\n') + '\n')
}

async function writeSheet(credits, present) {
  const sections = DEMO_STYLISTS.map((s) => {
    const rows = s.photos.map((photo, i) => {
      const c = credits.get(photo.file)
      const tags = [photo.service, photo.hairType, photo.texture, photo.length].map((t) => `<span class="tag">${html(t.toLowerCase().replace('_', ' '))}</span>`).join('')
      const links = searchPhrases(photo)
        .map((q) => `<a href="${html(searchUrl(q))}" target="_blank" rel="noreferrer">${html(q)}</a>`)
        .join(' · ')
      const prev = s.photos[i - 1]
      const pair = photo.beforeOfNext
        ? `<p class="pair">Before of ${html(s.photos[i + 1].file)}: fill both only with the same client (same photographer, same shoot), else leave this one empty.</p>`
        : prev?.beforeOfNext
          ? `<p class="pair">After of ${html(prev.file)}: fine on its own when no matching before exists.</p>`
          : ''
      let media = '<div class="empty">empty</div>'
      let credit = ''
      const mtime = present.get(photo.file)
      if (mtime) {
        media = `<img src="${html(photo.file)}?v=${mtime}" alt="" loading="lazy">`
        if (isOpenverse(c)) credit = '<p class="warn">Old Openverse photo: replace it with a Pexels pick.</p>'
        else if (c) {
          const who = c.photographer ? `Photo by ${html(c.photographer)}` : 'Photographer unknown'
          credit = `<p class="credit">${who}${c.page ? ` on <a href="${html(c.page)}">Pexels</a>` : ''} · ${html(c.original)}</p>`
        } else credit = '<p class="warn">No credit recorded: import it rather than copying it in.</p>'
      }
      return `<article>${media}<div><p><b>${html(photo.file)}</b> ${html(photo.caption)}</p><p>${tags}</p><p class="links">Search Pexels: ${links}</p>${pair}${credit}</div></article>`
    })
    return `<section><h2>${html(s.displayName)}</h2>${rows.join('\n')}</section>`
  })
  const page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Demo photos</title>
<style>
  :root { color-scheme: light dark; --bg: #faf8f5; --fg: #1f1b16; --muted: #6b6259; --card: #fff; --line: #e6e0d8; --tag: #f0ebe4; --warn: #a33c1a; }
  @media (prefers-color-scheme: dark) { :root { --bg: #171512; --fg: #f1ece6; --muted: #a59b90; --card: #221f1b; --line: #36312b; --tag: #2e2a25; --warn: #f0906b; } }
  body { margin: 0; padding: 24px 16px 48px; background: var(--bg); color: var(--fg); font: 14px/1.45 system-ui, sans-serif; }
  main { max-width: 900px; margin: 0 auto; }
  h1 { margin: 0 0 4px; font-size: 22px; } h2 { margin: 28px 0 10px; font-size: 17px; }
  .note { color: var(--muted); margin: 4px 0; }
  article { display: grid; grid-template-columns: 120px 1fr; gap: 12px; padding: 10px; margin-bottom: 8px; background: var(--card); border: 1px solid var(--line); border-radius: 10px; }
  article p { margin: 3px 0; overflow-wrap: anywhere; }
  img, .empty { display: block; width: 120px; aspect-ratio: 4 / 5; object-fit: cover; border-radius: 6px; }
  .empty { display: grid; place-items: center; color: var(--muted); background: repeating-linear-gradient(45deg, var(--tag) 0 8px, transparent 8px 16px); }
  .tag { display: inline-block; margin: 0 4px 2px 0; padding: 1px 6px; border-radius: 99px; background: var(--tag); font-size: 12px; }
  .pair, .credit, .links { font-size: 12px; } .pair { color: var(--muted); } .warn { font-size: 12px; color: var(--warn); }
  a { color: inherit; }
</style>
</head>
<body>
<main>
<h1>Demo photos: ${present.size} of ${slots.length} slots filled</h1>
<p class="note">${html(TERMS)}</p>
<p class="note">Pick a real salon photo that fits each slot's tags (a stylist at work, or a finished look in a salon) and download it as JPEG. Then run <code>npm run demo:photos -- import &lt;folder&gt;</code> and pick the files on <code>/admin/demo</code>. Leave a slot empty rather than use a wrong photo: it gets a drawn placeholder.</p>
${sections.join('\n')}
</main>
</body>
</html>
`
  await writeFile(SHEET, page)
}

/** Slot files on disk, with their modification time (so the contact sheet shows a replaced photo, not a cached one). */
async function presentFiles() {
  const present = new Map()
  for (const photo of slots) {
    const path = join(OUT, photo.file)
    if (await exists(path)) present.set(photo.file, Math.round((await stat(path)).mtimeMs))
  }
  return present
}

// --- import ---------------------------------------------------------------------------------------------------------

async function importFolder(folder, force) {
  const files = (await readdir(folder, { withFileTypes: true }))
    .filter((d) => d.isFile() && /\.(jpe?g|png|webp)$/i.test(d.name))
    .map((d) => d.name)
    .sort()
  if (!files.length) {
    console.log(`No .jpg, .jpeg, .png or .webp files in ${folder}.`)
    return
  }
  const credits = await readCredits()
  const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: Boolean(process.stdin.isTTY) })
  const lines = rl[Symbol.asyncIterator]()
  const ask = async (q) => {
    process.stdout.write(q)
    const { value, done } = await lines.next()
    if (!process.stdin.isTTY) process.stdout.write((value ?? '') + '\n')
    return done ? null : value.trim()
  }

  let imported = 0
  try {
    for (const name of files) {
      const buf = await readFile(join(folder, name))
      const size = check(buf)
      if (size.error) {
        console.log(`\n${name}: skipped, ${size.error}`)
        continue
      }
      const credit = creditFromName(name)
      const present = await presentFiles()
      const open = [...slotByName].filter(([, photo]) => !present.has(photo.file)).map(([slot]) => slot)
      console.log(`\n${name} (${size.width}×${size.height}${credit.photographer ? `, by ${credit.photographer}` : ''})`)
      console.log(`Open slots: ${open.length ? open.join(' ') : 'none'}`)
      let photo = null
      for (;;) {
        const answer = await ask('Slot (e.g. maya-2), Enter to skip, q to quit: ')
        if (answer === null || answer.toLowerCase() === 'q') return
        if (answer === '') break
        photo = slotByName.get(answer.toLowerCase().replace(/\.jpg$/, ''))
        if (photo) break
        console.log(`  No slot "${answer}". Slots are maya-1 … nia-5.`)
      }
      if (!photo) continue
      if (present.has(photo.file) && !force) {
        const yes = await ask(`  ${photo.file} already has a photo. Replace it? [y/N] `)
        if (yes === null) return
        if (!/^y(es)?$/i.test(yes)) continue
      }
      if (photo.beforeOfNext) console.log('  A before photo: keep it only if the after slot shows the same client.')
      await copyFile(join(folder, name), join(OUT, photo.file))
      credits.set(photo.file, { source: 'pexels', file: photo.file, original: name, ...credit, width: size.width, height: size.height })
      await writeCredits(credits)
      imported++
      console.log(`  Saved as ${photo.file}.`)
    }
  } finally {
    rl.close()
    console.log(`\n${imported} imported.`)
  }
}

// --- main -----------------------------------------------------------------------------------------------------------

const args = process.argv.slice(2)
const force = args.includes('--force')
const [command, folder] = args.filter((a) => a !== '--force')

await mkdir(OUT, { recursive: true })

if (command === 'import') {
  if (!folder) {
    console.error('Usage: npm run demo:photos -- import <folder with the photos you downloaded> [--force]')
    process.exit(1)
  }
  // npm runs scripts from web/; INIT_CWD is where the command was typed, so a relative folder means what Zach expects.
  const dir = resolve(process.env.INIT_CWD ?? process.cwd(), folder)
  if (!(await exists(dir)) || !(await stat(dir)).isDirectory()) {
    console.error(`Not a folder: ${dir}`)
    process.exit(1)
  }
  if (dir === resolve(OUT)) {
    console.error('Download the photos into another folder (Downloads is fine), then import them from there.')
    process.exit(1)
  }
  await importFolder(dir, force)
} else if (command) {
  console.error(`Unknown command "${command}". Run npm run demo:photos, or npm run demo:photos -- import <folder>.`)
  process.exit(1)
}

const present = await presentFiles()
await writeSheet(await readCredits(), present)
console.log(`${present.size} of ${slots.length} slots filled.`)
console.log('By stylist:   ' + DEMO_STYLISTS.map((s) => `${s.slug.split('-')[0]} ${s.photos.filter((p) => present.has(p.file)).length}/${s.photos.length}`).join(', '))
console.log(
  'By hair type: ' +
    ['STRAIGHT', 'WAVY', 'CURLY', 'COILY']
      .map((t) => {
        const of = slots.filter((p) => p.hairType === t)
        return `${t.toLowerCase()} ${of.filter((p) => present.has(p.file)).length}/${of.length}`
      })
      .join(', '),
)
console.log(`Contact sheet: ${SHEET}`)
