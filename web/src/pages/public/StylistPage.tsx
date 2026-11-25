import { Link, useParams, useSearchParams } from 'react-router'
import { ApiError, apiPath, publicApi } from '../../api'
import { SERVICE_LABEL, type StylistPublicPage } from '../../stylists/types'
import { Loading, NotFound, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import { FirstVisitBlock, NewClientsLine } from './FirstVisit'
import { InstagramLink, PhotoGroups, PracticeBlock } from './Portfolio'

/** Where the visitor came from, as the stylist shared it: their QR card, their link in bio, or anything else. */
function via(raw: string | null): 'qr' | 'bio' | 'link' {
  return raw === 'qr' || raw === 'bio' ? raw : 'link'
}

/**
 * A stylist's own page, combpatible.app/s/<slug>: their work by hair type, what to expect in their chair, and Book. It
 * is the stylist's to share, so the clients it brings are theirs: Book is tracked as its own source (PROFILE, never
 * charged for) and goes straight to their booking page. Link previews get their tags from the backend, not from here.
 */
export function StylistPage() {
  const { slug = '' } = useParams()
  const [params] = useSearchParams()
  const { data, error } = useLoad(
    () =>
      publicApi<StylistPublicPage>('GET', apiPath`/api/public/stylists/by-slug/${slug}`).then(
        (page) => ({ page }),
        (e: unknown) => {
          // Not live, hidden, a demo stylist or a mistyped address: all the same "not found".
          if (e instanceof ApiError && e.status === 404) return { page: null }
          throw e
        },
      ),
    [slug],
  )
  if (error) return <PageError message={error} />
  if (!data) return <Loading />
  return data.page ? <Page page={data.page} via={via(params.get('via'))} /> : <NotFound />
}

function Page({ page, via }: { page: StylistPublicPage; via: string }) {
  const name = page.displayName
  const first = page.photoGroups[0]?.photos[0]
  const where = [page.locationName, page.city].filter(Boolean).join(', ')
  return (
    <article className="stylist-page">
      {first && (
        <div className="frame sp-hero">
          <img src={first.url} alt={`${name}'s work, photo 1`} referrerPolicy="no-referrer" />
        </div>
      )}
      <div className="pf-title">
        {where && <p className="eyebrow">{where}</p>}
        <h1>{name}</h1>
        {page.specialties.length > 0 && <p className="muted small">{page.specialties.map((s) => SERVICE_LABEL[s]).join(' · ')}</p>}
        {page.bio && <p className="sp-bio">{page.bio}</p>}
        <NewClientsLine newClients={page.practice.newClients} />
      </div>
      <PracticeBlock name={name} practice={page.practice} />
      <FirstVisitBlock name={name} firstVisit={page.practice.firstVisit} />
      <PhotoGroups name={name} groups={page.photoGroups} skipFirst />
      <div className="stickybook">
        <div className="actions">
          <a
            className="button accent"
            href={`${apiPath`/api/public/stylists/${page.orgId}/book`}?via=${via}`}
            target="_blank"
            rel="noreferrer noopener"
          >
            Book with {name}
          </a>
          {page.finderPath && (
            <Link className="button secondary" to={page.finderPath}>
              Not sure what to book?
            </Link>
          )}
          <InstagramLink handle={page.instagram} />
        </div>
        <small>You book on {name}'s own page. Combpatible never takes payments.</small>
      </div>
      <p className="sp-foot muted small">
        Looking for someone else? <Link to="/find">Get matched with a stylist</Link> by their work on hair like yours.
      </p>
    </article>
  )
}
