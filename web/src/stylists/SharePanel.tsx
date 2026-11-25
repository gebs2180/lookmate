import { useMemo, useState } from 'react'
import { encodeQr, qrSvgPath } from './qr'

/**
 * "Share your page" on the dashboard: the stylist's own page (combpatible.app/s/<slug>) to copy, a QR card to print for
 * the station mirror, and a link for their Instagram bio. Each carries ?via= so the page's Book clicks say where the
 * client came from. The QR code is drawn here (qr.ts): no outside service sees the link.
 */
export function SharePanel({ name, slug }: { name: string; slug: string }) {
  const page = `${window.location.origin}/s/${slug}`
  const qrLink = `${page}?via=qr`
  const bioLink = `${page}?via=bio`
  const code = useMemo(() => encodeQr(qrLink), [qrLink])
  const [copied, setCopied] = useState<string | null>(null)

  async function copy(what: string, text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(what)
    } catch {
      setCopied(null)
    }
  }

  return (
    <section className="panel share" aria-labelledby="share">
      <h2 id="share">Share your page</h2>
      <p>
        Your own page shows your work by hair type, what to expect in your chair, and Book. Clients who come through it are yours:
        Combpatible never charges you for them.
      </p>
      <p className="share-link">
        <a href={page} target="_blank" rel="noreferrer noopener">
          {page.replace(/^https?:\/\//, '')}
        </a>
      </p>
      <div className="actions">
        <button className="secondary" onClick={() => copy('link', page)}>
          Copy link
        </button>
        <button className="secondary" onClick={() => window.print()}>
          Print QR card
        </button>
      </div>

      <h3 className="eyebrow">For your Instagram bio</h3>
      <p className="muted small">Paste it into the website field of your profile, so it is tappable.</p>
      <div className="actions">
        <input readOnly value={bioLink} aria-label="Link for your bio" onFocus={(e) => e.currentTarget.select()} />
        <button className="secondary" onClick={() => copy('bio', bioLink)}>
          Copy
        </button>
      </div>
      <p className="muted small" role="status">
        {copied === 'link' ? 'Link copied.' : copied === 'bio' ? 'Bio link copied.' : ''}
      </p>

      <figure className="qr-card" aria-label="Your QR card">
        <p className="eyebrow">Book with</p>
        <p className="qr-name">{name}</p>
        <svg viewBox={`0 0 ${code.size + 8} ${code.size + 8}`} role="img" aria-label={`QR code for ${page}`} shapeRendering="crispEdges">
          <rect width={code.size + 8} height={code.size + 8} fill="#fff" />
          <path d={qrSvgPath(code)} fill="#1a1614" />
        </svg>
        <figcaption>
          Scan to see my work and book
          <br />
          <span className="qr-url">{page.replace(/^https?:\/\//, '')}</span>
        </figcaption>
      </figure>
    </section>
  )
}
