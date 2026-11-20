import type { ReactNode } from 'react'
import { Chip } from '../../ui'

/**
 * One stylist as a client sees them: on their recommendations (with the reason and the photos that earned the match) or
 * in the directory. A made-up stylist (proof of concept) is always labelled. Images and links send no referrer: the
 * client's link token is in the page's URL.
 */
export function StylistCard({
  name,
  rank,
  location,
  demo,
  badge,
  reason,
  specialties,
  photoUrls,
  instagram,
  book,
}: {
  name: string
  rank?: number
  location: string | null
  demo: boolean
  badge?: ReactNode
  reason?: string
  specialties?: string[]
  photoUrls: string[]
  instagram: string | null
  /** The tracked Book link, or what to show instead when there is none (no client link on this page). */
  book: { href: string } | { instead: ReactNode }
}) {
  const heading = rank === undefined ? name : `${rank}. ${name}`
  return (
    <li className="stylist-card">
      <div className="stylist-card-head">
        <h3>{heading}</h3>
        {demo && <Chip tone="warning">Sample stylist</Chip>}
        {badge}
      </div>
      {location && <p className="muted small">{location}</p>}
      {reason && <p>{reason}</p>}
      {specialties && specialties.length > 0 && <p className="muted small">{specialties.join(' · ')}</p>}
      {photoUrls.length > 0 && (
        <div className="photos">
          {photoUrls.map((url, i) => (
            <img key={url} className="thumb" src={url} alt={`${name}'s work, photo ${i + 1}`} referrerPolicy="no-referrer" loading="lazy" />
          ))}
        </div>
      )}
      <div className="actions">
        {'href' in book ? (
          <a className="button" href={book.href} target="_blank" rel="noreferrer noopener">
            Book with {name}
          </a>
        ) : (
          book.instead
        )}
        {instagram && (
          <a className="button secondary" href={`https://instagram.com/${encodeURIComponent(instagram)}`} target="_blank" rel="noreferrer noopener">
            Instagram
          </a>
        )}
      </div>
    </li>
  )
}
