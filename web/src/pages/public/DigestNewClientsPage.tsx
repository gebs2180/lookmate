import { useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, apiPath, publicApi } from '../../api'
import { message } from '../../format'
import { newClientsLine } from '../../stylists/practice'
import type { NewClients } from '../../stylists/types'

/**
 * The weekly digest's "Still taking new clients? Confirm" link. No sign-in, and a button rather than confirming on
 * open, like the unsubscribe link. It confirms the status as it stands; changing it is on the stylist's profile.
 */
export function DigestNewClientsPage() {
  const { token = '' } = useParams()
  const [confirmed, setConfirmed] = useState<NewClients | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    setBusy(true)
    setError(null)
    try {
      const body = await publicApi<{ newClients: NewClients }>('POST', apiPath`/api/public/digest/new-clients/${token}`)
      setConfirmed(body.newClients)
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? 'This link has expired. Log in and confirm it on your stylist home page instead.'
          : message(e),
      )
    } finally {
      setBusy(false)
    }
  }

  if (confirmed) {
    return (
      <>
        <h1>Thanks, confirmed</h1>
        <p>Your profile still says: {newClientsLine(confirmed)}. We'll ask again in a month.</p>
        <p className="muted small">To change it, log in and edit your profile.</p>
      </>
    )
  }
  return (
    <>
      <p className="eyebrow">For stylists</p>
      <h1>Is your new-client status still right?</h1>
      <p>Clients see it on your match card and your profile. It never changes who you're matched with.</p>
      <div className="actions">
        <button className="block" onClick={confirm} disabled={busy} aria-busy={busy}>
          Yes, still right
        </button>
      </div>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </>
  )
}
