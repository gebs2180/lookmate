import { useState } from 'react'
import { Link } from 'react-router'
import { apiPath, type FairnessReport, type MatchMonitor, type MonitorItem, type MonitorMatch } from '../../api'
import { useApi } from '../../useApi'
import { label, message, shortDate } from '../../format'
import { Chip, EmptyState, Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import { FairnessTable } from './FairnessPage'
import { ReviewQueue } from './ReviewQueue'
import './matching.css'

const STATUS_TONE: Record<MonitorItem['status'], 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  NEW: 'neutral',
  MATCHING: 'info',
  IN_REVIEW: 'warning',
  PUBLISHED: 'success',
  CLOSED: 'neutral',
  FAILED: 'danger',
}

/**
 * The admin's matches page. Lists publish to clients as soon as they're ranked (2026-11-20): the admin watches here
 * what each client was shown and did, and steps in after the fact (hide a match on the request's page, or hide a
 * stylist from every result). Fairness by hair type comes first. With review-before-publish on, the queue is back.
 */
export function MatchMonitorPage() {
  const api = useApi()
  const { data: monitor, error, reload } = useLoad(() => api<MatchMonitor>('GET', '/api/admin/match-monitor?limit=50'), [api])
  const { data: fairness, error: fairnessError } = useLoad(
    () => api<FairnessReport>('GET', '/api/admin/match-reports/fairness?days=90'),
    [api],
  )
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function hideStylist(m: MonitorMatch) {
    const name = m.stylistName ?? 'this stylist'
    if (!window.confirm(`Hide ${name} from every client's results and from the directory? Their profile goes to hidden; they can publish it again.`)) return
    setBusy(true)
    setActionError(null)
    try {
      await api('POST', apiPath`/api/admin/stylists/${m.orgId}/hide`)
      setNotice(`${name} is hidden from all results.`)
      reload()
    } catch (e) {
      setActionError(message(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="review-page">
      <h1>Matches</h1>
      <p>
        {monitor?.reviewBeforePublish
          ? 'Review before publish is on: lists wait for you in the queue below.'
          : "Clients see their list as soon as it's ranked. Watch what they were shown and what they did; open a request to hide a match."}
      </p>

      <section className="panel" aria-labelledby="fairness-title">
        <h2 id="fairness-title">Fairness by hair type, last 90 days</h2>
        {fairnessError && <PageError inline message={fairnessError} />}
        {!fairnessError && !fairness && <Loading />}
        {fairness && fairness.rows.length === 0 && <p className="muted">No requests in this period.</p>}
        {fairness && fairness.rows.length > 0 && <FairnessTable report={fairness} />}
        <p>
          <Link to="/admin/matches/fairness">Full fairness report</Link> · <Link to="/admin/metrics">Pilot metrics</Link>
        </p>
      </section>

      {monitor?.reviewBeforePublish && <ReviewQueue />}

      <section aria-labelledby="recent-title">
        <h2 id="recent-title">Recent requests</h2>
        {notice && (
          <p className="alert alert-success" role="status">
            {notice}
          </p>
        )}
        {actionError && (
          <p className="error" role="alert">
            {actionError}
          </p>
        )}
        {error && <PageError inline message={error} />}
        {!error && !monitor && <Loading />}
        {monitor && monitor.requests.length === 0 && <EmptyState title="No requests yet." />}
        {monitor && monitor.requests.length > 0 && (
          <div className="scroll">
            <table className="table monitor">
              <thead>
                <tr>
                  <th scope="col">Requested</th>
                  <th scope="col">Client</th>
                  <th scope="col">Status</th>
                  <th scope="col">Shown to the client (clicks · bookings)</th>
                  <th scope="col">From the directory</th>
                  <th scope="col">
                    <span className="sr-only">Open</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {monitor.requests.map((r) => (
                  <Row key={r.id} item={r} busy={busy} onHideStylist={hideStylist} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}

function Row({ item: r, busy, onHideStylist }: { item: MonitorItem; busy: boolean; onHideStylist: (m: MonitorMatch) => void }) {
  const shown = r.matches.filter((m) => m.finalRank != null).sort((a, b) => a.finalRank! - b.finalRank!)
  const hidden = r.matches.filter((m) => m.hidden)
  return (
    <tr>
      <td>{shortDate(r.createdAt)}</td>
      <td>
        {r.firstName ?? 'Client'}
        <div className="muted small">
          {[r.service, r.hairType].filter(Boolean).map((v) => label(v!)).join(' · ') || '–'}
        </div>
      </td>
      <td>
        <Chip tone={STATUS_TONE[r.status]}>{label(r.status)}</Chip>
        {r.adminChanged && <div className="muted small">changed by admin</div>}
        {r.statusReason && <div className="muted small">{r.statusReason}</div>}
        {r.publishedAt && <div className="muted small">{r.firstViewedAt ? `opened ${shortDate(r.firstViewedAt)}` : 'not opened yet'}</div>}
      </td>
      <td>
        {shown.length === 0 ? (
          <span className="muted">–</span>
        ) : (
          <ol className="monitor-list">
            {shown.map((m) => (
              <li key={m.id}>
                {m.stylistName ?? 'Unknown stylist'}{' '}
                <span className="muted small">
                  {m.bookingClicks} · {m.bookings}
                  {m.rebooks > 0 ? ` (+${m.rebooks} rebook${m.rebooks === 1 ? '' : 's'})` : ''}
                </span>
                {!m.stylistLive && <Chip tone="danger">not live</Chip>}
                {m.stylistLive && (
                  <button className="link small" onClick={() => onHideStylist(m)} disabled={busy}>
                    Hide everywhere
                  </button>
                )}
              </li>
            ))}
          </ol>
        )}
        {hidden.length > 0 && <div className="muted small">{hidden.length} hidden by the admin</div>}
      </td>
      <td>
        {r.directoryClicks} {r.directoryClicks === 1 ? 'click' : 'clicks'} · {r.directoryBookings}{' '}
        {r.directoryBookings === 1 ? 'booking' : 'bookings'}
      </td>
      <td>
        <Link to={`/admin/matches/${r.id}`}>{r.status === 'IN_REVIEW' ? 'Review' : 'Open'}</Link>
      </td>
    </tr>
  )
}
