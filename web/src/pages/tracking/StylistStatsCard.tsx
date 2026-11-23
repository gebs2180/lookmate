import { useParams } from 'react-router'
import { apiPath, type StylistStats } from '../../api'
import { useApi } from '../../useApi'
import { Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './tracking.css'

/** A stylist's own numbers: how often Combpatible showed them, and the clicks and bookings that followed. */
export function StylistStatsCard({ orgId }: { orgId: string }) {
  const api = useApi()
  const { data, error } = useLoad(() => api<StylistStats>('GET', apiPath`/api/orgs/${orgId}/tracking/stats`), [api, orgId])

  if (error) return <p className="error" role="alert">{error}</p>
  if (!data) return <Loading />

  const rows = [
    { name: 'Shown to clients', recent: data.last30Days.matchesShown, all: data.allTime.matchesShown },
    { name: 'Booking clicks', recent: data.last30Days.bookingClicks, all: data.allTime.bookingClicks },
    { name: 'Bookings', recent: data.last30Days.bookings, all: data.allTime.bookings },
    { name: 'Rebooks', recent: data.last30Days.rebooks, all: data.allTime.rebooks },
  ]
  return (
    <section className="panel" aria-labelledby="stylist-stats-title">
      <h2 id="stylist-stats-title">Your Combpatible numbers</h2>
      <table className="table">
        <thead>
          <tr>
            <th scope="col">
              <span className="tracking-sr-only">Measure</span>
            </th>
            <th scope="col" className="num">Last 30 days</th>
            <th scope="col" className="num">All time</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.name}>
              <th scope="row">{r.name}</th>
              <td className="num">{r.recent}</td>
              <td className="num">{r.all}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">Bookings count once you or Combpatible record them.</p>
    </section>
  )
}

/** The card as a page, at /orgs/:orgId/stats, until the stylist home page includes it. */
export function StylistStatsPage() {
  const { orgId = '' } = useParams()
  if (!orgId) return <PageError message="No stylist org in the link." />
  return (
    <>
      <h1>Your stats</h1>
      <StylistStatsCard orgId={orgId} />
    </>
  )
}
