import { useEffect, useId, useRef, type CSSProperties, type ReactNode } from 'react'
import {
  CONSULTATION_PUBLIC,
  LABEL_PUBLIC,
  PRICE_DEPENDS,
  fromPriceLine,
  hasPractice,
  languagesLine,
} from '../../stylists/practice'
import { HAIR_TYPE_LABEL, type Practice, type StylistPublicPage } from '../../stylists/types'
import { Chip } from '../../ui'
import { FirstVisitBlock, NewClientsLine } from './FirstVisit'
import { useFirstVisitNotes, type Visit } from './useFirstVisit'
import { useStylistPage } from './useStylistPage'

/** One stylist as clients see them, wherever they appear: on the client's picks or in the directory. */
export type StylistView = {
  name: string
  location: string | null
  demo: boolean
  reason?: string
  specialties?: string[]
  photoUrls: string[]
  instagram: string | null
  /**
   * What to expect in their chair (profile only, never a filter or in ranking). The directory has it; a client's picks
   * don't, so the portfolio takes it from the stylist's page (fetched by orgId) when it's left out.
   */
  practice?: Practice | null
  orgId?: string
  /** What the client asked for, when known: the portfolio adds the generic first-visit notes for it. */
  visit?: Visit
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
 * A stylist's work as a spread: the first photo full width, the rest two across, Book pinned to the bottom, in a modal
 * dialog so the page under it keeps its place. It opens on the photos the page already has, then shows the whole
 * portfolio grouped by hair type once the stylist's page has loaded (by orgId). Escape or Back closes it. startAt
 * scrolls to a photo the client tapped.
 */
export function Portfolio({ stylist, startAt = 0, onClose }: { stylist: StylistView; startAt?: number; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (typeof dialog.showModal === 'function') dialog.showModal()
    else dialog.setAttribute('open', '')
    if (startAt > 0) dialog.querySelectorAll('.frame')[startAt]?.scrollIntoView?.({ block: 'center' })
    return () => dialog.close?.()
    // Only on opening: startAt is where the spread starts, not something to follow.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const page = useStylistPage(stylist.orgId)
  const practice = stylist.practice !== undefined ? stylist.practice : page === undefined ? undefined : (page?.practice ?? null)
  const notes = useFirstVisitNotes(stylist.visit)
  const grouped = page && page.photoGroups.length > 0 ? page.photoGroups : null
  const [first, ...rest] = grouped ? [grouped[0].photos[0].url] : stylist.photoUrls
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
        <NewClientsLine newClients={practice?.newClients} />
      </div>
      <PracticeBlock name={stylist.name} practice={practice} />
      <FirstVisitBlock name={stylist.name} firstVisit={practice?.firstVisit} notes={notes} />
      {grouped ? (
        <PhotoGroups name={stylist.name} groups={grouped} skipFirst />
      ) : (
        rest.length > 0 && (
          <div className="grid2">
            {rest.map((url, i) => (
              <div key={url} className="frame" style={{ '--i': i } as CSSProperties}>
                <img src={url} alt={alt(i + 1)} referrerPolicy="no-referrer" loading="lazy" />
              </div>
            ))}
          </div>
        )
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

/**
 * What to expect in the stylist's chair: the labels they ticked, languages, from-prices and consultation. Shows only
 * what they gave, never a "No"; nothing at all when they gave nothing (or it couldn't be loaded).
 */
export function PracticeBlock({ name, practice }: { name: string; practice: Practice | null | undefined }) {
  if (!hasPractice(practice)) return null
  const prices = fromPriceLine(practice)
  const speaks = languagesLine(practice.languages)
  return (
    <section className="pf-practice" aria-label="What to expect in their chair">
      <h3 className="eyebrow">What to expect in {name}'s chair</h3>
      {practice.labels.length > 0 && (
        <ul className="pf-labels">
          {practice.labels.map((l) => (
            <li key={l}>{LABEL_PUBLIC[l]}</li>
          ))}
        </ul>
      )}
      {speaks && <p>{speaks}</p>}
      {prices && <p className="pf-prices">{prices}</p>}
      {practice.consultation && <p>{CONSULTATION_PUBLIC[practice.consultation]}</p>}
      {practice.priceDependsOnHair && <p className="muted small">{PRICE_DEPENDS}</p>}
    </section>
  )
}

/**
 * The portfolio by hair type (straight, wavy, curly, coily, then untagged), two across. skipFirst: the first photo is
 * already shown full width above. A heading per group only when there is more than one.
 */
export function PhotoGroups({
  name,
  groups,
  skipFirst = false,
}: {
  name: string
  groups: StylistPublicPage['photoGroups']
  skipFirst?: boolean
}) {
  let n = 0
  const headed = groups.length > 1
  return (
    <>
      {groups.map((g, gi) => {
        const photos = skipFirst && gi === 0 ? g.photos.slice(1) : g.photos
        const title = g.hairType ? `${HAIR_TYPE_LABEL[g.hairType]} hair` : 'More work'
        if (photos.length === 0) return null
        return (
          <section key={g.hairType ?? 'untagged'} className="pf-group" aria-label={headed ? title : undefined}>
            {headed && <h3 className="eyebrow">{title}</h3>}
            {photos.length > 0 && (
              <div className="grid2">
                {photos.map((p, i) => {
                  n++
                  return (
                    <div key={p.photoId} className="frame" style={{ '--i': i } as CSSProperties}>
                      <img
                        src={p.url}
                        alt={`${name}'s work${g.hairType ? ` on ${HAIR_TYPE_LABEL[g.hairType].toLowerCase()} hair` : ''}, photo ${n + (skipFirst ? 1 : 0)}${p.before ? ' (before)' : ''}`}
                        referrerPolicy="no-referrer"
                        loading="lazy"
                      />
                    </div>
                  )
                })}
              </div>
            )}
          </section>
        )
      })}
    </>
  )
}
