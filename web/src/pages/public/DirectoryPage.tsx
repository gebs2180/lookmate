import { Link, useParams, useSearchParams } from 'react-router'
import { apiPath, publicApi, type ClientView, type DirectoryPage as Page } from '../../api'
import { Chip, EmptyState, Loading, PageError } from '../../ui'
import { useLoad } from '../../useLoad'
import { StylistCard } from './StylistCard'
import { HAIR_TYPES, SERVICES, labelOf } from './quiz'

/**
 * Every live stylist, for clients to pick from themselves (recommendations are suggestions, CLAUDE.md). Reached from
 * the client's own page (/m/:token/stylists), where Book goes through tracking with their token, or signed out from
 * /find (/stylists), where Book needs a request first: the booking link is never sent to the browser untracked.
 */
export function DirectoryPage() {
  const { token } = useParams()
  const [params, setParams] = useSearchParams()
  const service = params.get('service') ?? ''
  const hairType = params.get('hairType') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)

  const { data, error } = useLoad(() => {
    const query = new URLSearchParams()
    if (service) query.set('service', service)
    if (hairType) query.set('hairType', hairType)
    if (page > 0) query.set('page', String(page))
    const q = query.toString()
    // With the client's link, the directory is scoped to their request (its market); signed out, the open listing.
    const base = token ? apiPath`/api/public/match-requests/${token}/stylists` : '/api/public/stylists'
    return publicApi<Page>('GET', `${base}${q ? `?${q}` : ''}`)
  }, [token, service, hairType, page])

  // On the client's own page, mark the stylists they were recommended.
  const { data: mine } = useLoad(
    () => (token ? publicApi<ClientView>('GET', apiPath`/api/public/match-requests/${token}`) : Promise.resolve(null)),
    [token],
  )
  const recommended = new Set((mine?.matches ?? []).map((m) => m.orgId))

  function filter(key: 'service' | 'hairType', value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('page')
    setParams(next)
  }

  function goTo(p: number) {
    const next = new URLSearchParams(params)
    if (p > 0) next.set('page', String(p))
    else next.delete('page')
    setParams(next)
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1
  return (
    <section className="find directory">
      {token ? (
        <p className="back">
          <Link to={`/m/${encodeURIComponent(token)}`}>Back to your matches</Link>
        </p>
      ) : (
        <p className="back">
          <Link to="/find">Get matched instead</Link>
        </p>
      )}
      <p className="eyebrow">The index</p>
      <h1>All stylists</h1>
      <p className="lede">
        {token
          ? 'Your matches are our suggestions. You can book any stylist here instead.'
          : 'Browse every stylist. To book, start a request: it takes two minutes, and we match you with stylists whose work looks like the style you want.'}
      </p>
      <Filters service={service} hairType={hairType} onChange={filter} />

      {error && <PageError inline message={error} />}
      {!error && !data && <Loading />}
      {data && data.stylists.length === 0 && (
        <EmptyState title="No stylists match these filters yet.">
          <p>Try another service or hair type.</p>
        </EmptyState>
      )}
      {data && data.stylists.length > 0 && (
        <>
          <p className="muted small" role="status">
            {data.total} {data.total === 1 ? 'stylist' : 'stylists'}
            {pages > 1 ? `, page ${data.page + 1} of ${pages}` : ''}
          </p>
          <ul className="index">
            {data.stylists.map((s, i) => (
              <StylistCard
                key={s.orgId}
                index={i}
                name={s.displayName}
                location={[s.locationName, s.city].filter(Boolean).join(', ') || null}
                demo={s.demo}
                badge={recommended.has(s.orgId) ? <Chip tone="success">On your list</Chip> : undefined}
                specialties={s.specialties.map((v) => labelOf(SERVICES, v) ?? v)}
                photoUrls={s.photoUrls}
                instagram={s.instagram}
                practice={s.practice}
                book={
                  token
                    ? { href: apiPath`/api/public/book-stylist/${token}/${s.orgId}` }
                    : {
                        instead: (
                          <Link className="button" to="/find">
                            Start a request to book
                          </Link>
                        ),
                      }
                }
              />
            ))}
          </ul>
          {pages > 1 && (
            <nav className="actions" aria-label="Pages">
              <button className="secondary" onClick={() => goTo(data.page - 1)} disabled={data.page === 0}>
                Previous
              </button>
              <button className="secondary" onClick={() => goTo(data.page + 1)} disabled={data.page + 1 >= pages}>
                Next
              </button>
            </nav>
          )}
        </>
      )}
    </section>
  )
}

function Filters({
  service,
  hairType,
  onChange,
}: {
  service: string
  hairType: string
  onChange: (key: 'service' | 'hairType', value: string) => void
}) {
  return (
    <div className="filters" role="group" aria-label="Filters">
      <label>
        Service
        <select value={service} onChange={(e) => onChange('service', e.target.value)}>
          <option value="">Any service</option>
          {SERVICES.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Hair type
        <select value={hairType} onChange={(e) => onChange('hairType', e.target.value)}>
          <option value="">Any hair type</option>
          {HAIR_TYPES.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}
