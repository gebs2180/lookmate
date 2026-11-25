import { useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, apiPath, publicApi } from '../../api'
import { message } from '../../format'

/**
 * The weekly digest's unsubscribe link. No sign-in, and a button rather than unsubscribing on open, so a mail scanner
 * following the link changes nothing. Turning it back on is a switch on the stylist's home page.
 */
export function DigestUnsubscribePage() {
  const { token = '' } = useParams()
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function unsubscribe() {
    setBusy(true)
    setError(null)
    try {
      await publicApi<void>('POST', apiPath`/api/public/digest/unsubscribe/${token}`)
      setDone(true)
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? 'This link has expired. Log in and turn the weekly email off on your stylist home page instead.'
          : message(e),
      )
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <>
        <h1>You're unsubscribed</h1>
        <p>We won't send you the weekly Combpatible email. You can turn it back on from your stylist home page.</p>
      </>
    )
  }
  return (
    <>
      <p className="eyebrow">For stylists</p>
      <h1>Stop the weekly email?</h1>
      <p>Every Monday, Combpatible emails you who it showed you to and who tapped Book.</p>
      <div className="actions">
        <button className="block" onClick={unsubscribe} disabled={busy} aria-busy={busy}>
          Unsubscribe
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
