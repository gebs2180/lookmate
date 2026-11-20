import { useState, type FormEvent } from 'react'
import { apiPath, useApi, type RecentMatch, type RecordedEvent } from '../../api'
import { label, message } from '../../format'
import { Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import './tracking.css'

/**
 * The admin enters a booking or a rebook the stylist reported, until booking platforms report them. Events are
 * append-only: a mistake can't be edited away, so the form asks for a confirmation of what's being recorded.
 */
export function RecordBookingPage() {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const { data: matches, error, reload } = useLoad(
    () => api<RecentMatch[]>('GET', `/api/admin/tracking/matches${query ? `?q=${encodeURIComponent(query)}` : ''}`),
    [api, query],
  )
  const [matchId, setMatchId] = useState('')
  const [type, setType] = useState<'BOOKED' | 'REBOOKED'>('BOOKED')
  const [date, setDate] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

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
      // A date alone means midday in the admin's own time zone, safely inside that day.
      const occurredAt = date ? new Date(`${date}T12:00:00`).toISOString() : undefined
      const recorded = await api<RecordedEvent>('POST', apiPath`/api/admin/matches/${matchId}/events`, {
        type,
        note: note.trim() || undefined,
        occurredAt,
      })
      const m = matches?.find((x) => x.matchId === matchId)
      setSaved(`Recorded a ${label(recorded.type)} for ${m?.clientFirstName ?? 'the client'} with ${m?.stylistName ?? 'the stylist'}.`)
      setNote('')
      reload()
    } catch (err) {
      setSaveError(message(err))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <PageError message={error} />

  return (
    <>
      <h1>Record a booking</h1>
      <p className="muted">For a booking or a return visit the stylist or client told you about. These can't be edited later.</p>

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

      <form className="form" onSubmit={submit} aria-label="Record a booking">
        {!matches ? (
          <Loading />
        ) : matches.length === 0 ? (
          <p>No published matches found.</p>
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
                    <th scope="col">Stylist</th>
                    <th scope="col">Published</th>
                    <th scope="col" className="num">Clicks</th>
                    <th scope="col" className="num">Booked</th>
                    <th scope="col" className="num">Rebooked</th>
                  </tr>
                </thead>
                <tbody>
                  {matches.map((m) => (
                    <tr key={m.matchId}>
                      <td>
                        <input
                          type="radio"
                          name="match"
                          value={m.matchId}
                          checked={matchId === m.matchId}
                          onChange={() => setMatchId(m.matchId)}
                          aria-label={`${m.clientFirstName ?? 'Client'} with ${m.stylistName ?? 'stylist'}`}
                        />
                      </td>
                      <td>
                        {m.clientFirstName ?? '–'}
                        {m.clientEmail && <div className="muted">{m.clientEmail}</div>}
                      </td>
                      <td>
                        {m.stylistName ?? '–'} <span className="muted">#{m.finalRank}</span>
                      </td>
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
          <label htmlFor="rb-date" className="field-label">Date of the appointment booking</label>
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
    </>
  )
}
