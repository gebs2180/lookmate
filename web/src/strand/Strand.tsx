import { useEffect, useRef, useState } from 'react'
import { useLoopRunning, useReducedMotion, useSeenOnce } from './motion'
import { STRAND_SHAPES, shapeNumbers, toPath, type StrandShape } from './shapes'

export type { StrandShape } from './shapes'

/**
 * The Strand: Combpatible's signature, a single hair that draws itself root to tip and morphs between the four hair
 * types. Every shape is built from the same number of curve segments, so any two can be blended number by number
 * (docs/ux/redesign-options.md, "Strand"). The blend runs in JavaScript because Safari can't transition an SVG `d`.
 */
const MORPH_MS = 900
const HOLD_MS = 1700
const ease = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2)

export type StrandProps = {
  /** The shape to show; with `cycle`, where the cycle starts. */
  shape?: StrandShape
  /** Morph through all four shapes in a loop (only on screen, and never with reduced motion). */
  cycle?: boolean
  /** Called with each shape the cycle reaches, e.g. to caption it. */
  onShape?: (shape: StrandShape) => void
  width?: number
  height?: number
  /** How far the strand bends, relative to the full-size landing strand. */
  bend?: number
  strokeWidth?: number
  /** Lie across instead of hanging down. */
  horizontal?: boolean
  /** How the line appears: drawn once root to tip, drawn and withdrawn on a loop (loading), or already there. */
  draw?: 'once' | 'loop' | 'none'
  /** Change it to draw the strand again. */
  drawKey?: string | number
  className?: string
  /** Spoken name, for a strand that means something on its own. Without one the strand is decoration. */
  label?: string
}

export function Strand({
  shape = 'STRAIGHT',
  cycle = false,
  onShape,
  width = 120,
  height = 300,
  bend = 1,
  strokeWidth = 3,
  horizontal = false,
  draw = 'once',
  drawKey,
  className,
  label,
}: StrandProps) {
  const svgRef = useRef<SVGSVGElement>(null)
  const pathRef = useRef<SVGPathElement>(null)
  const running = useLoopRunning(svgRef)
  const seen = useSeenOnce(svgRef)
  const reduce = useReducedMotion()
  const [target, setTarget] = useState<StrandShape>(shape)
  const shown = useRef<number[] | null>(null)
  const onShapeRef = useRef(onShape)
  onShapeRef.current = onShape

  // Lying across: build it hanging down in a swapped box, then mirror it across the diagonal.
  const along = horizontal ? width : height
  const across = horizontal ? height : width
  const pad = Math.max(strokeWidth * 2, along * 0.05)
  const numbers = (s: StrandShape) => shapeNumbers(s, across / 2, pad, along - pad * 2, bend)

  // Not cycling: show the shape asked for (and when a cycle stops, settle on it).
  useEffect(() => {
    if (!cycle) setTarget(shape)
  }, [shape, cycle])

  // The cycle: hold a shape, morph to the next, round and round, only while it can be seen.
  useEffect(() => {
    if (!cycle || !running) return
    const timer = window.setInterval(() => {
      setTarget((t) => STRAND_SHAPES[(STRAND_SHAPES.indexOf(t) + 1) % STRAND_SHAPES.length])
    }, MORPH_MS + HOLD_MS)
    return () => window.clearInterval(timer)
  }, [cycle, running])

  useEffect(() => onShapeRef.current?.(target), [target])

  // The morph: blend from what's on screen to the target, writing `d` straight to the element (no re-render a frame).
  useEffect(() => {
    const path = pathRef.current
    const to = numbers(target)
    const from = shown.current
    if (!path) return
    if (!from || reduce || from.length !== to.length) {
      shown.current = to
      path.setAttribute('d', toPath(to))
      return
    }
    let frame = 0
    const start = performance.now()
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / MORPH_MS)
      const e = ease(t)
      const blend = from.map((v, i) => v + (to[i] - v) * e)
      shown.current = blend
      path.setAttribute('d', toPath(blend))
      if (t < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
    // numbers() depends only on the size props, which a mounted strand keeps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target, reduce])

  const initial = toPath(numbers(target))
  const classes = ['strand', `strand-draw-${draw}`, draw === 'loop' && !running ? 'is-paused' : '', draw === 'once' && !seen ? 'is-waiting' : '', className].filter(Boolean).join(' ')
  return (
    <svg
      ref={svgRef}
      className={classes}
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      focusable="false"
    >
      <g transform={horizontal ? 'matrix(0 1 1 0 0 0)' : undefined}>
        <path key={drawKey} ref={pathRef} d={shown.current ? toPath(shown.current) : initial} pathLength={1} strokeWidth={strokeWidth} />
      </g>
    </svg>
  )
}

/** The brand mark: one curl, drawn in when the page opens. The same path is the favicon. */
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 14 24" width="14" height="24" aria-hidden="true" focusable="false">
      <path d="M7 2c6 0 6 5 0 5s-6 5 0 5 6 5 0 5-6 5 0 5" pathLength={1} />
    </svg>
  )
}
