/**
 * The Strand's four shapes as numbers. Every shape is built from the same number of curve segments, so any two can be
 * blended number by number (docs/ux/redesign-options.md, "Strand").
 */
export type StrandShape = 'STRAIGHT' | 'WAVY' | 'CURLY' | 'COILY'

export const STRAND_SHAPES: StrandShape[] = ['STRAIGHT', 'WAVY', 'CURLY', 'COILY']

const SEGMENTS = 12

/** A wave: each segment bows out `amp` to one side, then the other. Zero amplitude is straight. */
function wave(x0: number, y0: number, len: number, amp: number): number[] {
  const h = len / SEGMENTS
  const n = [x0, y0]
  for (let i = 0; i < SEGMENTS; i++) {
    const s = i % 2 ? -1 : 1
    const y = y0 + h * i
    n.push(x0 + s * amp, y + h * 0.25, x0 + s * amp, y + h * 0.75, x0, y + h)
  }
  return n
}

/** A coil: control points that overshoot each segment, so the line loops back on itself. Smaller radius, tighter coil. */
function coil(x0: number, y0: number, len: number, r: number): number[] {
  const h = len / SEGMENTS
  const n = [x0, y0]
  for (let i = 0; i < SEGMENTS; i++) {
    const s = i % 2 ? -1 : 1
    const y = y0 + h * i
    n.push(x0 + s * r * 1.6, y - h * 0.6, x0 + s * r * 1.6, y + h * 1.6, x0, y + h)
  }
  return n
}

/** The numbers of one shape, for a strand hanging from (x, y), `len` long; `k` scales how far it bends. */
export function shapeNumbers(shape: StrandShape, x: number, y: number, len: number, k = 1): number[] {
  switch (shape) {
    case 'STRAIGHT':
      return wave(x, y, len, 0)
    case 'WAVY':
      return wave(x, y, len, 16 * k)
    case 'CURLY':
      return coil(x, y, len, 14 * k)
    case 'COILY':
      return coil(x, y, len, 9 * k)
  }
}

export function toPath(n: number[]): string {
  const r = (v: number) => Math.round(v * 100) / 100
  let d = `M${r(n[0])} ${r(n[1])}`
  for (let i = 2; i < n.length; i += 6) {
    d += ` C${r(n[i])} ${r(n[i + 1])} ${r(n[i + 2])} ${r(n[i + 3])} ${r(n[i + 4])} ${r(n[i + 5])}`
  }
  return d
}
