import { lazy, Suspense } from 'react'
import { useLocation } from 'react-router'
import { PublicShell } from './pages/public/PublicShell'
import { isPublicPath } from './pages/public/publicPaths'
import { Loading } from './ui'
import { useRouteFocus } from './useRouteFocus'

const SignedInApp = lazy(() => import('./SignedInApp'))

/**
 * Clients never sign in (CLAUDE.md, decisions): their pages render outside the signed-in shell, and Auth0 isn't even
 * loaded for them. Stylists and the admin get Auth0 around everything else.
 */
function App() {
  const location = useLocation()
  useRouteFocus()
  if (isPublicPath(location.pathname)) return <PublicShell />
  return (
    <Suspense fallback={<Loading />}>
      <SignedInApp />
    </Suspense>
  )
}

export default App
