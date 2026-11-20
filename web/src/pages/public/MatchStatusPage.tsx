import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, apiPath, publicApi, type ClientMatch, type ClientView } from '../../api'
import { message } from '../../format'
import { Chip, Loading } from '../../ui'
import { useLoad, usePoll, type Backoff } from '../../useLoad'
import { PhotoPicker, PrivacyNote } from './PhotoPicker'
import { sendPhoto } from './photo'
import { HAIR_TYPES, SERVICES, labelOf } from './quiz'
import { StylistCard } from './StylistCard'
import { useNoReferrer } from './useNoReferrer'

const STAGES: { stage: ClientView['stage']; label: string }[] = [
  { stage: 'RECEIVED', label: 'Request received' },
  { stage: 'MATCHING', label: 'Matching you with stylists' },
  { stage: 'READY', label: 'Your matches are ready' },
]

/**
 * While matching, check every 1.5s for the first 30s (ranking usually finishes within a worker pass), then a little
 * less often each time (up to every 30s), for 20 minutes.
 */
const POLL_START_MS = 1500
const POLL_BACKOFF: Backoff = { factor: 1.5, maxMs: 30_000, steadyMs: 30_000, giveUpMs: 20 * 60_000 }

/**
 * The client's own request, reached by the private link in their email (/m/:token). No account. Book links and images
 * send no referrer, so the link never reaches a booking site. While the request is being matched the page checks back
 * by itself and shows the stylists as soon as they're published (matching publishes on its own, CLAUDE.md).
 */
export function MatchStatusPage() {
  const { token = '' } = useParams()
  const { data: view, error, reload } = useLoad(
    () => publicApi<ClientView>('GET', apiPath`/api/public/match-requests/${token}`),
    [token],
  )
  useNoReferrer()
  const waiting = view?.stage === 'RECEIVED' || view?.stage === 'MATCHING'
  usePoll(waiting, reload, POLL_START_MS, POLL_BACKOFF)

  // A failed check while the page is already showing is retried by the next one; only a first load that fails is an error.
  if (error && !view) {
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

      {view.stage === 'PHOTO_NEEDED' && <AddPhoto token={token} keptDays={view.photoKeptDays} onSent={reload} />}
      {view.stage === 'CLOSED' && <p className="alert alert-info">This request is closed.</p>}
      {view.stage !== 'PHOTO_NEEDED' && view.stage !== 'CLOSED' && <Progress stage={view.stage} />}
      {waiting && (
        <p className="muted small" role="status">
          This page updates by itself when your matches are ready, and we'll email you too. Meanwhile you can{' '}
          <Link to={`/m/${encodeURIComponent(token)}/stylists`}>browse all stylists</Link>.
        </p>
      )}

      {view.stage === 'READY' && <Matches token={token} matches={view.matches} />}
      {view.stage === 'READY' && (
        <p>
          <Link className="button secondary" to={`/m/${encodeURIComponent(token)}/stylists`}>
            See all stylists
          </Link>
        </p>
      )}

      <div className="panel">
        <h2>Your inspiration photo</h2>
        {view.photoUrl && <img className="inspiration-preview" src={view.photoUrl} alt="Your inspiration photo" referrerPolicy="no-referrer" />}
        {view.photoDeleted ? (
          <p>Deleted, as promised: we keep inspiration photos for {view.photoKeptDays} days.</p>
        ) : view.photoUploaded && view.photoDeletesAt ? (
          <p className="muted small">Private to you and the Combpatible team. We delete it on {new Date(view.photoDeletesAt).toLocaleDateString()}.</p>
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

/**
 * The recommendations, best first. Each "Book" is tracking's redirect: it records the click, then sends the client to
 * the stylist's own booking page with UTM tags. Built here because only this page has the client's token.
 */
function Matches({ token, matches }: { token: string; matches: ClientMatch[] }) {
  // Tell tracking the list was seen (the pilot's "viewed" metric, and the held "matches ready" email isn't sent).
  useEffect(() => {
    if (matches.length === 0) return
    publicApi('POST', apiPath`/api/public/match-requests/${token}/viewed`).catch(() => {
      // A missed view is only a missing count; never bother the client with it.
    })
  }, [token, matches.length])

  if (matches.length === 0) {
    return <p>Your matches are being put together. Check back soon, or browse all stylists below.</p>
  }
  return (
    <>
      <h2>Stylists for your look</h2>
      <p className="muted small">These are suggestions: you can book any stylist you like. You book on the stylist's own page.</p>
      <ol className="stylist-cards">
        {matches.map((m) => (
          <StylistCard
            key={m.matchId}
            name={m.stylistName}
            rank={m.rank}
            location={m.locationName}
            demo={m.demo}
            reason={m.reason}
            photoUrls={m.photoUrls}
            instagram={m.instagram}
            book={{ href: apiPath`/api/public/book/${token}/${m.matchId}` }}
          />
        ))}
      </ol>
    </>
  )
}

function AddPhoto({ token, keptDays, onSent }: { token: string; keptDays: number; onSent: () => void }) {
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
      <PrivacyNote keptDays={keptDays} />
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
