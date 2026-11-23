import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, apiPath, type MatchReview, type MatchView, type StylistOption } from '../../api'
import { useApi } from '../../useApi'
import { label, message, shortDate } from '../../format'
import { ASKS, labelOf } from '../public/quiz'
import { Chip, Field, Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './matching.css'

/**
 * One request's review: the client's inspiration photo and answers beside the ranked stylists and the portfolio photos
 * that earned each match. The admin reorders, hides, adds and notes. Matching publishes by itself (the review switch is
 * off), so this is mostly the after-the-fact view; with the switch on, a request waits here in IN_REVIEW to be published.
 */
export function MatchReviewPage() {
  const { id = '' } = useParams()
  const api = useApi()
  const { data, error, reload } = useLoad(() => api<MatchReview>('GET', apiPath`/api/admin/match-requests/${id}`), [api, id])
  const [review, setReview] = useState<MatchReview | null>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [published, setPublished] = useState(false)

  const current = review?.request.id === id ? review : data
  if (error) return <PageError message={error} />
  if (!current) return <Loading />

  const editable = current.request.status === 'IN_REVIEW'
  // After publishing, the admin can still take a match off the client's list (or put it back) and keep notes.
  const hideable = editable || current.request.status === 'PUBLISHED'
  const matches = current.matches
  const visible = matches.filter((m) => !m.hidden).length

  /** Runs one change and shows the result; false (with the error on screen) when it failed. */
  async function act(run: () => Promise<MatchReview>, after?: (result: MatchReview) => void): Promise<boolean> {
    setActionError(null)
    setBusy(true)
    try {
      const result = await run()
      setReview(result)
      after?.(result)
      return true
    } catch (e) {
      setActionError(message(e))
      if (e instanceof ApiError && e.code === 'NOT_IN_REVIEW') reload()
      return false
    } finally {
      setBusy(false)
    }
  }

  function move(index: number, by: number) {
    const ids = matches.map((m) => m.id)
    const [moved] = ids.splice(index, 1)
    ids.splice(index + by, 0, moved)
    return act(() => api<MatchReview>('PUT', apiPath`/api/admin/match-requests/${id}/order`, { matchIds: ids }))
  }

  function change(m: MatchView, hidden: boolean, note: string | null) {
    return act(() => api<MatchReview>('PUT', apiPath`/api/admin/match-requests/${id}/matches/${m.id}`, { hidden, note }))
  }

  async function publish() {
    if (!window.confirm(`Publish ${visible} stylist${visible === 1 ? '' : 's'} to the client? If they gave an email, they'll get a link.`)) return
    return act(() => api<MatchReview>('POST', apiPath`/api/admin/match-requests/${id}/publish`), () => setPublished(true))
  }

  const r = current.request
  // The quiet / honest-advice chips travel in pastIssues but are asks, not complaints. Not used for ranking.
  const asked = r.pastIssues.filter((p) => ASKS.some((a) => a.value === p))
  const wentWrong = r.pastIssues.filter((p) => !asked.includes(p))
  return (
    <div className="review-page">
      <p className="back">
        <Link to="/admin/matches">Matches</Link>
      </p>
      <h1>
        {r.firstName ?? 'Client'}: {r.service ? label(r.service) : 'any service'}
      </h1>
      <p>
        <Chip tone={r.status === 'PUBLISHED' ? 'success' : r.status === 'FAILED' ? 'danger' : 'info'}>{label(r.status)}</Chip>{' '}
        <span className="muted">Requested {shortDate(r.createdAt)}</span>
      </p>
      {r.statusReason && <p className="alert alert-warning">{r.statusReason}</p>}
      {actionError && (
        <p className="error" role="alert">
          {actionError}
        </p>
      )}
      {published && (
        <p className="alert alert-success" role="status">
          Published. If the client gave an email, they're sent a link in a few minutes, unless they open their matches first.
        </p>
      )}

      <div className="review-layout">
        <aside className="panel">
          <h2>The look</h2>
          {current.inspirationUrl ? (
            <img className="inspiration" src={current.inspirationUrl} referrerPolicy="no-referrer" alt="The client's inspiration photo" />
          ) : (
            <p className="muted">The inspiration photo has been deleted (retention).</p>
          )}
          <dl className="facts">
            <dt>Hair</dt>
            <dd>{[r.hairType, r.texture, r.length].filter(Boolean).map((v) => label(v!)).join(', ') || '–'}</dd>
            <dt>Budget</dt>
            <dd>{r.budgetTier ? '$'.repeat(r.budgetTier) : '–'}</dd>
            <dt>Area</dt>
            <dd>{r.neighborhood ?? '–'}</dd>
            {wentWrong.length > 0 && (
              <>
                <dt>Went wrong before</dt>
                <dd>{wentWrong.map(label).join(', ')}</dd>
              </>
            )}
            {asked.length > 0 && (
              <>
                <dt>Asked for</dt>
                <dd>{asked.map((a) => labelOf(ASKS, a)).join(', ')}</dd>
              </>
            )}
            {r.notes && (
              <>
                <dt>Notes</dt>
                <dd>{r.notes}</dd>
              </>
            )}
          </dl>
        </aside>

        <section aria-labelledby="candidates-title">
          <h2 id="candidates-title">Stylists ({visible} shown)</h2>
          {matches.length === 0 && (
            <p>
              The ranker found no one. Add stylists by hand below
              {r.status === 'PUBLISHED' ? ': the client sees them at once.' : '.'}
            </p>
          )}
          <ol className="candidates">
            {matches.map((m, i) => (
              <Candidate
                key={m.id}
                match={m}
                position={i}
                last={i === matches.length - 1}
                editable={editable}
                hideable={hideable}
                busy={busy}
                onMove={(by) => move(i, by)}
                onChange={(hidden, note) => change(m, hidden, note)}
              />
            ))}
          </ol>
          {/* Also after publishing, e.g. a list published empty: the stylist goes on the client's list at once. */}
          {hideable && <AddStylist requestId={id} busy={busy} onAdd={(orgId, note) =>
            act(() => api<MatchReview>('POST', apiPath`/api/admin/match-requests/${id}/matches`, { orgId, note }))} />}
          {editable && (
            <div className="actions">
              <button onClick={publish} disabled={busy || visible === 0} aria-busy={busy}>
                Publish to client
              </button>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function Candidate({
  match: m,
  position,
  last,
  editable,
  hideable,
  busy,
  onMove,
  onChange,
}: {
  match: MatchView
  position: number
  last: boolean
  editable: boolean
  hideable: boolean
  busy: boolean
  onMove: (by: number) => void
  onChange: (hidden: boolean, note: string | null) => void
}) {
  const [note, setNote] = useState(m.adminNote ?? '')
  const name = m.stylistName ?? 'Unknown stylist'
  return (
    <li className={m.hidden ? 'candidate is-hidden' : 'candidate'}>
      <div className="candidate-head">
        <span className="candidate-rank" aria-label={`Position ${position + 1}`}>
          {position + 1}.
        </span>
        <h3>{name}</h3>
        {m.locationName && <span className="muted">{m.locationName}</span>}
        {m.hidden && <Chip tone="warning">hidden</Chip>}
        {m.source === 'ADMIN' && <Chip tone="info">added by reviewer</Chip>}
        {!m.stylistLive && <Chip tone="danger">no longer live</Chip>}
        {m.source === 'SYSTEM' && (
          <span className="muted small">
            score {m.score.toFixed(2)} · ranker's #{m.systemRank}
          </span>
        )}
      </div>
      <p>{m.reason}</p>
      {m.evidence.length > 0 ? (
        <div className="evidence">
          {m.evidence.map((p) => (
            <img key={p.id} src={p.url} loading="lazy" referrerPolicy="no-referrer"
              alt={`${name}'s portfolio photo: ${[p.service, p.hairType].filter(Boolean).map((v) => label(v!)).join(' on ')}`} />
          ))}
        </div>
      ) : (
        <p className="muted small">No portfolio photos to show for this service.</p>
      )}
      {hideable ? (
        <div className="candidate-tools">
          {editable && (
            <>
              <button className="secondary" onClick={() => onMove(-1)} disabled={busy || position === 0} aria-label={`Move ${name} up`}>
                Up
              </button>
              <button className="secondary" onClick={() => onMove(1)} disabled={busy || last} aria-label={`Move ${name} down`}>
                Down
              </button>
            </>
          )}
          <button className="secondary" onClick={() => onChange(!m.hidden, note)} disabled={busy}>
            {m.hidden ? 'Show' : editable ? 'Hide' : 'Hide from client'}
          </button>
          <Field label="Reviewer note (not shown to the client)">
            {(control) => <input {...control} value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />}
          </Field>
          <button className="secondary" onClick={() => onChange(m.hidden, note)} disabled={busy || note === (m.adminNote ?? '')}>
            Save note
          </button>
        </div>
      ) : (
        m.adminNote && <p className="muted small">Note: {m.adminNote}</p>
      )}
    </li>
  )
}

function AddStylist({ requestId, busy, onAdd }: { requestId: string; busy: boolean; onAdd: (orgId: string, note: string | null) => Promise<boolean> }) {
  const api = useApi()
  const [open, setOpen] = useState(false)
  const [orgId, setOrgId] = useState('')
  const { data: options, error } = useLoad(
    () => (open ? api<StylistOption[]>('GET', apiPath`/api/admin/match-requests/${requestId}/stylists`) : Promise.resolve(null)),
    [api, requestId, open],
  )
  if (!open) {
    return (
      <div className="actions">
        <button className="link" onClick={() => setOpen(true)}>
          Add a stylist by hand
        </button>
      </div>
    )
  }
  const available = (options ?? []).filter((o) => !o.alreadyMatched)
  return (
    <div className="panel">
      <h3>Add a stylist</h3>
      {error && <p className="error" role="alert">{error}</p>}
      {!options && !error && <Loading />}
      {options && available.length === 0 && <p>Every live stylist in this market is already in the list.</p>}
      {available.length > 0 && (
        <div className="add-stylist">
          <Field label="Stylist">
            {(control) => (
              <select {...control} value={orgId} onChange={(e) => setOrgId(e.target.value)}>
                <option value="">Choose…</option>
                {available.map((o) => (
                  <option key={o.orgId} value={o.orgId}>
                    {o.displayName}
                    {o.locationName ? ` (${o.locationName})` : ''}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <button
            disabled={busy || !orgId}
            onClick={async () => {
              // Stays open when the add fails, so the choice isn't lost and the error (shown above the list) has context.
              if (await onAdd(orgId, null)) {
                setOrgId('')
                setOpen(false)
              }
            }}
          >
            Add
          </button>
        </div>
      )}
    </div>
  )
}
