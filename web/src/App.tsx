import { useAuth0 } from '@auth0/auth0-react'
import { lazy, Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { PublicShell } from './pages/public/PublicShell'
import { isPublicPath } from './pages/public/publicPaths'
import { FairnessPage } from './pages/admin/FairnessPage'
import { MatchMonitorPage } from './pages/admin/MatchMonitorPage'
import { MatchReviewPage } from './pages/admin/MatchReviewPage'
import { AdminMetricsPage } from './pages/tracking/AdminMetricsPage'
import { RecordBookingPage, StylistBookingsPage } from './pages/tracking/RecordBookingPage'
import { StylistStatsPage } from './pages/tracking/StylistStatsCard'
import { Loading, NotFound, RouteErrorBoundary } from './ui'
import { useRouteFocus } from './useRouteFocus'

// Stylists module (docs/modules/stylists.md)
const StylistHomePage = lazy(() => import('./stylists/StylistHomePage').then((m) => ({ default: m.StylistHomePage })))
const ProfilePage = lazy(() => import('./stylists/ProfilePage').then((m) => ({ default: m.ProfilePage })))
const PortfolioPage = lazy(() => import('./stylists/PortfolioPage').then((m) => ({ default: m.PortfolioPage })))
const AdminStylistsPage = lazy(() => import('./admin/AdminStylistsPage').then((m) => ({ default: m.AdminStylistsPage })))
const AdminDemoPage = lazy(() => import('./admin/AdminDemoPage').then((m) => ({ default: m.AdminDemoPage })))
const AdminLocationsPage = lazy(() => import('./admin/AdminLocationsPage').then((m) => ({ default: m.AdminLocationsPage })))

/**
 * Stylists and the admin sign in (Auth0). Clients never do: their intake and match pages will live under
 * /find and /m/:token, outside the signed-in shell (CLAUDE.md, decisions).
 */
function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()
  useRouteFocus()
  if (isPublicPath(location.pathname)) return <PublicShell />

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          Combpatible
        </Link>
        {isAuthenticated && (
          <span className="who">
            <span className="email">{user?.email ?? user?.name}</span>{' '}
            <button className="link" onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>
              Log out
            </button>
          </span>
        )}
      </header>

      {error && <p className="error" role="alert">Login error: {error.message}</p>}
      {isLoading && <Loading />}

      {!isLoading && !isAuthenticated && (
        <main className="welcome">
          <p className="lead">Find the stylist whose work matches the look you want.</p>
          {!location.pathname.startsWith('/invite/') && (
            <>
              <Link className="button block" to="/find">
                Find my stylist
              </Link>
              <Link className="button secondary block" to="/stylists">
                See all stylists
              </Link>
            </>
          )}
          {location.pathname.startsWith('/invite/') && (
            <p>You've been invited to join Combpatible as a stylist. Log in, or sign up, to accept.</p>
          )}
          <button className="block" onClick={() => loginWithRedirect({ appState: { returnTo: location.pathname + location.search } })}>
            {location.pathname.startsWith('/invite/') ? 'Log in or sign up' : 'Stylist log in'}
          </button>
        </main>
      )}

      {isAuthenticated && (
        <main>
          <RouteErrorBoundary key={location.pathname}>
            <Suspense fallback={<Loading />}>
              <Routes>
                <Route path="/" element={<HomePage />} />
                <Route path="/invite/:token" element={<InvitePage />} />
                <Route path="/stylist/:orgId" element={<StylistHomePage />} />
                <Route path="/stylist/:orgId/profile" element={<ProfilePage />} />
                <Route path="/stylist/:orgId/portfolio" element={<PortfolioPage />} />
                <Route path="/admin/stylists" element={<AdminStylistsPage />} />
                <Route path="/admin/locations" element={<AdminLocationsPage />} />
                <Route path="/admin/demo" element={<AdminDemoPage />} />
                <Route path="/admin/matches" element={<MatchMonitorPage />} />
                <Route path="/admin/matches/fairness" element={<FairnessPage />} />
                <Route path="/admin/matches/:id" element={<MatchReviewPage />} />
                <Route path="/admin/metrics" element={<AdminMetricsPage />} />
                <Route path="/admin/bookings" element={<RecordBookingPage />} />
                <Route path="/orgs/:orgId/stats" element={<StylistStatsPage />} />
                <Route path="/orgs/:orgId/bookings" element={<StylistBookingsPage />} />
                <Route path="*" element={<NotFound />} />
              </Routes>
            </Suspense>
          </RouteErrorBoundary>
        </main>
      )}
    </div>
  )
}

export default App
