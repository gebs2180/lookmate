import { useEffect, useId, useRef, type CSSProperties, type ReactNode } from 'react'
import { Chip } from '../../ui'

/** One stylist as clients see them, wherever they appear: on the client's picks or in the directory. */
export type StylistView = {
  name: string
  location: string | null
  demo: boolean
  reason?: string
  specialties?: string[]
  photoUrls: string[]
  instagram: string | null
  /** The tracked Book link, or what to show instead when there is none (no client link on this page). */
  book: { href: string } | { instead: ReactNode }
}

/** Book: tracking's redirect, opened without a referrer so the client's link never reaches the booking site. */
export function BookLink({ stylist, className = 'button accent' }: { stylist: StylistView; className?: string }) {
  if (!('href' in stylist.book)) return <>{stylist.book.instead}</>
  return (
    <a className={className} href={stylist.book.href} target="_blank" rel="noreferrer noopener">
      Book with {stylist.name}
    </a>
  )
}

export function InstagramLink({ handle }: { handle: string | null }) {
  if (!handle) return null
  return (
    <a className="button secondary" href={`https://instagram.com/${encodeURIComponent(handle)}`} target="_blank" rel="noreferrer noopener">
      Instagram
    </a>
  )
}

/**
 * A stylist's work as a spread: the first photo full width, the rest two across, Book pinned to the bottom. Built from
 * the photos the page already has (there is no per-stylist endpoint), in a modal dialog so the page under it keeps its
 * place. Escape or Back closes it.
 */
export function Portfolio({ stylist, onClose }: { stylist: StylistView; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (typeof dialog.showModal === 'function') dialog.showModal()
    else dialog.setAttribute('open', '')
    return () => dialog.close?.()
  }, [])

  const [first, ...rest] = stylist.photoUrls
  const alt = (i: number) => `${stylist.name}'s work, photo ${i + 1}`
  return (
    <dialog
      ref={ref}
      className="portfolio"
      aria-labelledby={titleId}
      // Escape closes through onCancel, not onClose: the effect's cleanup calls close(), and in StrictMode that
      // close event would unmount the dialog it had just reopened.
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
    >
      <div className="pf-hero">
        {first && (
          <div className="frame">
            <img src={first} alt={alt(0)} referrerPolicy="no-referrer" />
          </div>
        )}
        <button className="pf-close" onClick={onClose} aria-label={`Close ${stylist.name}'s work`}>
          ← Back
        </button>
      </div>
      <div className="pf-title">
        {stylist.location && <p className="eyebrow">{stylist.location}</p>}
        <h2 id={titleId}>{stylist.name}</h2>
        {stylist.demo && (
          <p>
            <Chip tone="warning">Sample stylist</Chip>
          </p>
        )}
        {stylist.reason && <p className="reason">{stylist.reason}</p>}
        {stylist.specialties && stylist.specialties.length > 0 && <p className="muted small">{stylist.specialties.join(' · ')}</p>}
      </div>
      {rest.length > 0 && (
        <div className="grid2">
          {rest.map((url, i) => (
            <div key={url} className="frame" style={{ '--i': i } as CSSProperties}>
              <img src={url} alt={alt(i + 1)} referrerPolicy="no-referrer" loading="lazy" />
            </div>
          ))}
        </div>
      )}
      <div className="stickybook">
        <div className="actions">
          <BookLink stylist={stylist} />
          <InstagramLink handle={stylist.instagram} />
        </div>
        <small>You book on {stylist.name}'s own page. Combpatible never takes payments.</small>
      </div>
    </dialog>
  )
}
