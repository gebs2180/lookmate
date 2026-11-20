import { render, screen } from '@testing-library/react'
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

  it('lists the matches once ready, as suggestions, with book links that send no referrer', async () => {
    show(200, {
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
          bookUrl: '/api/public/book/m1',
          instagram: 'curlstudio',
        },
      ],
    })
    expect(await screen.findByText('12 similar cuts on curly hair')).toBeInTheDocument()
    expect(screen.getByText(/you can book any stylist you like/)).toBeInTheDocument()
    const book = screen.getByRole('link', { name: 'Book with Curl Studio' })
    expect(book).toHaveAttribute('href', '/api/public/book/m1')
    expect(book.getAttribute('rel')).toContain('noreferrer')
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
})
