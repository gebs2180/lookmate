import { Link } from 'react-router'
import { useApi, type Me } from '../api'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/**
 * Signed-in landing page. Scaffold only: shows who you are, so the Auth0 wiring can be checked. Stylists get their
 * profile and portfolio here, the admin the review queue (CLAUDE.md, Phase 1).
 */
export function HomePage() {
  const api = useApi()
  const { data: me, error } = useLoad(() => api<Me>('GET', '/api/me'), [api])

  if (error) return <PageError message={error} />
  if (!me) return <Loading />

  return (
    <>
      <h1>Welcome</h1>
      <p>Signed in as {me.roles.length ? me.roles.join(', ').toLowerCase() : 'a new user'}.</p>
      {me.memberships.length > 0 && (
        <ul>
          {me.memberships.map((m) => (
            <li key={m.orgId}>{m.orgType === 'STYLIST' ? <Link to={`/stylist/${m.orgId}`}>{m.orgName}</Link> : m.orgName}</li>
          ))}
        </ul>
      )}
      {me.roles.includes('ADMIN') && (
        <ul>
          <li>
            <Link to="/admin/stylists">Stylists and invites</Link>
          </li>
          <li>
            <Link to="/admin/locations">Salon locations</Link>
          </li>
        </ul>
      )}
      {me.roles.length === 0 && <p>Open the invite link you were sent to join as a stylist.</p>}
    </>
  )
}
