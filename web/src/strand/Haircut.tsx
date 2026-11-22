import { useEffect, useRef } from 'react'
import { useLoopRunning, useReducedMotion } from './motion'
import type { StrandShape } from './shapes'

/**
 * The matching moment: a client in the chair, caped, while scissors work at the ends of hair in their own hair type and
 * clippings fall onto the cape. The snipping loops for as long as we look through portfolios, and stands for the
 * looking, never for how far along it is. When the matches arrive (`done`), the scissors take one sweep across at chin
 * height, the cut ends fall, the hair springs back as its type does, and the cape comes off: then `onDone` reveals the
 * picks. Decoration only (the stage list and status line say it in words). Fast matches still get the whole show: the
 * snipping runs at least MIN_LOOP_S before the finish starts, so the client sees about 4.6s in all (Zach, 2026-11-22).
 *
 * The finish runs only when the client can see it. Off screen, in a hidden tab or with reduced motion, `onDone` comes
 * at once, so the picks are never held back for an animation nobody watches. Drawn by hand each frame (no React state
 * per frame); it was designed as a prototype first (2026-11-22).
 */
export function Haircut({ hairType, done, onDone }: { hairType: StrandShape | null; done: boolean; onDone: () => void }) {
  const wrap = useRef<HTMLDivElement>(null)
  const svg = useRef<SVGSVGElement>(null)
  const running = useLoopRunning(wrap)
  const reduce = useReducedMotion()
  const scene = useRef<Scene | null>(null)
  const doneRef = useRef(onDone)
  useEffect(() => {
    doneRef.current = onDone
  })

  useEffect(() => {
    if (!svg.current) return
    scene.current = buildScene(svg.current, TEXTURES[hairType ?? 'WAVY'] ?? TEXTURES.WAVY)
    scene.current.draw(0)
    return () => {
      scene.current = null
    }
  }, [hairType])

  useEffect(() => {
    const s = scene.current
    if (!s) return
    if (done && (!running || reduce)) {
      // Nobody is watching the finish: show the cut and move straight on. Just mounted, it can't yet tell whether it's
      // on screen (IntersectionObserver answers a moment later), so it waits that moment unless motion is reduced.
      const finishNow = () => {
        s.finishNow()
        doneRef.current()
      }
      if (reduce) {
        finishNow()
        return
      }
      const wait = setTimeout(finishNow, UNSEEN_GRACE_MS)
      return () => clearTimeout(wait)
    }
    if (!running) {
      s.draw(0)
      return
    }
    if (done) s.startFinish()
    let frame = 0
    let last = performance.now()
    const step = (now: number) => {
      const dt = Math.min(0.05, (now - last) / 1000)
      last = now
      if (s.draw(dt)) {
        doneRef.current()
        return
      }
      frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [done, running, reduce, hairType])

  return (
    <div ref={wrap} className="haircut" aria-hidden="true">
      <svg ref={svg} viewBox="0 0 360 290" />
    </div>
  )
}

type Texture = {
  /** How far the strand swings from its line, in px. */
  amp: number
  /** One wave or loop, in px along the strand. */
  wavelength: number
  /** Curls loop back on themselves; straight and wavy hair only bends. */
  loops: boolean
  /** Lift at the crown. */
  volume: number
  /** Opacity of the filled hair shape. */
  fill: number
  /** How much a cut strand springs back. */
  recoil: number
}

const TEXTURES: Record<StrandShape, Texture> = {
  STRAIGHT: { amp: 0, wavelength: 1, loops: false, volume: 7, fill: 0.22, recoil: 0.02 },
  WAVY: { amp: 4.5, wavelength: 28, loops: false, volume: 10, fill: 0.22, recoil: 0.04 },
  CURLY: { amp: 5, wavelength: 13, loops: true, volume: 15, fill: 0.28, recoil: 0.12 },
  COILY: { amp: 3.2, wavelength: 6.5, loops: true, volume: 20, fill: 0.3, recoil: 0.12 },
}

type Pt = [number, number]
type Scene = { draw: (dt: number) => boolean; startFinish: () => void; finishNow: () => void }
type Strand = { side: 1 | -1; u: number; phase: number; cut: boolean; cutAt: number }
type Clipping = { el: SVGGElement; x: number; y: number; vx: number; vy: number; r: number; vr: number; land: number; landed: boolean; life: number; off: boolean }

const NS = 'http://www.w3.org/2000/svg'
const HEAD = { x: 180, y: 118, rx: 33, ry: 41 }
const PER_SIDE = 12
/** Where the hair ends while it's long, and the chin-length line the finish cuts it to. */
const LONG_END = 250
const CUT_LINE = 166
/** The finish, in seconds after the matches arrive. */
/** How long a just-mounted haircut waits to learn it's on screen before giving up on the finish. */
const UNSEEN_GRACE_MS = 300
/** Seconds of snipping the client sees before the finish may start, however fast the matches arrive. */
const MIN_LOOP_S = 3
const FINISH = { moveIn: 0.2, sweepEnd: 1, capeFrom: 1.05, capeFor: 0.6, end: 1.65 }

const ease = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2)
const clamp01 = (x: number) => Math.max(0, Math.min(1, x))
const lerp = (a: number, b: number, u: number) => a + (b - a) * u

function el<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string | number>, parent: Element) {
  const e = document.createElementNS(NS, tag)
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, String(v))
  parent.appendChild(e)
  return e
}

