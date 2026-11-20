import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { vi } from 'vitest'
import { respond } from '../test/auth0'

export type Call = { method: string; url: string; body: unknown; headers: Record<string, string> }

/**
 * Stubs fetch with a handler per "METHOD /path" (the path without the query, or a full URL for storage) and records
 * every call. An unknown route answers 404.
 */
export function stubApi(routes: Record<string, (body: unknown) => Response | unknown>) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      const raw = init?.body
      const body = typeof raw === 'string' ? JSON.parse(raw) : raw
      calls.push({ method, url: input, body, headers: (init?.headers ?? {}) as Record<string, string> })
      const handler = routes[`${method} ${input}`] ?? routes[`${method} ${input.split('?')[0]}`]
      if (!handler) return respond(404, { detail: `No stub for ${method} ${input}` })
      const out = handler(body)
      return out instanceof Response ? out : respond(200, out)
    }),
  )
  return calls
}

export function renderAt(path: string, pattern: string, element: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={pattern} element={element} />
        <Route path="*" element={<p>Elsewhere</p>} />
      </Routes>
    </MemoryRouter>,
  )
}
