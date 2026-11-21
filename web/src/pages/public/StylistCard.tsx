import { useState, type CSSProperties, type ReactNode } from 'react'
import { Chip } from '../../ui'
import { BookLink, InstagramLink, Portfolio, type StylistView } from './Portfolio'

/**
 * One stylist in the directory, as an index entry: the name set large, where they work, what they do, four of their
 * photos, and their whole portfolio a tap away. A made-up stylist (proof of concept) is always labelled. Images and
 * links send no referrer: the client's link token is in the page's URL.
 */
export function StylistCard({ index = 0, badge, ...stylist }: StylistView & { index?: number; badge?: ReactNode }) {
  // The photo the portfolio opens at, or null while it's closed.
  const [open, setOpen] = useState<number | null>(null)
  const { name, location, demo, specialties, photoUrls } = stylist
  return (
    <li style={{ '--i': index } as CSSProperties}>
      <div className="row">
        <h3>{name}</h3>
        {demo && <Chip tone="warning">Sample stylist</Chip>}
        {badge}
      </div>
      {location && <p className="meta">{location}</p>}
      {specialties && specialties.length > 0 && <p className="meta">{specialties.join(' · ')}</p>}
      {photoUrls.length > 0 && (
        <div className="thumbs">
          {photoUrls.slice(0, 4).map((url, i) => (
            // Named by its photo: the button opens the whole portfolio at that photo.
            <button key={url} type="button" className="frame" onClick={() => setOpen(i)}>
              <img src={url} alt={`${name}'s work, photo ${i + 1}`} referrerPolicy="no-referrer" loading="lazy" />
            </button>
          ))}
        </div>
      )}
      <div className="actions">
        <BookLink stylist={stylist} className="button" />
        {photoUrls.length > 0 && (
          <button className="secondary" onClick={() => setOpen(0)}>
            See {photoUrls.length === 1 ? 'the photo' : `all ${photoUrls.length} photos`}
          </button>
        )}
        <InstagramLink handle={stylist.instagram} />
      </div>
      {open !== null && <Portfolio stylist={stylist} startAt={open} onClose={() => setOpen(null)} />}
    </li>
  )
}
