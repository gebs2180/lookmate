import { Link } from 'react-router'
import { type Me } from '../api'
import { useApi } from '../useApi'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'

/**
 * Signed-in landing page: who you are, and the way on. Stylists get their profile and portfolio, the admin the matches
 * monitor.
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
          <li>
            <Link to="/admin/matches">Matches and fairness</Link>
          </li>
          <li>
            <Link to="/admin/metrics">Pilot metrics</Link>
          </li>
          <li>
            <Link to="/admin/bookings">Record a booking</Link>
          </li>
        </ul>
      )}
      {me.roles.length === 0 && <p>Open the invite link you were sent to join as a stylist.</p>}
    </>
  )
}
