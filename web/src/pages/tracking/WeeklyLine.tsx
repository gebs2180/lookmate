import { useState } from 'react'
import type { MetricsWeek } from '../../api'
import { axis, H, M, points, SERIES, W } from './chart'

function weekLabel(iso: string) {
  return new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

/** Direct labels at the line ends, nudged apart so they never overlap. */
function endLabels(ys: number[], gap = 14) {
  const order = ys.map((y, i) => ({ y, i })).sort((a, b) => a.y - b.y)
  for (let k = 1; k < order.length; k++) {
    if (order[k].y - order[k - 1].y < gap) order[k].y = order[k - 1].y + gap
  }
  const out = new Array<number>(ys.length)
  order.forEach((o) => (out[o.i] = o.y))
  return out
}

/** Weekly clicks, bookings and rebooks: one axis, a legend, end labels, a crosshair tooltip, and a table view. */
export function WeeklyLine({ weeks }: { weeks: MetricsWeek[] }) {
  const [hover, setHover] = useState<number | null>(null)
  if (weeks.length === 0) return null

  const max = Math.max(0, ...weeks.flatMap((w) => SERIES.map((s) => w[s.key])))
  const { top, ticks } = axis(max)
  const plotH = H - M.top - M.bottom
  const lines = SERIES.map((s) => ({ ...s, pts: points(weeks, s.key, top) }))
  const labelYs = endLabels(lines.map((l) => l.pts[l.pts.length - 1].y))
  const colW = weeks.length <= 1 ? W - M.left - M.right : (W - M.left - M.right) / (weeks.length - 1)
  const every = Math.max(1, Math.ceil(weeks.length / 8))
  const hovered = hover == null ? null : weeks[hover]

  return (
    <figure className="viz-root weekly">
      <figcaption className="viz-title">Per week (weeks start Monday)</figcaption>
      <ul className="viz-legend" aria-label="Legend">
        {SERIES.map((s) => (
          <li key={s.key}>
            <span className={`viz-swatch s${s.slot}`} aria-hidden="true" />
            {s.label}
          </li>
        ))}
      </ul>
      <div className="viz-plot" onMouseLeave={() => setHover(null)}>
        <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Booking clicks, bookings and rebooks per week. The table below has the numbers.">
          {ticks.map((t) => {
            const y = M.top + plotH - (t / top) * plotH
            return (
              <g key={t}>
                <line className="viz-grid" x1={M.left} x2={W - M.right} y1={y} y2={y} />
                <text className="viz-tick" x={M.left - 6} y={y} textAnchor="end" dominantBaseline="middle">
                  {t}
                </text>
              </g>
            )
          })}
          {weeks.map((w, i) =>
            i % every === 0 ? (
              <text key={w.weekStart} className="viz-tick" x={lines[0].pts[i].x} y={H - 8} textAnchor="middle">
                {weekLabel(w.weekStart)}
              </text>
            ) : null,
          )}
          {hover != null && (
            <line className="viz-crosshair" x1={lines[0].pts[hover].x} x2={lines[0].pts[hover].x} y1={M.top} y2={M.top + plotH} />
          )}
          {lines.map((l) => (
            <polyline key={l.key} className={`viz-line s${l.slot}`} points={l.pts.map((p) => `${p.x},${p.y}`).join(' ')} />
          ))}
          {hover != null &&
            lines.map((l) => <circle key={l.key} className={`viz-dot s${l.slot}`} cx={l.pts[hover].x} cy={l.pts[hover].y} r={4} />)}
          {lines.map((l, i) => (
            <text key={l.key} className="viz-end" x={W - M.right + 8} y={labelYs[i]} dominantBaseline="middle">
              {l.label}
            </text>
          ))}
          {weeks.map((w, i) => (
            <rect
              key={w.weekStart}
              className="viz-hit"
              x={lines[0].pts[i].x - colW / 2}
              y={M.top}
              width={colW}
              height={plotH}
              onMouseEnter={() => setHover(i)}
              onFocus={() => setHover(i)}
              onBlur={() => setHover(null)}
              tabIndex={0}
              aria-label={`Week of ${weekLabel(w.weekStart)}: ${SERIES.map((s) => `${w[s.key]} ${s.label.toLowerCase()}`).join(', ')}`}
            />
          ))}
        </svg>
        {hovered && hover != null && (
          <div
            className="viz-tooltip"
            role="status"
            style={{ left: `${(lines[0].pts[hover].x / W) * 100}%` }}
            data-flip={lines[0].pts[hover].x > W / 2 ? 'left' : 'right'}
          >
            <strong>Week of {weekLabel(hovered.weekStart)}</strong>
            {SERIES.map((s) => (
              <span key={s.key}>
                <span className={`viz-swatch s${s.slot}`} aria-hidden="true" /> {s.label}: {hovered[s.key]}
              </span>
            ))}
          </div>
        )}
      </div>
      <details className="viz-table">
        <summary>Show as a table</summary>
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Week of</th>
                <th scope="col">Matched</th>
                {SERIES.map((s) => (
                  <th key={s.key} scope="col">
                    {s.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {weeks.map((w) => (
                <tr key={w.weekStart}>
                  <td>{weekLabel(w.weekStart)}</td>
                  <td>{w.requestsPublished}</td>
                  {SERIES.map((s) => (
                    <td key={s.key}>{w[s.key]}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  )
}
