import { useState } from 'react'
import { apiPath, useApi } from '../api'
import { message } from '../format'
import { Loading } from '../ui'
import { useLoad } from '../useLoad'
import type { ConsentStatus } from './types'

/** The stylist terms, in full, with one button to accept the version shown. */
export function TermsStep({ orgId, onAccepted }: { orgId: string; onAccepted: () => void }) {
  const api = useApi()
  const { data: terms, error } = useLoad(() => api<ConsentStatus>('GET', apiPath`/api/orgs/${orgId}/consent`), [api, orgId])
  const [busy, setBusy] = useState(false)
  const [acceptError, setAcceptError] = useState<string | null>(null)

  async function accept() {
    if (!terms) return
    setBusy(true)
    setAcceptError(null)
    try {
      await api<ConsentStatus>('POST', apiPath`/api/orgs/${orgId}/consent`, { version: terms.version })
      onAccepted()
    } catch (e) {
      setAcceptError(message(e))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <p className="error" role="alert">{error}</p>
  if (!terms) return <Loading />
  return (
    <section aria-labelledby="terms-heading">
      <h2 id="terms-heading">Stylist terms</h2>
      <pre className="terms" tabIndex={0}>
        {terms.text}
      </pre>
      <div className="actions">
        <button className="block" onClick={accept} disabled={busy} aria-busy={busy}>
          I accept these terms
        </button>
      </div>
      {acceptError && (
        <p className="error" role="alert">
          {acceptError}
        </p>
      )}
    </section>
  )
}
