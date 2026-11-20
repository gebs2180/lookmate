import type { MetricsWeek } from '../../api'

/** The weekly series on the dashboard: counts of the same kind, so they share one axis. Fixed colour slots 1-3. */
export const SERIES = [
  { key: 'bookingClicks', label: 'Booking clicks', slot: 1 },
  { key: 'bookings', label: 'Bookings', slot: 2 },
  { key: 'rebooks', label: 'Rebooks', slot: 3 },
] as const

type SeriesKey = (typeof SERIES)[number]['key']

export const W = 640
export const H = 220
export const M = { top: 12, right: 96, bottom: 28, left: 36 }

/** A "nice" axis top and its ticks: 0 and three or four round steps at or above the largest value. */
export function axis(max: number): { top: number; ticks: number[] } {
  if (max <= 0) return { top: 4, ticks: [0, 1, 2, 3, 4] }
  const rough = max / 4
  const pow = 10 ** Math.floor(Math.log10(rough))
  const step = [1, 2, 5, 10].map((f) => f * pow).find((s) => s >= rough) ?? 10 * pow
  const top = Math.ceil(max / step) * step
  const ticks: number[] = []
  for (let t = 0; t <= top + 1e-9; t += step) ticks.push(Math.round(t * 1e6) / 1e6)
  return { top, ticks }
}

/** Points for one series in chart coordinates. */
export function points(weeks: MetricsWeek[], key: SeriesKey, top: number) {
  const plotW = W - M.left - M.right
  const plotH = H - M.top - M.bottom
  const x = (i: number) => M.left + (weeks.length <= 1 ? plotW / 2 : (i * plotW) / (weeks.length - 1))
  return weeks.map((w, i) => ({ x: x(i), y: M.top + plotH - (w[key] / top) * plotH }))
}
