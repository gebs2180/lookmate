import { useAuth0 } from '@auth0/auth0-react'
import { lazy, Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { Landing } from './pages/public/Landing'
import { BrandMark } from './strand/Strand'
import { AuthProvider } from './AuthProvider'
import { Loading, NotFound, RouteErrorBoundary } from './ui'

// Stylists module (docs/modules/stylists.md)
const StylistHomePage = lazy(() => import('./stylists/StylistHomePage').then((m) => ({ default: m.StylistHomePage })))
const ProfilePage = lazy(() => import('./stylists/ProfilePage').then((m) => ({ default: m.ProfilePage })))
const PortfolioPage = lazy(() => import('./stylists/PortfolioPage').then((m) => ({ default: m.PortfolioPage })))
const AdminStylistsPage = lazy(() => import('./admin/AdminStylistsPage').then((m) => ({ default: m.AdminStylistsPage })))
const AdminDemoPage = lazy(() => import('./admin/AdminDemoPage').then((m) => ({ default: m.AdminDemoPage })))
const FairnessPage = lazy(() => import('./pages/admin/FairnessPage').then((m) => ({ default: m.FairnessPage })))
const MatchMonitorPage = lazy(() => import('./pages/admin/MatchMonitorPage').then((m) => ({ default: m.MatchMonitorPage })))
const MatchReviewPage = lazy(() => import('./pages/admin/MatchReviewPage').then((m) => ({ default: m.MatchReviewPage })))
const AdminMetricsPage = lazy(() => import('./pages/tracking/AdminMetricsPage').then((m) => ({ default: m.AdminMetricsPage })))
const RecordBookingPage = lazy(() => import('./pages/tracking/RecordBookingPage').then((m) => ({ default: m.RecordBookingPage })))
const StylistBookingsPage = lazy(() => import('./pages/tracking/RecordBookingPage').then((m) => ({ default: m.StylistBookingsPage })))
const StylistStatsPage = lazy(() => import('./pages/tracking/StylistStatsCard').then((m) => ({ default: m.StylistStatsPage })))
const AdminLocationsPage = lazy(() => import('./admin/AdminLocationsPage').then((m) => ({ default: m.AdminLocationsPage })))

/** Everything behind sign-in, with Auth0 around it. Its own chunk, so client pages never download Auth0. */
export default function SignedInApp() {
  return (
    <AuthProvider>
      <SignedInShell />
    </AuthProvider>
  )
}

function SignedInShell() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()
  const invite = location.pathname.startsWith('/invite/')
  const login = () => loginWithRedirect({ appState: { returnTo: location.pathname + location.search } })

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <BrandMark />
          Combpatible
        </Link>
        {!isAuthenticated && <span className="topbar-note">Columbus</span>}
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

      {!isLoading && !isAuthenticated && !invite && <Landing key="landing" onStylistLogin={login} />}

      {!isLoading && !isAuthenticated && invite && (
        <main className="welcome page-lift">
          <p className="eyebrow">For stylists</p>
          <p className="lead">You've been invited to join Combpatible as a stylist.</p>
          <p>Log in, or sign up, to accept. Clients find you by your own work, so your portfolio does the talking.</p>
          <button className="block" onClick={login}>
            Log in or sign up
          </button>
        </main>
      )}

      {isAuthenticated && (
        <main className="page-lift" key={location.pathname}>
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
