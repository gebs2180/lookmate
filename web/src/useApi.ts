import { useAuth0 } from '@auth0/auth0-react'
import { useCallback } from 'react'
import { ApiError, UNREACHABLE, problemError, reach, readBody } from './api'

/** Auth0 errors that mean the session is gone and only a new login helps. */
const LOGIN_ERRORS = new Set(['login_required', 'missing_refresh_token', 'invalid_grant', 'consent_required'])

/** Fetch wrapper (for signed-in pages: Auth0 lives here, so the client pages never load it) that adds the access token and turns problem-detail responses into ApiError; a lost session goes back to login. */
export function useApi() {
  const { getAccessTokenSilently, loginWithRedirect } = useAuth0()

  return useCallback(
    async <T,>(method: string, path: string, body?: unknown): Promise<T> => {
      const relogin = async () => {
        // A backend that rejects every token (no issuer configured, wrong audience) would loop through
        // login forever: redirect at most once a minute, otherwise just report the 401.
        const key = 'lookmate.reloginAt'
        let last = 0
        try {
          last = Number(sessionStorage.getItem(key) ?? 0)
          sessionStorage.setItem(key, String(Date.now()))
        } catch {
          // storage blocked: redirect anyway
        }
        if (Date.now() - last < 60_000) {
          throw new ApiError(401, 'Not signed in: the API rejected your login. Check the API issuer and audience settings.')
        }
        await loginWithRedirect({ appState: { returnTo: window.location.pathname + window.location.search } })
      }
      const token = await getAccessTokenSilently().catch(async (e: unknown) => {
        if (LOGIN_ERRORS.has((e as { error?: string }).error ?? '')) {
          await relogin()
          throw new ApiError(401, 'Your session has ended. Redirecting to log in…')
        }
        throw e
      })
      const response = await reach(
        () =>
          fetch(path, {
            method,
            headers: {
              Authorization: `Bearer ${token}`,
              ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
            },
            body: body === undefined ? undefined : JSON.stringify(body),
          }),
        UNREACHABLE,
      )
      if (response.status === 401) {
        await relogin()
        throw new ApiError(401, 'Your session has ended. Redirecting to log in…')
      }
      if (!response.ok) {
        const fallback =
          response.status === 403
            ? "You don't have access to this page."
            : response.status === 404
              ? "We couldn't find that."
              : `${method} ${path} failed: ${response.status}`
        throw await problemError(response, fallback)
      }
      return readBody<T>(response)
    },
    [getAccessTokenSilently, loginWithRedirect],
  )
}
