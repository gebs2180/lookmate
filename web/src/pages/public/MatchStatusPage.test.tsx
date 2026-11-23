import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import userEvent from '@testing-library/user-event'
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
  emailHint: null,
  emailChangeAllowed: true,
  retakeAllowed: false,
  asks: [],
  matches: [],
}

const MATCH = {
  matchId: 'm1',
  orgId: 'org-1',
  rank: 1,
  stylistName: 'Curl Studio',
  locationName: null,
  reason: '12 similar cuts on curly hair',
  photoUrls: [],
  instagram: null,
  demo: false,
}

/** The page's GET answers {@code body}; the email POST answers {@code saved} (or an error). */
function showWithEmail(body: ClientView, saved: () => Response) {
  const fetch = vi.fn(async (url: string) => {
    if (url.endsWith('/email')) return saved()
    if (url.endsWith('/viewed')) return new Response(null, { status: 204 })
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
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

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
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
  it('moves between the picks with arrows and dots, not only by swiping', async () => {
    const two = [MATCH, { ...MATCH, matchId: 'm2', orgId: 'org-2', rank: 2, stylistName: 'Coil Room' }]
    showWithEmail({ ...BASE, stage: 'READY', matches: two } as unknown as ClientView, () => new Response(null, { status: 204 }))
    const user = userEvent.setup()

    const previous = await screen.findByRole('button', { name: 'Previous stylist' })
    const next = screen.getByRole('button', { name: 'Next stylist' })
    expect(previous).toBeDisabled()
    await user.click(next)
    expect(next).toBeDisabled()
    expect(previous).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Stylist 2: Coil Room' })).toHaveAttribute('aria-current', 'true')
    await user.click(screen.getByRole('button', { name: 'Stylist 1: Curl Studio' }))
    expect(previous).toBeDisabled()
  })

  it('offers a different photo when the first could not be read', async () => {
    show(200, { ...BASE, stage: 'FAILED' })
    expect(await screen.findByRole('heading', { name: /couldn't read your photo/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /browse all stylists/i })).toBeInTheDocument()
    expect(screen.getByText('Add a different photo')).toBeInTheDocument()
  })

  it('shows where the request is and when the photo goes', async () => {
    const fetch = show(200, BASE)
    expect(await screen.findByRole('heading', { name: "Ada, here's your request" })).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(`/api/public/match-requests/${TOKEN}`, expect.objectContaining({ method: 'GET' }))
    expect(screen.getByText('Request received').closest('li')).toHaveAttribute('aria-current', 'step')
    expect(screen.getByText(/We delete it on/)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Your inspiration photo' })).toHaveAttribute('referrerpolicy', 'no-referrer')
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

  it('suggests a booking note with a copy button when the client asked for a quiet appointment or honest advice', async () => {
    const user = userEvent.setup()
    // After setup(), which installs its own clipboard.
    const writeText = vi.spyOn(navigator.clipboard, 'writeText')
    show(200, { ...BASE, stage: 'READY', asks: ['QUIET_APPOINTMENT', 'HONEST_ADVICE'], matches: [MATCH] })
    const note = "Quiet appointment, please. I'd like honest advice about what my hair can do."
    expect(await screen.findByText(note)).toBeInTheDocument()
    expect(screen.getByText(/add this to your booking notes/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Copy note' }))
    expect(writeText).toHaveBeenCalledWith(note)
    expect(await screen.findByText('Note copied.')).toBeInTheDocument()
    // Book is still right there.
    expect(screen.getByRole('link', { name: 'Book with Curl Studio' })).toBeInTheDocument()
  })

  it('suggests just the quiet line when only that was asked', async () => {
    show(200, { ...BASE, stage: 'READY', asks: ['QUIET_APPOINTMENT'], matches: [MATCH] })
    expect(await screen.findByText('Quiet appointment, please.')).toBeInTheDocument()
  })

  it('shows no booking note when the client asked for neither', async () => {
    show(200, { ...BASE, stage: 'READY', matches: [MATCH] })
    expect(await screen.findByText(MATCH.reason)).toBeInTheDocument()
    expect(screen.queryByText(/booking notes/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy note' })).not.toBeInTheDocument()
  })

  it('says plainly when no stylist was a close match, points at every stylist, and stops checking back', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fetch = show(200, { ...BASE, stage: 'READY', matches: [] })
    expect(await screen.findByRole('heading', { name: 'No close matches yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Browse all stylists' })).toHaveAttribute('href', `/m/${TOKEN}/stylists`)
    expect(screen.getByText(/Our team may add picks/)).toBeInTheDocument()
    // Not "your matches are ready", and nothing still pretending to match.
    expect(screen.queryByText('Your matches are ready')).not.toBeInTheDocument()
    expect(screen.queryByText(/This page updates by itself/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'See all stylists' })).not.toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(120_000))
    expect(fetch).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('says the page is the link, and emails the list only if the client asks', async () => {
    const ready = { ...BASE, stage: 'READY', matches: [MATCH] } as unknown as ClientView
    const fetch = showWithEmail(ready, () => jsonResponse(200, { ...ready, emailHint: 'a•••@example.com' }))
    const user = userEvent.setup()
    expect(await screen.findByRole('heading', { name: 'Email me this list' })).toBeInTheDocument()
    expect(screen.getByText(/Bookmark this page, or email it to yourself/)).toBeInTheDocument()
    expect(screen.getByText(/We may ask how your appointment went/)).toBeInTheDocument()
    const send = screen.getByRole('button', { name: 'Send me the link' })
    expect(send).toBeDisabled()

    await user.type(screen.getByLabelText('Email (optional)'), 'ada@example.com')
    await user.click(send)
    expect(fetch).toHaveBeenCalledWith(
      `/api/public/match-requests/${TOKEN}/email`,
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ email: 'ada@example.com' }) }),
    )
    // Set: the hint replaces the box, with a way to correct it.
    expect(await screen.findByText('a•••@example.com')).toBeInTheDocument()
    expect(screen.queryByLabelText('Email (optional)')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Use a different email' }))
    expect(screen.getByRole('button', { name: 'Send it here instead' })).toBeInTheDocument()
  })

  it('offers to say when picks are added to an empty list, and shows the email once given', async () => {
    showWithEmail({ ...BASE, stage: 'READY', matches: [], emailHint: 'a•••@example.com', emailChangeAllowed: false }, () =>
      jsonResponse(500, {}),
    )
    expect(await screen.findByRole('heading', { name: 'Tell me when picks are added' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent("We've emailed this list to a•••@example.com, and we'll write again if picks are added.")
    expect(screen.queryByLabelText('Email (optional)')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use a different email' })).not.toBeInTheDocument()
  })

  it("says why an email can't be changed again", async () => {
    showWithEmail({ ...BASE, stage: 'MATCHING' }, () =>
      jsonResponse(409, { detail: 'limit', code: 'EMAIL_CHANGE_LIMIT' }),
    )
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Email (optional)'), 'ada@example.com')
    await user.click(screen.getByRole('button', { name: 'Send me the link' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("can't be changed again")
  })

  it('asks for the photo when it never arrived', async () => {
    show(200, { ...BASE, stage: 'PHOTO_NEEDED', photoUploaded: false, photoUrl: null, photoDeletesAt: null })
    expect(await screen.findByRole('heading', { name: 'Add your inspiration photo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send photo' })).toBeDisabled()
    expect(screen.queryByLabelText('Email (optional)')).not.toBeInTheDocument()
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

  it('checks back every 1.5s while matching, and shows the list in place once published', async () => {
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

    await act(() => vi.advanceTimersByTimeAsync(1500)) // first check, after 1.5s: still matching
    expect(gets()).toBe(2)
    await act(() => vi.advanceTimersByTimeAsync(1000)) // the steady pace: nothing before 1.5s more
    expect(gets()).toBe(2)
    await act(() => vi.advanceTimersByTimeAsync(500)) // published: the list shows in place
    expect(gets()).toBe(3)
    expect(await screen.findByRole('link', { name: 'Book with Curl Studio' })).toBeInTheDocument()
    expect(screen.queryByText(/This page updates by itself/)).not.toBeInTheDocument()

    await act(() => vi.advanceTimersByTimeAsync(120_000)) // ready: no more polling
    expect(gets()).toBe(3)
    vi.useRealTimers()
  })
})

describe('MatchStatusPage straight from the quiz', () => {
  it('plays the haircut even when matching finished before the first check, then shows the picks', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url.endsWith('/viewed')
          ? new Response(null, { status: 204 })
          : new Response(JSON.stringify({ ...BASE, stage: 'READY', matches: [MATCH] }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
      ),
    )
    const { container } = render(
      <MemoryRouter initialEntries={[{ pathname: `/m/${TOKEN}`, state: { fromQuiz: true } }]}>
        <Routes>
          <Route path="/m/:token" element={<MatchStatusPage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('Your matches are ready.')).toBeInTheDocument()
    expect(container.querySelector('.haircut')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Book with Curl Studio' })).not.toBeInTheDocument()
    // No IntersectionObserver in tests, so nobody "sees" it: the picks follow without the finish.
    expect(await screen.findByRole('link', { name: 'Book with Curl Studio' })).toBeInTheDocument()
    expect(container.querySelector('.haircut')).not.toBeInTheDocument()
  })
})
