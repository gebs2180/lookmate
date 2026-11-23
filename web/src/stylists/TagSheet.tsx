import { useEffect, useId, useRef, useState } from 'react'
import { apiPath } from '../api'
import { useApi } from '../useApi'
import { message } from '../format'
import {
  HAIR_TYPES,
  LENGTHS,
  SERVICES,
  SERVICE_LABEL,
  TEXTURES,
  words,
  type HairLength,
  type HairType,
  type PortfolioPhoto,
  type ServiceType,
  type TagRequest,
  type Texture,
} from './types'

/**
 * One photo's tags in a bottom sheet with big tap targets: service, hair type, texture, length, and an optional
 * before/after partner. Also hide/show and delete.
 */
export function TagSheet({
  orgId,
  photo,
  photos,
  onClose,
  onChanged,
}: {
  orgId: string
  photo: PortfolioPhoto
  photos: PortfolioPhoto[]
  onClose: () => void
  onChanged: () => void
}) {
  const api = useApi()
  const titleId = useId()
  const sheet = useRef<HTMLDivElement>(null)
  const [service, setService] = useState<ServiceType | null>(photo.service)
  const [hairType, setHairType] = useState<HairType | null>(photo.hairType)
  const [texture, setTexture] = useState<Texture | null>(photo.texture)
  const [length, setLength] = useState<HairLength | null>(photo.length)
  const [pairPhotoId, setPairPhotoId] = useState<string | null>(photo.pairPhotoId)
  const [before, setBefore] = useState(photo.pairPhotoId ? photo.before : true)
  const [busy, setBusy] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    sheet.current?.focus()
    const escape = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', escape)
    return () => document.removeEventListener('keydown', escape)
  }, [onClose])

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onChanged()
    } catch (e) {
      setError(message(e))
      setBusy(false)
    }
  }

  const save = () => {
    const body: TagRequest = { service, hairType, texture, length, before, pairPhotoId }
    return run(() => api('PUT', apiPath`/api/orgs/${orgId}/photos/${photo.id}/tags`, body))
  }
  const toggleHidden = () =>
    run(() =>
      api('POST', photo.status === 'HIDDEN' ? apiPath`/api/orgs/${orgId}/photos/${photo.id}/show` : apiPath`/api/orgs/${orgId}/photos/${photo.id}/hide`),
    )
  const remove = () => run(() => api('DELETE', apiPath`/api/orgs/${orgId}/photos/${photo.id}`))

  const others = photos.filter((p) => p.id !== photo.id)
  return (
    <div className="sheet-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={sheet} tabIndex={-1}>
        <div className="sheet-head">
          {photo.url && <img src={photo.url} alt="" />}
          <h2 id={titleId}>Tag this photo</h2>
        </div>

        <Choices legend="Service" name="service" options={SERVICES} value={service} onChange={setService} label={(s) => SERVICE_LABEL[s]} />
        <Choices legend="Hair type" name="hair-type" options={HAIR_TYPES} value={hairType} onChange={setHairType} />
        <Choices legend="Texture" name="texture" options={TEXTURES} value={texture} onChange={setTexture} />
        <Choices legend="Length" name="length" options={LENGTHS} value={length} onChange={setLength} />

        {others.length > 0 && (
          <fieldset className="tag-group">
            <legend className="field-label">Before and after</legend>
            <p className="field-hint">If this photo pairs with another one, pick it.</p>
            <div className="pair-picker">
              <label>
                <input type="radio" name="pair" checked={pairPhotoId === null} onChange={() => setPairPhotoId(null)} />
                <span className="choice">No pair</span>
              </label>
              {others.map((p, i) => (
                <label key={p.id}>
                  <input type="radio" name="pair" checked={pairPhotoId === p.id} onChange={() => setPairPhotoId(p.id)} aria-label={`Pair with photo ${i + 1}`} />
                  {p.url && <img src={p.url} alt="" />}
                </label>
              ))}
            </div>
            {pairPhotoId && (
              <div className="choices" role="radiogroup" aria-label="This photo is the">
                <label className="choice">
                  <input type="radio" name="before" checked={before} onChange={() => setBefore(true)} />
                  This is the before
                </label>
                <label className="choice">
                  <input type="radio" name="before" checked={!before} onChange={() => setBefore(false)} />
                  This is the after
                </label>
              </div>
            )}
          </fieldset>
        )}

        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <div className="actions">
          <button className="block" onClick={save} disabled={busy} aria-busy={busy}>
            Save tags
          </button>
          <button className="secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
        </div>
        <div className="actions">
          <button className="secondary" onClick={toggleHidden} disabled={busy}>
            {photo.status === 'HIDDEN' ? 'Show on my profile' : 'Hide from my profile'}
          </button>
          {confirmDelete ? (
            <button className="danger" onClick={remove} disabled={busy}>
              Yes, delete it
            </button>
          ) : (
            <button className="secondary" onClick={() => setConfirmDelete(true)} disabled={busy}>
              Delete photo
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function Choices<T extends string>({
  legend,
  name,
  options,
  value,
  onChange,
  label = words,
}: {
  legend: string
  name: string
  options: readonly T[]
  value: T | null
  onChange: (value: T) => void
  label?: (value: T) => string
}) {
  return (
    <fieldset className="tag-group">
      <legend className="field-label">{legend}</legend>
      <div className="choices">
        {options.map((o) => (
          <label key={o} className="choice">
            <input type="radio" name={name} checked={value === o} onChange={() => onChange(o)} />
            {label(o)}
          </label>
        ))}
      </div>
    </fieldset>
  )
}
