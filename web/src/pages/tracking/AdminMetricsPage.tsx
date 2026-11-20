import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useApi, type Metrics } from '../../api'
import { label, percent } from '../../format'
import { Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import { metricsPath, type Filters } from './metricsPath'
import { WeeklyLine } from './WeeklyLine'
import './tracking.css'

/** The pilot table from the brief, with a weekly line and the per-stylist and per-hair-type breakdowns. */
export function AdminMetricsPage() {
  const api = useApi()
  const [applied, setApplied] = useState<Filters>({ market: '', from: '', to: '' })
  const [draft, setDraft] = useState<Filters>(applied)
  const { data, error } = useLoad(() => api<Metrics>('GET', metricsPath(applied)), [api, applied])

  function apply(e: FormEvent) {
    e.preventDefault()
    setApplied(draft)
  }

  if (error) return <PageError message={error} />

  return (
    <>
      <h1>Pilot metrics</h1>
      <form className="metric-filters" onSubmit={apply} aria-label="Filters">
        <div className="field">
          <label htmlFor="m-market" className="field-label">Market</label>
          <select id="m-market" value={draft.market} onChange={(e) => setDraft({ ...draft, market: e.target.value })}>
            <option value="">All markets</option>
            {(data?.markets ?? []).map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="m-from" className="field-label">From</label>
          <input id="m-from" type="date" value={draft.from || (data?.from ?? '')} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </div>
        <div className="field">
          <label htmlFor="m-to" className="field-label">To</label>
          <input id="m-to" type="date" value={draft.to || (data?.to ?? '')} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </div>
        <button type="submit">Apply</button>
        <Link to="/admin/bookings">Record a booking</Link>
      </form>

      {!data ? <Loading /> : <MetricsView data={data} />}
    </>
  )
}

function MetricsView({ data }: { data: Metrics }) {
  const t = data.totals
  const rate = t.rebookRate == null ? '–' : percent(t.rebookRate)
  return (
    <>
      <p className="muted">
        {data.from} to {data.to} ({data.timeZone}){data.market ? `, ${data.market}` : ', all markets'}.
      </p>
      <ul className="metric-tiles" aria-label="Totals">
        <Tile value={t.stylistsActive} label="Stylists active" note={`LIVE with a ready photo, now. ${t.stylistsLive} LIVE. Pilot target 20+.`} />
        <Tile value={t.clientsMatched} label="Clients matched" note={`${t.requestsPublished} match lists published`} />
        <Tile value={t.bookingClicks} label="Booking clicks" note={`${t.clientsClicked} clients`} />
        <Tile value={t.clientsBooked} label="Clients who booked" note={`${t.bookings} confirmed bookings. Pilot target 100+.`} />
        <Tile value={t.rebooks} label="Rebooks" note={`${t.clientsRebooked} clients`} />
        <Tile
          value={rate}
          label="Rebook rate"
          note={`${t.rebookCohortRebooked} of ${t.rebookCohort} clients who booked here rebooked with the same stylist. Target 50%+.`}
        />
      </ul>

      <section className="metric-section" aria-labelledby="metric-source-title">
        <h2 id="metric-source-title">Recommended or picked from the directory</h2>
        <p className="muted">
          Clients book from their recommendations or pick any stylist from the directory. A directory pick of a stylist who
          was also on that client's list is counted apart.
        </p>
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Where the client picked the stylist</th>
                <th scope="col" className="num">Booking clicks</th>
                <th scope="col" className="num">Bookings</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">From their recommendations</th>
                <td className="num">{t.clicksBySource.recommended}</td>
                <td className="num">{t.bookingsBySource.recommended}</td>
              </tr>
              <tr>
                <th scope="row">From the directory</th>
                <td className="num">{t.clicksBySource.directory}</td>
                <td className="num">{t.bookingsBySource.directory}</td>
              </tr>
              <tr>
                <th scope="row">
                  <span className="muted">of which, a stylist also on their list</span>
                </th>
                <td className="num">{t.clicksBySource.directoryAlsoRecommended}</td>
                <td className="num">{t.bookingsBySource.directoryAlsoRecommended}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section className="metric-section">
        <WeeklyLine weeks={data.weeks} />
      </section>

      <section className="metric-section">
        <h2>By stylist</h2>
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Stylist</th>
                <th scope="col">Status</th>
                <th scope="col" className="num">Ready photos</th>
                <th scope="col" className="num">Shown</th>
                <th scope="col" className="num">Clicks</th>
                <th scope="col" className="num">Bookings</th>
                <th scope="col" className="num">Rebooks</th>
              </tr>
            </thead>
            <tbody>
              {data.stylists.map((s) => (
                <tr key={s.orgId}>
                  <th scope="row">{s.displayName}</th>
                  <td>{label(s.status)}</td>
                  <td className="num">{s.readyPhotos}</td>
                  <td className="num">{s.matchesShown}</td>
                  <td className="num">{s.bookingClicks}</td>
                  <td className="num">{s.bookings}</td>
                  <td className="num">{s.rebooks}</td>
                </tr>
              ))}
              {data.stylists.length === 0 && (
                <tr>
                  <td colSpan={7}>No stylists yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="metric-section">
        <h2>By hair type</h2>
        <p className="muted">Clients whose matches were published in this range, and how many went on to click and to book.</p>
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Hair type</th>
                <th scope="col" className="num">Matched</th>
                <th scope="col" className="num">Clicked</th>
                <th scope="col" className="num">Booked</th>
                <th scope="col" className="num">Booked share</th>
              </tr>
            </thead>
            <tbody>
              {data.hairTypes.map((h) => (
                <tr key={h.hairType}>
                  <th scope="row">{label(h.hairType)}</th>
                  <td className="num">{h.clientsMatched}</td>
                  <td className="num">{h.clientsClicked}</td>
                  <td className="num">{h.clientsBooked}</td>
                  <td className="num">{h.clientsMatched ? percent(h.clientsBooked / h.clientsMatched) : '–'}</td>
                </tr>
              ))}
              {data.hairTypes.length === 0 && (
                <tr>
                  <td colSpan={5}>No clients matched in this range.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </>
  )
}

function Tile({ value, label: name, note }: { value: number | string; label: string; note: string }) {
  return (
    <li>
      <span className="metric-label">{name}</span>
      <span className="metric-value">{value}</span>
      <span className="metric-note">{note}</span>
    </li>
  )
}