/** A strand's path over the crown: from the parting to where it leaves the head. */
function crownOf(t: Texture, s: Strand) {
  const { u, side } = s
  const v = t.volume
  const p: Pt = [172, 78 - v * (1 - u) * 0.5]
  const e: Pt = [HEAD.x + side * (HEAD.rx + 3 + (1 - u) * v * 0.9), 106 + u * 10]
  const c1: Pt = [lerp(p[0] + side * 14, p[0] + side * 10, u), lerp(p[1] - v * 0.9 - 3, 90, u)]
  const c2: Pt = [lerp(e[0] + side * v * 0.25, e[0] - side * 6, u), lerp(e[1] - 30, 100, u)]
  const pts: Pt[] = []
  for (let i = 0; i <= 22; i++) {
    const q = i / 22
    const m = 1 - q
    const at = (k: 0 | 1) => m * m * m * p[k] + 3 * m * m * q * c1[k] + 3 * m * q * q * c2[k] + q * q * q * e[k]
    pts.push([at(0), at(1)])
  }
  return { exit: e, pts }
}

/** How far out a hanging strand sits `d` px below where it leaves the head, the ends tucking under. */
const hangX = (exit: Pt, side: number, d: number, length: number) =>
  exit[0] + side * (0.14 * d + 5 * (1 - Math.exp(-d / 14)) - 7 * (d / Math.max(length, 1)) ** 4)

function hang(exit: Pt, side: number, length: number, sway: number): Pt[] {
  const n = Math.max(4, Math.ceil(length / 1.3))
  const pts: Pt[] = []
  for (let i = 1; i <= n; i++) {
    const d = (length * i) / n
    const w = d / Math.max(length, 1)
    pts.push([hangX(exit, side, d, length) + sway * w * w, exit[1] + d * 0.97])
  }
  return pts
}

function pathLength(pts: Pt[]) {
  let s = 0
  for (let i = 1; i < pts.length; i++) s += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
  return s
}

/** Gives a line the hair type's texture: a wave across it, and for curls and coils a loop back along it too. */
function textured(pts: Pt[], t: Texture, ampAt: (s: number) => number, phase: number) {
  let s = 0
  const out: string[] = []
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i]
    if (i) s += Math.hypot(p[0] - pts[i - 1][0], p[1] - pts[i - 1][1])
    const a = pts[Math.max(0, i - 1)]
    const b = pts[Math.min(pts.length - 1, i + 1)]
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1
    const tx = (b[0] - a[0]) / len
    const ty = (b[1] - a[1]) / len
    const amp = t.amp * ampAt(s)
    const th = (2 * Math.PI * s) / t.wavelength + phase
    const across = amp * Math.sin(th)
    const along = t.loops ? amp * (Math.cos(th) - 1) : 0
    out.push(`${(p[0] - ty * across + tx * along).toFixed(1)} ${(p[1] + tx * across + ty * along).toFixed(1)}`)
  }
  return 'M' + out.join('L')
}

