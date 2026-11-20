import { Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { Loading, NotFound, RouteErrorBoundary } from '../../ui'
import { DirectoryPage } from './DirectoryPage'
import { FindPage } from './FindPage'
import { MatchStatusPage } from './MatchStatusPage'

/** Clients never sign in (CLAUDE.md): their pages render outside the signed-in shell, with no login at all. */
export function PublicShell() {
  const location = useLocation()
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/find" className="brand">
          Combpatible
        </Link>
      </header>
      <main>
        <RouteErrorBoundary key={location.pathname}>
          <Suspense fallback={<Loading />}>
            <Routes>
              <Route path="/find" element={<FindPage />} />
              <Route path="/m/:token" element={<MatchStatusPage />} />
              <Route path="/m/:token/stylists" element={<DirectoryPage />} />
              <Route path="/stylists" element={<DirectoryPage />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Suspense>
        </RouteErrorBoundary>
      </main>
    </div>
  )
}
