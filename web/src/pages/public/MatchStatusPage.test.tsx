import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { ClientView } from '../../api'
import { MatchStatusPage } from './MatchStatusPage'

const TOKEN = 'b'.repeat(43)

const BASE: ClientView = {
  stage: 'RECEIVED',
  firstName: 'Ada',
  service: 'CUT',
  hairType: 'CURLY',
  texture: 'THICK',
  length: null,
  createdAt: '2026-11-20T12:00:00Z',
  photoUploaded: true,
  photoDeleted: false,
  photoDeletesAt: '2026-12-20T12:00:00Z',
  photoKeptDays: 21,
  photoUrl: 'https://storage.test/view',
  matches: [],
}

function show(status: number, body: unknown) {
  const fetch = vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  render(
    <MemoryRouter initialEntries={[`/m/${TOKEN}`]}>
      <Routes>
        <Route path="/m/:token" element={<MatchStatusPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return fetch
}

describe('MatchStatusPage', () => {
  it('shows where the request is and when the photo goes', async () => {
    const fetch = show(200, BASE)
    expect(await screen.findByRole('heading', { name: "Ada, here's your request" })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(`/api/public/match-requests/${TOKEN}`, expect.objectContaining({ method: 'GET' }))
    expect(screen.getByText('Request received').closest('li')).toHaveAttribute('aria-current', 'step')
    expect(screen.getByText(/We delete it on/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Your inspiration photo' })).toHaveAttribute('referrerpolicy', 'no-referrer')
    expect(document.querySelector('meta[name="referrer"]')).toHaveAttribute('content', 'no-referrer')
  })

  it('lists the matches once ready, as suggestions, with tracked book links that send no referrer', async () => {
    const fetch = show(200, {
      ...BASE,
      stage: 'READY',
      matches: [
        {
          matchId: 'm1',
          rank: 1,
          stylistName: 'Curl Studio',
          locationName: 'Short North',
          reason: '12 similar cuts on curly hair',
          photoUrls: ['https://storage.test/p1'],
          instagram: 'curlstudio',
          orgId: 'org-1',
          demo: true,
        },
      ],
    })
    expect(await screen.findByText('12 similar cuts on curly hair')).toBeInTheDocument()
    expect(screen.getByText(/you can book any stylist you like/)).toBeInTheDocument()
    const book = screen.getByRole('link', { name: 'Book with Curl Studio' })
    // The tracking redirect, built from the client's own token: it records the click, then goes to the stylist.
    expect(book).toHaveAttribute('href', `/api/public/book/${TOKEN}/m1`)
    expect(book.getAttribute('rel')).toContain('noreferrer')
    // A made-up stylist is labelled, and every other stylist is one link away.
    expect(screen.getByText('Sample stylist')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See all stylists' })).toHaveAttribute('href', `/m/${TOKEN}/stylists`)
    // Seeing the list counts as a view for the pilot metrics.
    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(`/api/public/match-requests/${TOKEN}/viewed`, expect.objectContaining({ method: 'POST' })),
    )
  })

  it('asks for the photo when it never arrived', async () => {
    show(200, { ...BASE, stage: 'PHOTO_NEEDED', photoUploaded: false, photoUrl: null, photoDeletesAt: null })
    expect(await screen.findByRole('heading', { name: 'Add your inspiration photo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send photo' })).toBeDisabled()
    expect(screen.getByText(/we delete it 21 days after your request/)).toBeInTheDocument()
  })

  it('says the photo is gone once retention deleted it, with the real period', async () => {
    show(200, { ...BASE, stage: 'MATCHING', photoDeleted: true, photoUrl: null, photoDeletesAt: null })
    expect(await screen.findByText('Deleted, as promised: we keep inspiration photos for 21 days.')).toBeInTheDocument()
  })

  it('treats a wrong or expired link as not found', async () => {
    show(404, { detail: "This link doesn't work. It may have expired.", code: 'LINK_NOT_FOUND' })
    expect(await screen.findByRole('heading', { name: "This link doesn't work" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Start a new request' })).toHaveAttribute('href', '/find')
  })

  it('checks back by itself while matching, gently, and shows the list in place once published', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const ready = {
      ...BASE,
      stage: 'READY',
      matches: [
        {
          matchId: 'm1',
          orgId: 'org-1',
          rank: 1,
          stylistName: 'Curl Studio',
          locationName: null,
          reason: '12 similar cuts on curly hair',
          photoUrls: [],
          instagram: null,
          demo: false,
        },
      ],
    }
    const bodies = [BASE, { ...BASE, stage: 'MATCHING' }, ready]
    const fetch = vi.fn(async (url: string) => {
      const body = url.endsWith('/viewed') ? '' : JSON.stringify(bodies.length > 1 ? bodies.shift() : bodies[0])
      return new Response(body, { status: url.endsWith('/viewed') ? 204 : 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetch)
    render(
      <MemoryRouter initialEntries={[`/m/${TOKEN}`]}>
        <Routes>
          <Route path="/m/:token" element={<MatchStatusPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText(/This page updates by itself/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'browse all stylists' })).toHaveAttribute('href', `/m/${TOKEN}/stylists`)
    const gets = () => fetch.mock.calls.filter(([url]) => url === `/api/public/match-requests/${TOKEN}`).length
    expect(gets()).toBe(1)

    await act(() => vi.advanceTimersByTimeAsync(3000)) // first check: still matching
    expect(gets()).toBe(2)
    await act(() => vi.advanceTimersByTimeAsync(3000)) // the next wait is longer (4.5s): nothing yet
    expect(gets()).toBe(2)
    await act(() => vi.advanceTimersByTimeAsync(1500)) // published: the list shows in place
    expect(gets()).toBe(3)
    expect(await screen.findByRole('link', { name: 'Book with Curl Studio' })).toBeInTheDocument()
    expect(screen.queryByText(/This page updates by itself/)).not.toBeInTheDocument()

    await act(() => vi.advanceTimersByTimeAsync(120_000)) // ready: no more polling
    expect(gets()).toBe(3)
    vi.useRealTimers()
  })
})
