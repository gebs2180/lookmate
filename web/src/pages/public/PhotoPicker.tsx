import { useEffect, useMemo, useState } from 'react'
import { message } from '../../format'
import { preparePhoto } from './photo'

/**
 * "Never public, deleted after N days": shown wherever a client picks a photo. N is the server's retention setting;
 * until it has loaded, the note says the photo is deleted without a number rather than guess one.
 */
export function PrivacyNote({ keptDays }: { keptDays: number | null }) {
  return (
    <div className="alert alert-info privacy-note">
      <p className="alert-title">Your photo stays private</p>
      <p>
        Only the Combpatible team sees it. It's never public, and we delete it{' '}
        {keptDays ? `${keptDays} days after your request` : 'on a set schedule'}. We remove
        location and camera details before it's stored.
      </p>
    </div>
  )
}

/** Take a photo or choose one from the library; shows a preview of the chosen one. */
export function PhotoPicker({ photo, onChange }: { photo: Blob | null; onChange: (photo: Blob | null) => void }) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const preview = useMemo(() => (photo && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(photo) : null), [photo])
  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview)
  }, [preview])

  async function pick(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setError(null)
    setBusy(true)
    try {
      onChange(await preparePhoto(file))
    } catch (e) {
      setError(message(e))
      onChange(null)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack">
      {preview && <img className="inspiration-preview" src={preview} alt="Your inspiration photo" />}
      <div className="actions">
        <label className="capture">
          <input type="file" accept="image/*" capture="environment" onChange={pick} disabled={busy} />
          Take a photo
        </label>
        <label className="capture secondary">
          <input type="file" accept="image/*" onChange={pick} disabled={busy} />
          Choose from your photos
        </label>
      </div>
      {busy && <p className="muted">Getting your photo ready…</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
