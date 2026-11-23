import { Link, useSearchParams } from 'react-router'
import { type MatchQueueItem, type MatchRequestStatus } from '../../api'
import { useApi } from '../../useApi'
import { label, shortDate } from '../../format'
import { EmptyState, Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './matching.css'

const TABS: { status: MatchRequestStatus; title: string }[] = [
  { status: 'IN_REVIEW', title: 'To review' },
  { status: 'MATCHING', title: 'Matching' },
  { status: 'FAILED', title: 'Failed' },
  { status: 'PUBLISHED', title: 'Published' },
]

/**
 * The review queue, oldest first: shown on the matches page when review-before-publish is on
 * (lookmate.matching.review-before-publish). "To review" is the work; the other tabs show what's stuck or done.
 */
export function ReviewQueue() {
  const api = useApi()
  const [params] = useSearchParams()
  const status = (TABS.find((t) => t.status === params.get('status'))?.status ?? 'IN_REVIEW') as MatchRequestStatus
  const { data: items, error } = useLoad(
    () => api<MatchQueueItem[]>('GET', `/api/admin/match-requests?status=${status}`),
    [api, status],
  )

  return (
    <section aria-labelledby="queue-title">
      <h2 id="queue-title">Review queue</h2>
      <p>Review before publish is on: each client's list waits here until you publish it.</p>
      <nav className="tabs" aria-label="Request status">
        {TABS.map((t) => (
          <Link key={t.status} to={`/admin/matches?status=${t.status}`} aria-current={t.status === status ? 'page' : undefined}>
            {t.title}
          </Link>
        ))}
      </nav>
      {error && <PageError inline message={error} />}
      {!error && !items && <Loading />}
      {items && items.length === 0 && <EmptyState title="Nothing here right now." />}
      {items && items.length > 0 && (
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Requested</th>
                <th scope="col">Client</th>
                <th scope="col">Service</th>
                <th scope="col">Hair</th>
                <th scope="col">Stylists found</th>
                <th scope="col">
                  <span className="sr-only">Open</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((r) => (
                <tr key={r.id}>
                  <td>{shortDate(r.createdAt)}</td>
                  <td>{r.firstName ?? 'Client'}</td>
                  <td>{r.service ? label(r.service) : '–'}</td>
                  <td>{[r.hairType, r.texture].filter(Boolean).map((v) => label(v!)).join(', ') || '–'}</td>
                  <td>
                    {r.candidates}
                    {r.statusReason && <span className="muted small"> · {r.statusReason}</span>}
                  </td>
                  <td>
                    <Link to={`/admin/matches/${r.id}`}>{r.status === 'IN_REVIEW' ? 'Review' : 'Open'}</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
