export type Filters = { market: string; from: string; to: string }

export function metricsPath(f: Filters) {
  const q = new URLSearchParams()
  if (f.market) q.set('market', f.market)
  if (f.from) q.set('from', f.from)
  if (f.to) q.set('to', f.to)
  const s = q.toString()
  return `/api/admin/metrics${s ? `?${s}` : ''}`
}
