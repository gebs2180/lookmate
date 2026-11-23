import { useRef, useState, type CSSProperties } from 'react'
import { Link } from 'react-router'
import { publicApi, type DirectoryPage } from '../../api'
import { Strand, type StrandShape } from '../../strand/Strand'
import { useLoopRunning } from '../../strand/motion'
import { useLoad } from '../../useLoad'
import { InkRise } from './InkRise'

/** When a fade-up starts, after the headline has risen. */
const delay = (ms: number) => ({ '--d': `${ms}ms` }) as CSSProperties

const SHAPE_NAMES: Record<StrandShape, string> = { STRAIGHT: 'Straight', WAVY: 'Wavy', CURLY: 'Curly', COILY: 'Coily' }

/**
 * The signed-out front page, Lookbook style: stylists' own work drifting across the top, the headline rising out of
 * its mask, and the Strand beside it morphing through every hair type, because every hair type is welcome here.
 */
export function Landing({ onStylistLogin }: { onStylistLogin: () => void }) {
  const [shape, setShape] = useState<StrandShape>('STRAIGHT')
  return (
    <main className="landing">
      <HeroStrip />
      <div className="issue">
        <span className="eyebrow">Stylists at Salon Lofts, Columbus</span>
        <span className="eyebrow">No. 01</span>
      </div>
      <div className="landing-hero">
        <h1 className="display">
          <InkRise text="The stylist who's already done" /> <em>
            <InkRise text="your" start={6} />
          </em> <InkRise text="look." start={7} />
        </h1>
        <figure className="landing-strand" aria-hidden="true">
          <Strand cycle onShape={setShape} width={96} height={260} bend={0.8} strokeWidth={3} />
          <figcaption key={shape}>{SHAPE_NAMES[shape]}</figcaption>
        </figure>
      </div>
      <p className="lede fade-up" style={delay(620)}>
        Show us a photo of the hair you want. We find the Columbus stylists whose own work looks like it, on hair like yours: straight,
        wavy, curly or coily.
      </p>
      <div className="landing-cta fade-up" style={delay(760)}>
        <Link className="button block" to="/find">
          Find my stylist
        </Link>
        <Link className="landing-browse" to="/stylists">
          or browse every stylist
        </Link>
      </div>
      <p className="privacy fade-up" style={delay(860)}>
        <LockIcon />
        <span>No account. Your photo stays private: only you and the Combpatible team see it, and we delete it on a set schedule.</span>
      </p>
      <ol className="how">
        <li>
          <b>Two minutes of questions</b>
          <span>Your hair type, the service, what your next stylist should get right.</span>
        </li>
        <li>
          <b>One photo of the look</b>
          <span>A screenshot or a saved post is perfect.</span>
        </li>
        <li>
          <b>Stylists who've done it</b>
          <span>With the photos that prove it. You book on their own page.</span>
        </li>
      </ol>
      <footer className="landing-foot">
        <span>
          Are you a stylist?{' '}
          <button className="link" onClick={onStylistLogin}>
            Stylist log in
          </button>
        </span>
        <span>Our picks are suggestions. Book any stylist you like.</span>
      </footer>
    </main>
  )
}

/** Stylists' published work drifting slowly sideways. Decoration: hidden from screen readers, still when off screen. */
function HeroStrip() {
  const { data } = useLoad(() => publicApi<DirectoryPage>('GET', '/api/public/stylists'), [])
  const ref = useRef<HTMLDivElement>(null)
  const running = useLoopRunning(ref)
  const photos = (data?.stylists ?? []).flatMap((s) => s.photoUrls.slice(0, 2)).slice(0, 8)
  if (photos.length < 3) return <div ref={ref} className="hero-strip empty" aria-hidden="true" />
  // Two copies side by side: the track slides one copy's width, then starts over seamlessly.
  return (
    <div ref={ref} className={running ? 'hero-strip' : 'hero-strip is-paused'} aria-hidden="true">
      <div className="track">
        {[...photos, ...photos].map((url, i) => (
          <div key={i} className="frame">
            <img src={url} alt="" referrerPolicy="no-referrer" loading={i < 4 ? 'eager' : 'lazy'} />
          </div>
        ))}
      </div>
    </div>
  )
}

export function LockIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
      <rect x="5" y="10" width="14" height="10" rx="2" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </svg>
  )
}
