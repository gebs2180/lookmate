import { useMemo, useState, type FormEvent } from 'react'
import { useParams } from 'react-router'
import { apiPath, useApi, type DirectoryClick, type MatchEventView, type RecentMatch, type RecordedEvent, type StylistMatch } from '../../api'
import { label, message } from '../../format'
import { Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './tracking.css'

/** A match row as both lists show it; the admin's also has the client's email. */
type Row = Pick<StylistMatch, 'matchId' | 'clientFirstName' | 'publishedAt' | 'finalRank' | 'bookingClicks' | 'bookings' | 'rebooks'> & {
  clientEmail?: string
  stylistName?: string
}

/** Where one side (the admin, or one stylist org) reads and writes. */
type Paths = {
  list: (query: string) => string
  events: (matchId: string) => string
  voidEvent: (eventId: string) => string
}

const ADMIN: Paths = {
  list: (q) => `/api/admin/tracking/matches${q ? `?q=${encodeURIComponent(q)}` : ''}`,
  events: (matchId) => apiPath`/api/admin/matches/${matchId}/events`,
  voidEvent: (eventId) => apiPath`/api/admin/events/${eventId}/void`,
}

function orgPaths(orgId: string): Paths {
  return {
    list: () => apiPath`/api/orgs/${orgId}/tracking/matches`,
    events: (matchId) => apiPath`/api/orgs/${orgId}/matches/${matchId}/events`,
    voidEvent: (eventId) => apiPath`/api/orgs/${orgId}/events/${eventId}/void`,
  }
}

/** The admin enters a booking or rebook a stylist reported, and voids one entered by mistake. */
export function RecordBookingPage() {
  return (
    <>
      <BookingEntry paths={ADMIN} admin />
      <DirectoryBookings />
    </>
  )
}

/**
 * Bookings with a stylist the client picked from the directory (a Book click with no match). Admin only for now: a
 * stylist's own bookings page lists their matches.
 */
function DirectoryBookings() {
  const api = useApi()
  const { data: clicks, error, reload } = useLoad(() => api<DirectoryClick[]>('GET', '/api/admin/tracking/directory-clicks'), [api])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

  async function record(c: DirectoryClick, type: 'BOOKED' | 'REBOOKED') {
    setBusy(true)
    setActionError(null)
    setSaved(null)
    try {
      await api<RecordedEvent>('POST', apiPath`/api/admin/match-requests/${c.requestId}/stylists/${c.orgId}/events`, { type })
      setSaved(`Recorded a ${label(type)} for ${c.clientFirstName ?? 'the client'} with ${c.stylistName ?? 'the stylist'}.`)
      reload()
    } catch (err) {
      setActionError(message(err))
    } finally {
      setBusy(false)
    }
  }

  async function voidBooking(c: DirectoryClick) {
    const reason = window.prompt('Why is this booking being voided?')
    if (!reason?.trim() || !c.bookedEventId) return
    setBusy(true)
    setActionError(null)
    try {
      await api('POST', apiPath`/api/admin/events/${c.bookedEventId}/void`, { reason: reason.trim() })
      reload()
    } catch (err) {
      setActionError(message(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="metric-section" aria-labelledby="rb-directory-title">
      <h2 id="rb-directory-title">Picked from the directory</h2>
      <p className="muted">Clients who clicked Book on a stylist they found in the directory, newest first. Record a booking here when one came of it.</p>
      {error && <PageError message={error} />}
      {actionError && <p className="error" role="alert">{actionError}</p>}
      {saved && <p className="tracking-saved" role="status">{saved}</p>}
      {!error && !clicks && <Loading />}
      {clicks && clicks.length === 0 && <p>No directory clicks yet.</p>}
      {clicks && clicks.length > 0 && (
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Client</th>
                <th scope="col">Stylist</th>
                <th scope="col">Last click</th>
                <th scope="col">Booking</th>
              </tr>
            </thead>
            <tbody>
              {clicks.map((c) => (
                <tr key={`${c.requestId}-${c.orgId}`}>
                  <td>
                    {c.clientFirstName ?? '–'}
                    {c.clientEmail && <div className="muted">{c.clientEmail}</div>}
                  </td>
                  <td>
                    {c.stylistName ?? '–'}
                    {c.recommended && <div className="muted">also on their list</div>}
                  </td>
                  <td>{new Date(c.lastClickedAt).toLocaleDateString()}</td>
                  <td>
                    {c.bookedEventId ? (
                      <>
                        Booked{' '}
                        <button className="link" onClick={() => record(c, 'REBOOKED')} disabled={busy}>
                          Record a rebook
                        </button>{' '}
                        <button className="link" onClick={() => voidBooking(c)} disabled={busy}>
                          Void
                        </button>
                      </>
                    ) : (
                      <button className="secondary" onClick={() => record(c, 'BOOKED')} disabled={busy}>
                        Record booking
                      </button>
                    )}
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

/** The same for a stylist, on their own matches: clients appear by first name only. */
export function StylistBookingsPage() {
  const { orgId = '' } = useParams()
  // Stable per org: the list reloads when its paths change.
  const paths = useMemo(() => orgPaths(orgId), [orgId])
  if (!orgId) return <PageError message="No stylist org in the link." />
  return <BookingEntry paths={paths} admin={false} />
}

function BookingEntry({ paths, admin }: { paths: Paths; admin: boolean }) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const { data: matches, error, reload } = useLoad(
    () => api<(RecentMatch | StylistMatch)[]>('GET', paths.list(query)),
    [api, paths, query],
  )
  const [matchId, setMatchId] = useState('')
  const [type, setType] = useState<'BOOKED' | 'REBOOKED'>('BOOKED')
  const [date, setDate] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const [version, setVersion] = useState(0)

  const rows: Row[] = (matches ?? []).map((m) => m as Row)
  const selected = rows.find((m) => m.matchId === matchId)

  function refresh() {
    reload()
    setVersion((v) => v + 1)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!matchId) {
      setSaveError('Pick the match the booking came from.')
      return
    }
    setBusy(true)
    setSaveError(null)
    setSaved(null)
    try {
      // A date alone means midday in the user's own time zone, safely inside that day.
      const occurredAt = date ? new Date(`${date}T12:00:00`).toISOString() : undefined
      const recorded = await api<RecordedEvent>('POST', paths.events(matchId), { type, note: note.trim() || undefined, occurredAt })
      setSaved(`Recorded a ${label(recorded.type)} for ${selected?.clientFirstName ?? 'the client'}${selected?.stylistName ? ` with ${selected.stylistName}` : ''}.`)
      setNote('')
      refresh()
    } catch (err) {
      setSaveError(message(err))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <PageError message={error} />

  return (
    <>
      <h1>{admin ? 'Record a booking' : 'Your bookings from LookMate'}</h1>
      <p className="muted">
        For a booking or a return visit that came from a LookMate match. A mistake can be voided below; the record keeps
        both.
      </p>

      {admin && (
        <form
          className="metric-filters"
          role="search"
          onSubmit={(e) => {
            e.preventDefault()
            setQuery(search.trim())
          }}
        >
          <div className="field">
            <label htmlFor="rb-search" className="field-label">Find a match</label>
            <input id="rb-search" type="search" value={search} placeholder="Stylist, client name or email" onChange={(e) => setSearch(e.target.value)} />
          </div>
          <button type="submit">Search</button>
        </form>
      )}

      <form className="form" onSubmit={submit} aria-label="Record a booking">
        {!matches ? (
          <Loading />
        ) : rows.length === 0 ? (
          <p>No published matches yet.</p>
        ) : (
          <fieldset>
            <legend className="field-label">Match</legend>
            <div className="scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">
                      <span className="tracking-sr-only">Pick</span>
                    </th>
                    <th scope="col">Client</th>
                    {admin && <th scope="col">Stylist</th>}
                    <th scope="col">Published</th>
                    <th scope="col" className="num">Clicks</th>
                    <th scope="col" className="num">Booked</th>
                    <th scope="col" className="num">Rebooked</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((m) => (
                    <tr key={m.matchId}>
                      <td>
                        <input
                          type="radio"
                          name="match"
                          value={m.matchId}
                          checked={matchId === m.matchId}
                          onChange={() => setMatchId(m.matchId)}
                          aria-label={`${m.clientFirstName ?? 'Client'}, published ${new Date(m.publishedAt).toLocaleDateString()}`}
                        />
                      </td>
                      <td>
                        {m.clientFirstName ?? '–'}
                        {m.clientEmail && <div className="muted">{m.clientEmail}</div>}
                      </td>
                      {admin && (
                        <td>
                          {m.stylistName ?? '–'} <span className="muted">#{m.finalRank}</span>
                        </td>
                      )}
                      <td>{new Date(m.publishedAt).toLocaleDateString()}</td>
                      <td className="num">{m.bookingClicks}</td>
                      <td className="num">{m.bookings}</td>
                      <td className="num">{m.rebooks}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </fieldset>
        )}

        <fieldset>
          <legend className="field-label">What happened</legend>
          <label>
            <input type="radio" name="type" checked={type === 'BOOKED'} onChange={() => setType('BOOKED')} /> First booking
          </label>{' '}
          <label>
            <input type="radio" name="type" checked={type === 'REBOOKED'} onChange={() => setType('REBOOKED')} /> Rebooked (a return visit)
          </label>
        </fieldset>

        <div className="field">
          <label htmlFor="rb-date" className="field-label">Date the booking was made</label>
          <input id="rb-date" type="date" value={date} onChange={(e) => setDate(e.target.value)} aria-describedby="rb-date-hint" />
          <p id="rb-date-hint" className="field-hint">Leave empty for today.</p>
        </div>
        <div className="field">
          <label htmlFor="rb-note" className="field-label">Note (optional)</label>
          <textarea id="rb-note" maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>

        {saveError && <p className="error" role="alert">{saveError}</p>}
        {saved && <p className="tracking-saved" role="status">{saved}</p>}
        <div className="actions">
          <button type="submit" disabled={busy} aria-busy={busy}>
            Record {type === 'BOOKED' ? 'booking' : 'rebook'}
          </button>
        </div>
      </form>

      {matchId && <MatchEvents key={`${matchId}-${version}`} path={paths.events(matchId)} voidPath={paths.voidEvent} admin={admin} onChange={refresh} />}
    </>
  )
}

/** What's recorded on the picked match, with a Void action on each live event the user may void. */
function MatchEvents({
  path,
  voidPath,
  admin,
  onChange,
}: {
  path: string
  voidPath: (eventId: string) => string
  admin: boolean
  onChange: () => void
}) {
  const api = useApi()
  const { data: events, error } = useLoad(() => api<MatchEventView[]>('GET', path), [api, path])
  const [voiding, setVoiding] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [voidError, setVoidError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function confirmVoid(e: FormEvent) {
    e.preventDefault()
    if (!voiding) return
    setBusy(true)
    setVoidError(null)
    try {
      await api('POST', voidPath(voiding), { reason: reason.trim() })
      setVoiding(null)
      setReason('')
      onChange()
    } catch (err) {
      setVoidError(message(err))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!events) return <Loading />

  return (
    <section className="metric-section" aria-labelledby="rb-events-title">
      <h2 id="rb-events-title">Recorded on this match</h2>
      {events.length === 0 ? (
        <p>Nothing yet.</p>
      ) : (
        <div className="scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">What</th>
                <th scope="col">When</th>
                <th scope="col">By</th>
                <th scope="col">Note</th>
                <th scope="col">
                  <span className="tracking-sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {events.map((ev) => {
                const mayVoid = !ev.voided && (admin || ev.type === 'BOOKED' || ev.type === 'REBOOKED')
                return (
                  <tr key={ev.id} className={ev.voided ? 'tracking-voided' : undefined}>
                    <td>
                      {label(ev.type)}
                      {ev.voided && <div className="muted">Voided: {ev.voidReason}</div>}
                    </td>
                    <td>{new Date(ev.occurredAt).toLocaleDateString()}</td>
                    <td>{label(ev.source)}</td>
                    <td>{ev.note ?? ''}</td>
                    <td>
                      {mayVoid && (
                        <button type="button" className="link" onClick={() => setVoiding(ev.id)}>
                          Void
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {voiding && (
        <form className="form" onSubmit={confirmVoid} aria-label="Void an event">
          <div className="field">
            <label htmlFor="rb-void-reason" className="field-label">Why is this being voided?</label>
            <input id="rb-void-reason" required maxLength={2000} value={reason} onChange={(e) => setReason(e.target.value)} />
          </div>
          {voidError && <p className="error" role="alert">{voidError}</p>}
          <div className="actions">
            <button type="submit" disabled={busy || !reason.trim()} aria-busy={busy}>
              Void it
            </button>
            <button type="button" className="link" onClick={() => setVoiding(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
