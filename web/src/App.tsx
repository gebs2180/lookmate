import { useAuth0 } from '@auth0/auth0-react'
import { Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { HomePage } from './pages/HomePage'
import { InvitePage } from './pages/InvitePage'
import { Loading, NotFound, RouteErrorBoundary } from './ui'
import { useRouteFocus } from './useRouteFocus'

/**
 * Stylists and the admin sign in (Auth0). Clients never do: their intake and match pages will live under
 * /find and /m/:token, outside the signed-in shell (CLAUDE.md, decisions).
 */
function App() {
  const { isLoading, isAuthenticated, user, error, loginWithRedirect, logout } = useAuth0()
  const location = useLocation()
  useRouteFocus()

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          LookMate
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
          {location.pathname.startsWith('/invite/') && (
            <p>You've been invited to join LookMate as a stylist. Log in, or sign up, to accept.</p>
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
