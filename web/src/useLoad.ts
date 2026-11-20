import { useCallback, useEffect, useState } from 'react'
import { message } from './format'

/** Runs an async loader on mount and whenever its dependencies change; reload() runs it again. */
export function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(loader, deps)

  // New deps mean a different thing is loading: drop the old one's data and error so they're never shown for it.
  const [loaded, setLoaded] = useState(() => load)
  if (loaded !== load) {
    setLoaded(() => load)
    setData(null)
    setError(null)
  }

  useEffect(() => {
    let cancelled = false
    load()
      .then((value) => {
        if (!cancelled) setData(value)
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(message(e))
      })
    return () => {
      cancelled = true
    }
  }, [load, version])

  const reload = useCallback(() => {
    setError(null)
    setVersion((v) => v + 1)
  }, [])
  return { data, error, reload }
}

/**
 * Gentle backoff for {@link usePoll}: the interval stays steady for {@code steadyMs}, then each wait is {@code factor}
 * times the last, up to {@code maxMs}; polling stops after {@code giveUpMs}.
 */
export type Backoff = { factor: number; maxMs: number; steadyMs?: number; giveUpMs?: number }

/**
 * Calls {@code reload} every {@code ms} while {@code active}, e.g. while background work is pending. With a
 * {@code backoff}, the waits grow after a steady start (up to maxMs) so a page left open doesn't keep hammering the API,
 * and polling stops after giveUpMs; it starts over when {@code active} turns true again.
 */
export function usePoll(active: boolean, reload: () => void, ms = 2000, backoff?: Backoff) {
  const factor = backoff?.factor ?? 1
  const maxMs = backoff?.maxMs ?? ms
  const giveUpMs = backoff?.giveUpMs
  const steadyMs = backoff?.steadyMs ?? 0
  useEffect(() => {
    if (!active) return
    let wait = ms
    let waited = 0
    let timer: ReturnType<typeof setTimeout>
    const next = () => {
      if (giveUpMs !== undefined && waited + wait > giveUpMs) return
      timer = setTimeout(() => {
        waited += wait
        reload()
        if (waited >= steadyMs) wait = Math.min(maxMs, Math.round(wait * factor))
        next()
      }, wait)
    }
    next()
    return () => clearTimeout(timer)
  }, [active, reload, ms, factor, maxMs, steadyMs, giveUpMs])
}
