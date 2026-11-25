import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError, apiPath, type DigestLink } from '../../api'
import { message, shortDate } from '../../format'
import { SERVICE_LABEL, type ServiceType } from '../../stylists/types'
import { Loading, PageError } from '../../ui'
import { useApi } from '../../useApi'
import { useLoad } from '../../useLoad'

type Kind = 'BOOKED' | 'REBOOKED'
type Recorded = { id: string; type: Kind }

const WORD: Record<Kind, string> = { BOOKED: 'booked', REBOOKED: 'rebooked' }

/** The errors the API names by code, said for a stylist (docs/tracking.md, "Weekly digest"). */
const CODE_TEXT: Record<string, string> = {
  LINK_USED: 'This link was already used: the booking is recorded.',
  LINK_EXPIRED: 'This link has expired. Record the booking on your bookings page instead.',
  ALREADY_BOOKED: 'A booking with this client is already recorded. If they came back, tap Rebooked instead.',
  REBOOK_WITHOUT_BOOKING: 'Record the first booking before a rebook.',
}

/**
 * Where the weekly digest's "Booked" and "Rebooked" buttons land. The link alone records nothing: the stylist signs in
 * (a member of the link's org, so a forwarded email gets nowhere) and confirms here, and can undo it afterwards.
 */
export function DigestConfirmPage() {
  const { token = '' } = useParams()
  const [params] = useSearchParams()
  const kind: Kind = params.get('type') === 'REBOOKED' ? 'REBOOKED' : 'BOOKED'
  const api = useApi()
  const { data: link, error } = useLoad(() => api<DigestLink>('GET', apiPath`/api/digest-links/${token}`), [api, token])
  const [recorded, setRecorded] = useState<Recorded | null>(null)
  const [undone, setUndone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  if (error) return <PageError message={error} />
  if (!link) return <Loading />

  const who = link.clientFirstName ?? 'This client'
  const service = link.service && link.service in SERVICE_LABEL ? SERVICE_LABEL[link.service as ServiceType].toLowerCase() : null
  const bookings = `/orgs/${link.orgId}/bookings`

  async function confirm() {
    setBusy(true)
    setActionError(null)
    try {
      setRecorded(await api<Recorded>('POST', apiPath`/api/digest-links/${token}`, { type: kind }))
    } catch (e) {
      setActionError(e instanceof ApiError && e.code && CODE_TEXT[e.code] ? CODE_TEXT[e.code] : message(e))
    } finally {
      setBusy(false)
    }
  }

  async function undo(eventId: string) {
    setBusy(true)
    setActionError(null)
    try {
      await api('POST', apiPath`/api/orgs/${link!.orgId}/events/${eventId}/void`, { reason: 'Undone from the weekly digest' })
      setUndone(true)
    } catch (e) {
      setActionError(message(e))
    } finally {
      setBusy(false)
    }
  }

  const done = recorded ?? (link.used && link.eventId ? { id: link.eventId, type: kind } : null)

  return (
    <>
      <p className="eyebrow">Your week from {shortDate(link.weekStart)}</p>
      {undone ? (
        <>
          <h1>Undone</h1>
          <p>Nothing is recorded for {who} from this link.</p>
        </>
      ) : done ? (
        <>
          <h1>Recorded</h1>
          <p>
            {who} {recorded ? `is ${WORD[recorded.type]}` : 'is recorded'}. Thanks: it counts toward your numbers.
          </p>
          <div className="actions">
            <button className="secondary" onClick={() => undo(done.id)} disabled={busy} aria-busy={busy}>
              Undo
            </button>
          </div>
        </>
      ) : link.expired ? (
        <>
          <h1>This link has expired</h1>
          <p>Digest links last 14 days. You can still record {who} on your bookings page.</p>
        </>
      ) : (
        <>
          <h1>
            Record {who} as {WORD[kind]}?
          </h1>
          <p>
            {who} tapped Book on you{service ? ` for a ${service}` : ''}
            {link.directoryPick ? ', from the stylist list' : ''}.{' '}
            {kind === 'BOOKED' ? 'Confirm only if they booked with you.' : 'Confirm only if they came back for another visit.'}
          </p>
          <div className="actions">
            <button className="block" onClick={confirm} disabled={busy} aria-busy={busy}>
              Yes, {WORD[kind]}
            </button>
          </div>
        </>
      )}
      {actionError && (
        <p className="error" role="alert">
          {actionError}
        </p>
      )}
      <p className="muted small">
        <Link to={bookings}>All your bookings</Link>
      </p>
    </>
  )
}
