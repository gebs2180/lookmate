import { useEffect, useRef, useState, type CSSProperties, type FormEvent, type UIEvent } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, apiPath, publicApi, type ClientMatch, type ClientView } from '../../api'
import { message } from '../../format'
import { Strand, type StrandShape } from '../../strand/Strand'
import { useLoopRunning } from '../../strand/motion'
import { Chip, Loading } from '../../ui'
import { useLoad, usePoll, type Backoff } from '../../useLoad'
import { PhotoPicker, PrivacyNote } from './PhotoPicker'
import { sendPhoto } from './photo'
import { BookLink, InstagramLink, Portfolio, type StylistView } from './Portfolio'
import { HAIR_TYPES, SERVICES, labelOf } from './quiz'
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
 * The client's own request at its private link (/m/:token): the quiz lands here, and the URL is the client's way back
 * (no account, and an email only if they add one here). Book links and images send no referrer, so the link never
 * reaches a booking site. While the request is being matched the page checks back by itself and shows the stylists as
 * soon as they're published (matching publishes on its own, CLAUDE.md).
 */
export function MatchStatusPage() {
  const { token = '' } = useParams()
  const { data: loaded, error, reload } = useLoad(
    () => publicApi<ClientView>('GET', apiPath`/api/public/match-requests/${token}`),
    [token],
  )
  // The email box answers with the whole view; keep it until the next check replaces it.
  const [updated, setUpdated] = useState<{ after: typeof loaded; view: ClientView } | null>(null)
  const view = updated && updated.after === loaded ? updated.view : loaded
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

  // Published with nobody on it: no stylist's portfolio was a close match (CLAUDE.md, decided 2026-11-20).
  const noMatches = view.stage === 'READY' && view.matches.length === 0
  const revealed = view.stage === 'READY' && !noMatches
  const directory = `/m/${encodeURIComponent(token)}/stylists`
  const service = labelOf(SERVICES, view.service)
  const hairType = labelOf(HAIR_TYPES, view.hairType)
  const sent = new Date(view.createdAt).toLocaleDateString()
  return (
    <section className="find request">
      <h1>{view.firstName ? `${view.firstName}, here's your request` : 'Your request'}</h1>
      {revealed ? (
        <blockquote className="echo">
          <p>{`“${echo(view)}”`}</p>
          <cite>What you asked for, {sent}</cite>
        </blockquote>
      ) : (
        <p className="muted">
          {service}
          {hairType ? ` · ${hairType.toLowerCase()} hair` : ''} · sent {sent}
        </p>
      )}

      {view.stage === 'PHOTO_NEEDED' && <AddPhoto token={token} keptDays={view.photoKeptDays} onSent={reload} />}
      {view.stage === 'CLOSED' && <p className="alert alert-info">This request is closed.</p>}
      {waiting && <ContactSheet hairType={view.hairType} />}
      {waiting && <Progress stage={view.stage} />}
      {waiting && (
        <p className="muted small" role="status">
          This page updates by itself when your matches are ready, usually in a few minutes. Meanwhile you can{' '}
          <Link to={directory}>browse all stylists</Link>.
        </p>
      )}

      {noMatches && <NoCloseMatches directory={directory} />}
      {revealed && <Matches token={token} matches={view.matches} hairType={view.hairType} directory={directory} />}
      {(waiting || view.stage === 'READY') && (
        <KeepThisList token={token} view={view} title={noMatches ? 'Tell me when picks are added' : 'Email me this list'} onSaved={(saved) => setUpdated({ after: loaded, view: saved })} />
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

/** Where the request is: the stages the server reports, nothing finer (no made-up percentages). */
function Progress({ stage }: { stage: ClientView['stage'] }) {
  const at = STAGES.findIndex((s) => s.stage === stage)
  return (
    <ol className="steps">
      {STAGES.map((s, i) => (
        <li key={s.stage} className={i < at ? 'done' : undefined} aria-current={i === at ? 'step' : undefined}>
          <span className="dot" aria-hidden="true" />
          <span>{s.label}</span>
          <span className="sr-only">{i < at ? ', done' : i === at ? ', now' : ', next'}</span>
        </li>
      ))}
    </ol>
  )
}

const SHEET_FRAMES = 12

/**
 * The matching moment: a contact sheet of blank frames with the strand, in the client's hair type, threading through
 * it while we look through portfolios. It stands for the looking, never for how far along it is: no frame numbers, no
 * frames ticked off, no counts. Decoration only (the stage list and the status line say it in words), and still when
 * it's off screen or motion is reduced.
 */
function ContactSheet({ hairType }: { hairType: ClientView['hairType'] }) {
  const ref = useRef<HTMLDivElement>(null)
  const running = useLoopRunning(ref)
  return (
    <div ref={ref} className={running ? 'sheet' : 'sheet is-paused'} aria-hidden="true">
      <div className="sheet-frames">
        {Array.from({ length: SHEET_FRAMES }, (_, i) => (
          <i key={i} style={{ '--i': i } as CSSProperties} />
        ))}
      </div>
      <Strand shape={hairType as StrandShape} horizontal draw="loop" width={320} height={150} bend={1.6} strokeWidth={3.5} />
    </div>
  )
}

/** A list published empty: say so plainly and send the client to the directory. The page doesn't check back. */
function NoCloseMatches({ directory }: { directory: string }) {
  return (
    <div className="panel">
      <h2>No close matches yet</h2>
      <p>
        We looked through every stylist's portfolio and didn't find work close enough to your look on hair like yours yet. You can
        still browse all our stylists and book any of them.
      </p>
      <p>
        <Link className="button" to={directory}>
          Browse all stylists
        </Link>
      </p>
      <p className="muted small">Our team may add picks to this list, so it's worth opening this page again later.</p>
    </div>
  )
}

/**
 * The recommendations as magazine covers, best first: the stylist's work full bleed with their name set over it, the
 * reason as the coverline, a strand in the client's hair type threading down to the photos that earned the match, and
 * Book. Each "Book" is tracking's redirect: it records the click, then sends the client to the stylist's own booking
 * page with UTM tags. Built here because only this page has the client's token. No scores, only the order.
 */
function Matches({
  token,
  matches,
  hairType,
  directory,
}: {
  token: string
  matches: ClientMatch[]
  hairType: ClientView['hairType']
  directory: string
}) {
  const [at, setAt] = useState(0)
  // Tell tracking the list was seen (the pilot's "viewed" metric, and the held "matches ready" email isn't sent).
  useEffect(() => {
    if (matches.length === 0) return
    publicApi('POST', apiPath`/api/public/match-requests/${token}/viewed`).catch(() => {
      // A missed view is only a missing count; never bother the client with it.
    })
  }, [token, matches.length])

  const listRef = useRef<HTMLOListElement>(null)
  const last = matches.length - 1
  const shown = Math.min(at, last)

  // The dots under the covers follow the scroll.
  function onScroll(event: UIEvent<HTMLOListElement>) {
    const list = event.currentTarget
    const cards = [...list.children] as HTMLElement[]
    if (cards.length === 0) return
    // On a wide screen the row can't scroll a whole card, so its end counts as the last card.
    if (list.scrollLeft >= list.scrollWidth - list.clientWidth - 2) {
      setAt(cards.length - 1)
      return
    }
    const left = list.scrollLeft + cards[0].offsetLeft
    let nearest = 0
    cards.forEach((card, i) => {
      if (Math.abs(card.offsetLeft - left) < Math.abs(cards[nearest].offsetLeft - left)) nearest = i
    })
    setAt(nearest)
  }

  // Swiping works on phones; a mouse can't scroll the row sideways, so the arrows and dots move it too.
  function goTo(index: number) {
    const list = listRef.current
    const target = list?.children[index] as HTMLElement | undefined
    const first = list?.firstElementChild as HTMLElement | null
    if (!list || !target || !first) return
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    list.scrollTo?.({ left: target.offsetLeft - first.offsetLeft, behavior: reduce ? 'auto' : 'smooth' })
    setAt(index)
  }

  return (
    <>
      <ul className="tabs">
        <li>
          <span aria-current="page">{matches.length === 1 ? 'Your pick' : `Your ${matches.length} picks`}</span>
        </li>
        <li>
          <Link to={directory}>All stylists</Link>
        </li>
      </ul>
      <ol className="covers" ref={listRef} onScroll={onScroll} aria-label="Stylists for your look">
        {matches.map((m, i) => (
          <Cover key={m.matchId} index={i} match={m} hairType={hairType} book={{ href: apiPath`/api/public/book/${token}/${m.matchId}` }} />
        ))}
      </ol>
      {matches.length > 1 && (
        <div className="cover-nav">
          <button type="button" className="cover-arrow" onClick={() => goTo(shown - 1)} disabled={shown === 0} aria-label="Previous stylist">
            ←
          </button>
          <div className="dots">
            {matches.map((m, i) => (
              <button
                type="button"
                key={m.matchId}
                className={i === shown ? 'on' : undefined}
                onClick={() => goTo(i)}
                aria-label={`Stylist ${i + 1}: ${m.stylistName}`}
                aria-current={i === shown || undefined}
              />
            ))}
          </div>
          <button type="button" className="cover-arrow" onClick={() => goTo(shown + 1)} disabled={shown === last} aria-label="Next stylist">
            →
          </button>
        </div>
      )}
      <div className="anyone">
        <p>These are suggestions: you can book any stylist you like. You book on the stylist's own page.</p>
        <p>
          <Link className="button secondary" to={directory}>
            See all stylists
          </Link>
        </p>
      </div>
    </>
  )
}

function Cover({
  index,
  match,
  hairType,
  book,
}: {
  index: number
  match: ClientMatch
  hairType: ClientView['hairType']
  book: { href: string }
}) {
  const [open, setOpen] = useState(false)
  const stylist: StylistView = {
    name: match.stylistName,
    location: match.locationName,
    demo: match.demo,
    reason: match.reason,
    photoUrls: match.photoUrls,
    instagram: match.instagram,
    book,
  }
  const [cover, ...evidence] = match.photoUrls
  const alt = (i: number) => `${match.stylistName}'s work, photo ${i + 1}`
  return (
    <li className="cover" style={{ '--i': index } as CSSProperties}>
      <div className="cover-photo">
        <div className="frame">{cover && <img src={cover} alt={alt(0)} referrerPolicy="no-referrer" />}</div>
        <span className="scrim" />
        <span className="no" aria-hidden="true">
          No. {index + 1}
        </span>
        {match.demo && (
          <span className="sample-on">
            <Chip tone="warning">Sample stylist</Chip>
          </span>
        )}
        <h3>{match.stylistName}</h3>
      </div>
      <div className="cover-body">
        {match.locationName && <p className="eyebrow">{match.locationName}</p>}
        <p className="reason">{match.reason}</p>
        {evidence.length > 0 && (
          <>
            <p className="thread" aria-hidden="true">
              <Strand shape={hairType as StrandShape} width={18} height={44} bend={0.42} strokeWidth={2} />
              The work behind it
            </p>
            <div className="evidence">
              {evidence.slice(0, 3).map((url, i) => (
                <div key={url} className="frame">
                  <img src={url} alt={alt(i + 1)} referrerPolicy="no-referrer" loading="lazy" />
                </div>
              ))}
            </div>
          </>
        )}
        <div className="cover-actions">
          <BookLink stylist={stylist} />
          {match.photoUrls.length > 1 && (
            <button className="secondary" onClick={() => setOpen(true)}>
              All {match.photoUrls.length} photos
            </button>
          )}
          <InstagramLink handle={match.instagram} />
        </div>
      </div>
      {open && <Portfolio stylist={stylist} onClose={() => setOpen(false)} />}
    </li>
  )
}

const LENGTH_WORDS: Record<string, string> = { PIXIE: 'cropped', SHORT: 'short', MEDIUM: 'shoulder-length', LONG: 'long' }
const TEXTURE_WORDS: Record<string, string> = { FINE: 'fine', THICK: 'thick' }

/** The pull quote over the picks: the client's own answers, said back to them (the API has no free-text note). */
function echo(view: ClientView): string {
  const service = labelOf(SERVICES, view.service) ?? 'A new look'
  const hair = [
    view.length ? LENGTH_WORDS[view.length] : null,
    view.texture ? TEXTURE_WORDS[view.texture] : null,
    labelOf(HAIR_TYPES, view.hairType)?.toLowerCase(),
  ].filter(Boolean)
  return hair.length > 0 ? `${service}, on my ${hair.join(', ')} hair.` : `${service}.`
}

/**
 * The page's URL is the client's link, so say so; and an optional email box that sends it to them (and lets us email
 * when picks are added, or ask later how the appointment went). Once an email is set it shows a hint of it instead, with
 * a way to correct it a couple of times.
 */
function KeepThisList({
  token,
  view,
  title,
  onSaved,
}: {
  token: string
  view: ClientView
  title: string
  onSaved: (view: ClientView) => void
}) {
  const [email, setEmail] = useState('')
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const valid = /^\S+@\S+\.\S+$/.test(email.trim())
  const showForm = !view.emailHint || editing

  async function save(event: FormEvent) {
    event.preventDefault()
    if (!valid || busy) return
    setBusy(true)
    setError(null)
    try {
      const saved = await publicApi<ClientView>('POST', apiPath`/api/public/match-requests/${token}/email`, { email: email.trim() })
      setEditing(false)
      setEmail('')
      onSaved(saved)
    } catch (e) {
      setError(
        e instanceof ApiError && e.code === 'EMAIL_CHANGE_LIMIT'
          ? "This request's email can't be changed again. Bookmark this page instead."
          : e instanceof ApiError && e.code === 'TOO_MANY_FOR_ADDRESS'
            ? "We've sent that address several links today. Try again tomorrow, or bookmark this page."
            : message(e),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="panel keep">
      <h2>{title}</h2>
      <p>
        <strong>This page is your link.</strong> Bookmark this page, or email it to yourself. Anyone with the link can see your request, so
        keep it to yourself.
      </p>
      {view.emailHint && !editing ? (
        <p role="status">
          We've emailed this list to <strong>{view.emailHint}</strong>
          {title === 'Tell me when picks are added' ? ", and we'll write again if picks are added." : '.'}{' '}
          {view.emailChangeAllowed && (
            <button type="button" className="link-button" onClick={() => setEditing(true)}>
              Use a different email
            </button>
          )}
        </p>
      ) : null}
      {showForm && (
        <form className="keep-form" onSubmit={save} noValidate>
          <label className="field">
            <span className="field-label">Email (optional)</span>
            <input
              type="email"
              inputMode="email"
              autoComplete="email"
              maxLength={254}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </label>
          <button type="submit" disabled={!valid || busy} aria-busy={busy}>
            {busy ? 'Sending…' : view.emailHint ? 'Send it here instead' : 'Send me the link'}
          </button>
          {editing && (
            <button type="button" className="secondary" onClick={() => setEditing(false)} disabled={busy}>
              Cancel
            </button>
          )}
        </form>
      )}
      {showForm && <p className="muted small">We may ask how your appointment went. No account, no newsletter.</p>}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </div>
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
