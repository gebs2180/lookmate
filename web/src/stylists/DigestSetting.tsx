import { useState } from 'react'
import { apiPath, type DigestSettingView as Setting } from '../api'
import { message } from '../format'
import { useApi } from '../useApi'
import { useLoad } from '../useLoad'

/** The switch for the Monday email (docs/tracking.md, "Weekly digest"); per stylist, on by default. */
export function DigestSetting({ orgId }: { orgId: string }) {
  const api = useApi()
  const { data, error } = useLoad(() => api<Setting>('GET', apiPath`/api/orgs/${orgId}/digest`), [api, orgId])
  const [saved, setSaved] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const on = saved ?? data?.weeklyDigest

  async function change(next: boolean) {
    setBusy(true)
    setSaveError(null)
    try {
      setSaved((await api<Setting>('PUT', apiPath`/api/orgs/${orgId}/digest`, { weeklyDigest: next })).weeklyDigest)
    } catch (e) {
      setSaveError(message(e))
    } finally {
      setBusy(false)
    }
  }

  if (error || on === undefined) return null
  return (
    <section className="panel" aria-labelledby="digest">
      <h2 id="digest">Weekly email</h2>
      <label className="check">
        <input type="checkbox" checked={on} onChange={(e) => change(e.target.checked)} disabled={busy} />
        Email me every Monday: who Combpatible showed me to, and who tapped Book
      </label>
      {saveError && (
        <p className="error" role="alert">
          {saveError}
        </p>
      )}
    </section>
  )
}
