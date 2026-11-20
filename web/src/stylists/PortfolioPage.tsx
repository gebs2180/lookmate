import { useState, type ChangeEvent } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath, uploadToStorage, useApi, type UploadSlot } from '../api'
import { message } from '../format'
import { EmptyState, Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'
import './stylists.css'
import { TagSheet } from './TagSheet'
import { SERVICE_LABEL, words, type PortfolioPhoto } from './types'

const ACCEPT = 'image/jpeg,image/png'

type Upload = { name: string; state: 'uploading' | 'done' | 'failed'; error?: string }

/** The stylist's portfolio: upload from the camera or library, then tag, pair, order, hide or delete each photo. */
export function PortfolioPage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data: loaded, error, reload } = useLoad(() => api<PortfolioPhoto[]>('GET', apiPath`/api/orgs/${orgId}/photos`), [api, orgId])
  const [uploads, setUploads] = useState<Upload[]>([])
  const [open, setOpen] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  async function uploadOne(file: File, index: number) {
    const mark = (u: Partial<Upload>) => setUploads((all) => all.map((x, i) => (i === index ? { ...x, ...u } : x)))
    try {
      const slot = await api<UploadSlot>('POST', apiPath`/api/orgs/${orgId}/photos`, { contentType: file.type, sizeBytes: file.size })
      await uploadToStorage(slot, file)
      await api<PortfolioPhoto>('POST', apiPath`/api/orgs/${orgId}/photos/${slot.photoId}/confirm`)
      mark({ state: 'done' })
    } catch (e) {
      mark({ state: 'failed', error: message(e) })
    }
  }

  async function pick(e: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? [])
    e.target.value = ''
    if (files.length === 0) return
    const start = uploads.length
    setUploads((all) => [...all, ...files.map((f) => ({ name: f.name, state: 'uploading' as const }))])
    // One at a time: phone connections are slow, and the server checks photos a few at once anyway.
    for (const [i, file] of files.entries()) {
      await uploadOne(file, start + i)
    }
    reload()
  }

  async function move(photos: PortfolioPhoto[], index: number, by: number) {
    const order = photos.map((p) => p.id)
    const [moved] = order.splice(index, 1)
    order.splice(index + by, 0, moved)
    setActionError(null)
    try {
      await api<PortfolioPhoto[]>('PUT', apiPath`/api/orgs/${orgId}/photos/order`, { photoIds: order })
    } catch (e) {
      setActionError(message(e))
    }
    reload()
  }

  if (error) return <PageError message={error} />
  if (!loaded) return <Loading />
  const photos = loaded
  const uploading = uploads.some((u) => u.state === 'uploading')
  const untagged = photos.filter((p) => p.status === 'READY' && (!p.service || !p.hairType)).length
  const current = photos.find((p) => p.id === open)

  return (
    <>
      <p>
        <Link to={`/stylist/${orgId}`} className="back">
          ‹ Back
        </Link>
      </p>
      <h1>Your portfolio</h1>
      <p>Add 10 to 20 photos of your best work. Tap a photo to tag it: clients are matched on the service and hair type.</p>

      <div className="upload-bar">
        <label className="button">
          Take a photo
          <input type="file" accept={ACCEPT} capture="environment" onChange={pick} disabled={uploading} aria-label="Take a photo" />
        </label>
        <label className="button secondary">
          Choose photos
          <input type="file" accept={ACCEPT} multiple onChange={pick} disabled={uploading} aria-label="Choose photos" />
        </label>
      </div>

      {uploads.length > 0 && (
        <ul className="uploads" aria-live="polite">
          {uploads.map((u, i) => (
            <li key={i} className={u.state === 'failed' ? 'error' : undefined}>
              {u.name}: {u.state === 'uploading' ? 'uploading…' : u.state === 'done' ? 'added' : `not added. ${u.error}`}
            </li>
          ))}
        </ul>
      )}

      {untagged > 0 && (
        <p className="alert alert-warning">
          {untagged === 1 ? '1 photo needs' : `${untagged} photos need`} a service and hair type before clients can be matched to it.
        </p>
      )}
      {actionError && (
        <p className="error" role="alert">
          {actionError}
        </p>
      )}

      {photos.length === 0 ? (
        <EmptyState title="No photos yet">
          <p>Start with a few recent looks you're proud of. Before and after shots work great.</p>
        </EmptyState>
      ) : (
        <ul className="portfolio">
          {photos.map((p, i) => (
            <li key={p.id} className={p.status === 'HIDDEN' ? 'portfolio-item hidden' : 'portfolio-item'}>
              <button className="thumb-button" onClick={() => setOpen(p.id)} aria-label={`Tag photo ${i + 1}${describe(p) ? `: ${describe(p)}` : ', not tagged'}`}>
                {p.url && <img src={p.url} alt="" loading="lazy" />}
              </button>
              <p className={p.service && p.hairType ? 'tags' : 'tags untagged'}>
                {p.status === 'HIDDEN' ? 'Hidden. ' : ''}
                {describe(p) || 'Needs tags'}
              </p>
              <div className="row">
                <button className="secondary small" onClick={() => move(photos, i, -1)} disabled={i === 0} aria-label={`Move photo ${i + 1} earlier`}>
                  ‹
                </button>
                <button
                  className="secondary small"
                  onClick={() => move(photos, i, 1)}
                  disabled={i === photos.length - 1}
                  aria-label={`Move photo ${i + 1} later`}
                >
                  ›
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {current && (
        <TagSheet
          orgId={orgId}
          photo={current}
          photos={photos}
          onClose={() => setOpen(null)}
          onChanged={() => {
            setOpen(null)
            reload()
          }}
        />
      )}
    </>
  )
}

function describe(p: PortfolioPhoto) {
  const parts = [p.service ? SERVICE_LABEL[p.service] : null, p.hairType ? words(p.hairType) : null, p.pairPhotoId ? (p.before ? 'before' : 'after') : null]
  return parts.filter(Boolean).join(' · ')
}
