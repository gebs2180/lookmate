import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, apiPath, publicApi, type ClientMatch, type ClientView } from '../../api'
import { message } from '../../format'
import { Chip, Loading } from '../../ui'
import { useLoad } from '../../useLoad'
import { PhotoPicker, PrivacyNote } from './PhotoPicker'
import { sendPhoto } from './photo'
import { HAIR_TYPES, SERVICES, labelOf } from './quiz'

const STAGES: { stage: ClientView['stage']; label: string }[] = [
  { stage: 'RECEIVED', label: 'Request received' },
  { stage: 'MATCHING', label: 'Matching you with stylists' },
  { stage: 'READY', label: 'Your matches are ready' },
]

/**
 * The client's own request, reached by the private link in their email (/m/:token). No account. Book links and images
 * send no referrer, so the link never reaches a booking site.
 */
export function MatchStatusPage() {
  const { token = '' } = useParams()
  const { data: view, error, reload } = useLoad(
    () => publicApi<ClientView>('GET', apiPath`/api/public/match-requests/${token}`),
    [token],
  )
  useNoReferrer()

  if (error) {
    return (
      <section className="find">
        <h1>This link doesn't work</h1>
        <p className="error" role="alert">
          {error}
        </p>
        <p>
          <Link to="/find">Start a new request</Link>
        </p>
      </section>
    )
  }
  if (!view) return <Loading />

  const service = labelOf(SERVICES, view.service)
  const hairType = labelOf(HAIR_TYPES, view.hairType)
  return (
    <section className="find">
      <h1>{view.firstName ? `${view.firstName}, here's your request` : 'Your request'}</h1>
      <p className="muted">
        {service}
        {hairType ? ` · ${hairType.toLowerCase()} hair` : ''} · sent {new Date(view.createdAt).toLocaleDateString()}
      </p>

      {view.stage === 'PHOTO_NEEDED' && <AddPhoto token={token} onSent={reload} />}
      {view.stage === 'CLOSED' && <p className="alert alert-info">This request is closed.</p>}
      {view.stage !== 'PHOTO_NEEDED' && view.stage !== 'CLOSED' && <Progress stage={view.stage} />}

      {view.stage === 'READY' && <Matches matches={view.matches} />}

      <div className="panel">
        <h2>Your inspiration photo</h2>
        {view.photoUrl && <img className="inspiration-preview" src={view.photoUrl} alt="Your inspiration photo" referrerPolicy="no-referrer" />}
        {view.photoDeleted ? (
          <p>Deleted, as promised: we keep inspiration photos for 30 days.</p>
        ) : view.photoUploaded && view.photoDeletesAt ? (
          <p className="muted small">Private to you and the LookMate team. We delete it on {new Date(view.photoDeletesAt).toLocaleDateString()}.</p>
        ) : null}
      </div>
    </section>
  )
}

function Progress({ stage }: { stage: ClientView['stage'] }) {
  const at = STAGES.findIndex((s) => s.stage === stage)
  return (
    <ol className="stage-list">
      {STAGES.map((s, i) => (
        <li key={s.stage} aria-current={i === at ? 'step' : undefined}>
          <Chip tone={i < at ? 'success' : i === at ? 'info' : 'neutral'}>{i < at ? 'Done' : i === at ? 'Now' : 'Next'}</Chip> {s.label}
        </li>
      ))}
    </ol>
  )
}

function Matches({ matches }: { matches: ClientMatch[] }) {
  if (matches.length === 0) {
    return <p>Your matches are being put together. Check back soon.</p>
  }
  return (
    <>
      <h2>Stylists for your look</h2>
      <p className="muted small">These are suggestions: you can book any stylist you like. You book on the stylist's own page.</p>
      <ol className="tile-list">
        {matches.map((m) => (
          <li key={m.matchId} className="tile match">
            <div className="tile-head">
              <strong>
                {m.rank}. {m.stylistName}
              </strong>
              {m.locationName && <span className="muted">{m.locationName}</span>}
            </div>
            <p>{m.reason}</p>
            {m.photoUrls.length > 0 && (
              <div className="photos">
                {m.photoUrls.map((url, i) => (
                  <img key={url} className="thumb" src={url} alt={`${m.stylistName}'s work, photo ${i + 1}`} referrerPolicy="no-referrer" />
                ))}
              </div>
            )}
            <div className="actions">
              {m.bookUrl && (
                <a className="button" href={m.bookUrl} target="_blank" rel="noreferrer noopener">
                  Book with {m.stylistName}
                </a>
              )}
              {m.instagram && (
                <a className="button secondary" href={`https://instagram.com/${encodeURIComponent(m.instagram)}`} target="_blank" rel="noreferrer noopener">
                  Instagram
                </a>
              )}
            </div>
          </li>
        ))}
      </ol>
    </>
  )
}

function AddPhoto({ token, onSent }: { token: string; onSent: () => void }) {
  const [photo, setPhoto] = useState<Blob | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function send() {
    if (!photo) return
    setBusy(true)
    setError(null)
    try {
      await sendPhoto(token, photo)
      onSent()
    } catch (e) {
      setError(e instanceof ApiError && e.code === 'PHOTO_ALREADY_CONFIRMED' ? null : message(e))
      if (e instanceof ApiError && e.code === 'PHOTO_ALREADY_CONFIRMED') onSent()
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel">
      <h2>Add your inspiration photo</h2>
      <p>We match on the look in your photo, so we start once it's here.</p>
      <PhotoPicker photo={photo} onChange={setPhoto} />
      <PrivacyNote />
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button onClick={send} disabled={!photo || busy} aria-busy={busy}>
          {busy ? 'Sending…' : 'Send photo'}
        </button>
      </div>
    </div>
  )
}

/** The link is the client's key: no page they open from here, and no image host, learns it from a Referer header. */
function useNoReferrer() {
  useEffect(() => {
    const meta = document.createElement('meta')
    meta.name = 'referrer'
    meta.content = 'no-referrer'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])
}
