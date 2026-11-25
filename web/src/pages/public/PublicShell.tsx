import { lazy, Suspense } from 'react'
import { Link, Route, Routes, useLocation } from 'react-router'
import { BrandMark } from '../../strand/Strand'
import { Loading, NotFound, RouteErrorBoundary } from '../../ui'
import { DigestUnsubscribePage } from './DigestUnsubscribePage'
import { DirectoryPage } from './DirectoryPage'
import { FindPage } from './FindPage'
import { MatchStatusPage } from './MatchStatusPage'

// The service finder is its own module (consult), loaded only when a client opens it.
const GeneralConsultPage = lazy(() => import('../../consult/ConsultPage').then((m) => ({ default: m.GeneralConsultPage })))
const PartnerConsultPage = lazy(() => import('../../consult/ConsultPage').then((m) => ({ default: m.PartnerConsultPage })))

/** Clients never sign in (CLAUDE.md): their pages render outside the signed-in shell, with no login at all. */
export function PublicShell() {
  const location = useLocation()
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <BrandMark />
          Combpatible
        </Link>
        <span className="topbar-note">Columbus</span>
      </header>
      {/* Keyed by page, so each new page lifts in over the last like a turned magazine page (lookbook.css). */}
      <main className="page-lift" key={location.pathname}>
        <RouteErrorBoundary key={location.pathname}>
          <Suspense fallback={<Loading />}>
            <Routes>
              <Route path="/find" element={<FindPage />} />
              <Route path="/m/:token" element={<MatchStatusPage />} />
              <Route path="/m/:token/stylists" element={<DirectoryPage />} />
              <Route path="/stylists" element={<DirectoryPage />} />
              <Route path="/consult" element={<GeneralConsultPage />} />
              <Route path="/c/:slug" element={<PartnerConsultPage />} />
              <Route path="/digest/unsubscribe/:token" element={<DigestUnsubscribePage />} />
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Suspense>
        </RouteErrorBoundary>
      </main>
    </div>
  )
}