function buildScene(svg: SVGSVGElement, tex: Texture): Scene {
  svg.replaceChildren()
  el('path', { class: 'hc-top', d: 'M164 183 C151 188 132 191 121 201 C112 211 108 246 107 292 L253 292 C252 246 248 211 239 201 C228 191 209 188 196 183 Q180 195 164 183 Z' }, svg)
  el('path', { class: 'hc-line', d: 'M160 186 Q180 202 200 186 M124 240 C125 258 126 276 126 292 M236 240 C235 258 234 276 234 292' }, svg)
  el('path', { class: 'hc-neck', d: 'M169 150 C169 163 168 173 165 183 M191 150 C191 163 192 173 195 183' }, svg)
  const cape = el('g', {}, svg)
  el('path', { class: 'hc-cape', d: 'M58 298 C74 224 128 194 166 186 Q180 196 194 186 C232 194 286 224 302 298 Z' }, cape)
  el('path', { class: 'hc-fold', d: 'M124 298 C130 258 142 228 152 210 M236 298 C230 258 218 228 208 210 M180 204 V298' }, cape)
  const clipLayer = el('g', { class: 'hc-hair' }, svg)
  el('ellipse', { class: 'hc-head', cx: HEAD.x, cy: HEAD.y, rx: HEAD.rx, ry: HEAD.ry }, svg)
  el('path', { class: 'hc-face', d: 'M162 122 q6 4 12 0 M186 122 q6 4 12 0 M181 127 q-4 9 0 12 M174 146 q6 3 12 0' }, svg)
  const mass = el('path', { class: 'hc-mass', 'fill-opacity': tex.fill }, svg)
  const hairLayer = el('g', { class: 'hc-hair' }, svg)
  const strands: Strand[] = []
  for (const side of [-1, 1] as const)
    for (let k = 0; k < PER_SIDE; k++) strands.push({ side, u: k / (PER_SIDE - 1), phase: k * 1.7 + (side > 0 ? 0.9 : 0), cut: false, cutAt: 0 })
  const paths = strands.map(() => el('path', {}, hairLayer))

  const scissors = el('g', {}, svg)
  const blades = [el('g', {}, scissors), el('g', {}, scissors)]
  const lower = el('g', { transform: 'scale(1 -1)' }, blades[1])
  for (const half of [blades[0], lower]) {
    el('path', { class: 'hc-blade', d: 'M3 -1.6 L-29 .2 L3 2.4 Z' }, half)
    el('path', { class: 'hc-shank', d: 'M3 .4 L10 6' }, half)
    el('circle', { class: 'hc-ring', cx: 14, cy: 9, r: 5 }, half)
  }
  el('circle', { class: 'hc-pivot', cx: 0, cy: 0.4, r: 1.7 }, scissors)

  let t = 0
  let finish = -1
  let wantFinish = false
  let snapped = false
  let from: Pt = [180, 242]
  let at: Pt = [180, 242]
  let clips: Clipping[] = []

  function placeScissors(x: number, y: number, rot: number, open: number, alpha: number) {
    scissors.setAttribute('transform', `translate(${x.toFixed(1)} ${y.toFixed(1)}) rotate(${rot})`)
    blades[0].setAttribute('transform', `rotate(${(-open).toFixed(1)})`)
    blades[1].setAttribute('transform', `rotate(${open.toFixed(1)})`)
    scissors.setAttribute('opacity', alpha.toFixed(2))
    at = [x, y]
  }

  function drop(x: number, y: number, length: number, side: number, vx: number, land: number) {
    const g = el('g', {}, clipLayer)
    const n = Math.max(4, Math.ceil(length / 1.3))
    const pts: Pt[] = []
    for (let i = 0; i <= n; i++) pts.push([side * 0.14 * ((length * i) / n), (length * i * 0.97) / n])
    el('path', { d: textured(pts, tex, () => 1, Math.random() * 6) }, g)
    clips.push({ el: g, x, y, vx, vy: -20, r: 0, vr: (Math.random() - 0.5) * 4, land, landed: false, life: 1, off: false })
  }

  function draw(dt: number): boolean {
    t += dt
    if (finish >= 0) finish += dt
    else if (wantFinish && t >= MIN_LOOP_S) {
      finish = 0
      from = at
    }
    const f = finish
    if (f < 0) {
      // Snipping at the ends, sweeping side to side; each snip drops a clipping on the cape.
      const x = 180 + 96 * Math.sin(t * 0.8)
      const y = 242 + 5 * Math.sin(t * 1.6)
      const right = x > 180
      const c = Math.cos(t * 10)
      const snipping = Math.abs(x - 180) > 64
      placeScissors(x, y, right ? 0 : 180, snipping ? 7 + 7 * c : 13, 1)
      if (snipping && c < -0.95 && !snapped && dt > 0) {
        snapped = true
        drop(right ? x - 28 : x + 28, y, 5 + Math.random() * 7, right ? -1 : 1, (right ? -1 : 1) * (20 + Math.random() * 40), Math.max(y + 12, 236 + Math.random() * 50))
      }
      if (c > 0) snapped = false
    } else if (f < FINISH.moveIn) {
      const e = ease(f / FINISH.moveIn)
      placeScissors(lerp(from[0], 96, e), lerp(from[1], CUT_LINE, e), 180, 10, 1)
    } else if (f < FINISH.sweepEnd) {
      // One sweep across at chin height.
      placeScissors(96 + (168 * (f - FINISH.moveIn)) / (FINISH.sweepEnd - FINISH.moveIn), CUT_LINE, 180, 7 + 7 * Math.cos((f - FINISH.moveIn) * 34), 1)
    } else {
      const e = ease(clamp01((f - FINISH.sweepEnd) / 0.5))
      placeScissors(264 + 80 * e, CUT_LINE - 140 * e, 180, 5, 1 - e)
    }

    const outer: Partial<Record<number, Pt[]>> = {}
    const front: Partial<Record<number, Pt[]>> = {}
    strands.forEach((s, i) => {
      const { exit, pts } = crownOf(tex, s)
      const crown = pathLength(pts)
      const long = LONG_END - exit[1]
      const cut = CUT_LINE - exit[1]
      let length = long
      if (f >= 0) {
        if (!s.cut && (f >= FINISH.sweepEnd || (f > FINISH.moveIn && hangX(exit, s.side, cut, cut) < at[0] + 28))) {
          s.cut = true
          s.cutAt = f
          if (f < FINISH.end) drop(hangX(exit, s.side, cut, cut), exit[1] + cut * 0.97, long - cut, s.side, s.side * (10 + Math.random() * 25), 236 + Math.random() * 50)
        }
        if (s.cut) {
          const k = f - s.cutAt
          length = cut * (1 - tex.recoil * Math.exp(-k * 4) * Math.cos(k * 14))
        }
      }
      const sway = (f < 0 ? 3 : 3 * Math.max(0, 1 - f * 0.8)) * Math.sin(t * 1.3 + s.phase)
      const full = pts.concat(hang(exit, s.side, length, sway))
      paths[i].setAttribute('d', textured(full, tex, (d) => Math.min(1, d / 10) * (d < crown ? 0.6 : 1), s.phase))
      if (s.u === 0) outer[s.side] = full
      if (s.u === 1) front[s.side] = full
    })
    const join = (pts: Pt[]) => pts.map((p) => `${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join('L')
    mass.setAttribute('d', `M${join([...outer[-1]!].reverse())}L${join(outer[1]!)}L${join([...front[1]!].reverse())}L${join(front[-1]!)}Z`)

    if (f >= 0) {
      const e = ease(clamp01((f - FINISH.capeFrom) / FINISH.capeFor))
      cape.setAttribute('transform', `translate(0 ${(e * 190).toFixed(1)})`)
      cape.setAttribute('opacity', (1 - e).toFixed(2))
    }
    clips = clips.filter((c) => {
      if (f > FINISH.capeFrom && c.landed && !c.off) {
        // The cape comes off and takes the clippings with it.
        Object.assign(c, { off: true, landed: false, land: 420, vy: 0 })
      }
      if (!c.landed) {
        c.vy += 520 * dt
        c.x += c.vx * dt
        c.y += c.vy * dt
        c.r += c.vr * dt
        if (c.y >= c.land) Object.assign(c, { y: c.land, landed: true })
      } else c.life -= dt * 0.35
      c.el.setAttribute('transform', `translate(${c.x.toFixed(1)} ${c.y.toFixed(1)}) rotate(${(c.r * 57.3).toFixed(1)})`)
      c.el.setAttribute('opacity', clamp01(c.life).toFixed(2))
      if (c.life <= 0 || c.y > 400) {
        c.el.remove()
        return false
      }
      return true
    })
    return f >= FINISH.end
  }

  return {
    draw,
    startFinish() {
      wantFinish = true
    },
    finishNow() {
      finish = FINISH.end
      for (const c of clips) c.el.remove()
      clips = []
      strands.forEach((s) => Object.assign(s, { cut: true, cutAt: -10 }))
      draw(0)
    },
  }
}
