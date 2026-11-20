import { useState } from 'react'
import { Link } from 'react-router'
import { useApi, type FairnessReport } from '../../api'
import { label, percent } from '../../format'
import { EmptyState, Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './matching.css'

const PERIODS = [30, 90, 365]

/**
 * Matching quality by the client's hair type (CLAUDE.md: measure fairness across hair types). A type with many
 * requests that find no stylists, or that the reviewer keeps changing, is one the ranker serves poorly.
 */
export function FairnessPage() {
  const api = useApi()
  const [days, setDays] = useState(90)
  const { data: report, error } = useLoad(
    () => api<FairnessReport>('GET', `/api/admin/match-reports/fairness?days=${days}`),
    [api, days],
  )

  return (
    <div className="review-page">
      <p className="back">
        <Link to="/admin/matches">Matches</Link>
      </p>
      <h1>Fairness by hair type</h1>
      <p>
        Watch for a hair type with a high share of requests with no stylists found, a low share of clients who click
        "Book", or lists the admin had to change. Clicks and bookings fill in as tracking records them.
      </p>
      <div className="actions" role="group" aria-label="Period">
        {PERIODS.map((d) => (
          <button key={d} className={d === days ? undefined : 'secondary'} aria-pressed={d === days} onClick={() => setDays(d)}>
            Last {d} days
          </button>
        ))}
      </div>
      {error && <PageError message={error} />}
      {!error && !report && <Loading />}
      {report && report.rows.length === 0 && <EmptyState title="No requests in this period." />}
      {report && report.rows.length > 0 && <FairnessTable report={report} />}
    </div>
  )
}

/** Requests and outcomes by the client's hair type: the fairness check (CLAUDE.md). Also on the matches monitor. */
export function FairnessTable({ report }: { report: FairnessReport }) {
  return (
        <div className="scroll">
          <table className="table">
            <caption className="sr-only">Match requests by client hair type, {report.market}</caption>
            <thead>
              <tr>
                <th scope="col">Hair type</th>
                <th scope="col">Requests</th>
                <th scope="col">No stylists found</th>
                <th scope="col">Admin changed</th>
                <th scope="col">Published</th>
                <th scope="col">Stylists shown</th>
                <th scope="col">Clicked book</th>
                <th scope="col">Booked</th>
                <th scope="col">Mean top score</th>
              </tr>
            </thead>
            <tbody>
              {report.rows.map((r) => (
                <tr key={r.hairType}>
                  <th scope="row">{label(r.hairType)}</th>
                  <td>{r.requests}</td>
                  <td>{share(r.noSystemMatches, r.reviewed)}</td>
                  <td>{share(r.withAdminChanges, r.reviewed)}</td>
                  <td>{r.published}</td>
                  <td>{r.shownMatches}</td>
                  <td>{share(r.clickedRequests, r.published)}</td>
                  <td>{share(r.bookedRequests, r.published)}</td>
                  <td>{r.meanTopScore == null ? '–' : r.meanTopScore.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
  )
}

function share(n: number, of: number) {
  return of === 0 ? `${n}` : `${n} (${percent(n / of)})`
}
