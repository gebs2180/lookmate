import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { MatchReview, MatchView } from '../../api'
import { respond } from '../../test/auth0'
import { MatchReviewPage } from './MatchReviewPage'

function match(id: string, name: string, rank: number, extra: Partial<MatchView> = {}): MatchView {
  return {
    id,
    orgId: `org-${id}`,
    stylistName: name,
    locationName: 'Short North',
    instagram: null,
    stylistLive: true,
    score: 0.8,
    reason: '3 similar cuts on wavy hair',
    systemRank: rank,
    finalRank: rank,
    hidden: false,
    adminOverride: false,
    adminNote: null,
    source: 'SYSTEM',
    evidence: [{ id: `p-${id}`, url: `https://photos.example/${id}.jpg`, service: 'CUT', hairType: 'WAVY' }],
    ...extra,
  }
}

function review(matches: MatchView[], status: MatchReview['request']['status'] = 'IN_REVIEW'): MatchReview {
  return {
    request: {
      id: 'req-1',
      status,
      statusReason: null,
      market: 'columbus-oh',
      service: 'CUT',
      hairType: 'WAVY',
      texture: 'THICK',
      length: 'MEDIUM',
      budgetTier: 2,
      neighborhood: 'Clintonville',
      pastIssues: ['DIDNT_LISTEN', 'QUIET_APPOINTMENT'],
      notes: null,
      firstName: 'Sam',
      createdAt: '2026-11-19T12:00:00Z',
      publishedAt: null,
      inspirationDeleted: false,
    },
    inspirationUrl: 'https://photos.example/look.jpg',
    matches,
  }
}

function show() {
  render(
    <MemoryRouter initialEntries={['/admin/matches/req-1']}>
      <Routes>
        <Route path="/admin/matches/:id" element={<MatchReviewPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('MatchReviewPage', () => {
  it('shows the look, the ranked stylists and their evidence photos', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(200, review([match('a', 'Ana', 1), match('b', 'Bo', 2)]))))
    show()
    expect(await screen.findByRole('heading', { name: 'Sam: cut' })).toBeInTheDocument()
    expect(screen.getByAltText("The client's inspiration photo")).toHaveAttribute('src', 'https://photos.example/look.jpg')
    // The asks are shown apart from what went wrong.
    expect(screen.getByText('Went wrong before').nextElementSibling).toHaveTextContent(/^Didnt listen$/i)
    expect(screen.getByText('Asked for').nextElementSibling).toHaveTextContent('A quiet appointment')
    const items = screen.getAllByRole('listitem')
    expect(within(items[0]).getByRole('heading', { name: 'Ana' })).toBeInTheDocument()
    expect(within(items[0]).getByAltText("Ana's portfolio photo: cut on wavy")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Move Ana up' })).toBeDisabled()
  })

  it('moves a stylist up by sending the whole new order', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(200, review([match('a', 'Ana', 1), match('b', 'Bo', 2)])))
      .mockResolvedValueOnce(respond(200, review([match('b', 'Bo', 2, { finalRank: 1 }), match('a', 'Ana', 1, { finalRank: 2 })])))
    vi.stubGlobal('fetch', fetch)
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Move Bo up' }))
    const [url, init] = fetch.mock.calls[1]
    expect(url).toBe('/api/admin/match-requests/req-1/order')
    expect(JSON.parse(init.body)).toEqual({ matchIds: ['b', 'a'] })
    const items = await screen.findAllByRole('listitem')
    expect(within(items[0]).getByRole('heading', { name: 'Bo' })).toBeInTheDocument()
  })

  it('publishes after confirming', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(200, review([match('a', 'Ana', 1), match('b', 'Bo', 2, { hidden: true })])))
      .mockResolvedValueOnce(respond(200, review([match('a', 'Ana', 1)], 'PUBLISHED')))
    vi.stubGlobal('fetch', fetch)
    show()
    expect(await screen.findByRole('heading', { name: 'Stylists (1 shown)' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Publish to client' }))
    expect(window.confirm).toHaveBeenCalledWith("Publish 1 stylist to the client? If they gave an email, they'll get a link.")
    expect(fetch.mock.calls[1][0]).toBe('/api/admin/match-requests/req-1/publish')
    expect(await screen.findByRole('status')).toHaveTextContent('Published')
    expect(screen.queryByRole('button', { name: 'Publish to client' })).not.toBeInTheDocument()
  })

  it('lets the admin hide a match after publishing, but not reorder or publish again', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(200, review([match('a', 'Ana', 1, { adminNote: 'Great fit' })], 'PUBLISHED')))
      .mockResolvedValueOnce(respond(200, review([match('a', 'Ana', 1, { hidden: true, finalRank: 1 })], 'PUBLISHED')))
    vi.stubGlobal('fetch', fetch)
    show()
    expect(await screen.findByDisplayValue('Great fit')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Move/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish to client' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hide from client' }))
    expect(fetch.mock.calls[1][0]).toBe('/api/admin/match-requests/req-1/matches/a')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ hidden: true, note: 'Great fit' })
    expect(await screen.findByRole('button', { name: 'Show' })).toBeInTheDocument()
  })

  it('shows why a list was published empty and lets the admin add a pick afterwards', async () => {
    const empty = review([], 'PUBLISHED')
    empty.request.statusReason = 'No stylist has a compatible portfolio photo for this look yet'
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(200, empty))
      .mockResolvedValueOnce(respond(200, [{ orgId: 'org-b', displayName: 'Bo', locationName: null, alreadyMatched: false }]))
      .mockResolvedValueOnce(respond(200, review([match('b', 'Bo', 1, { source: 'ADMIN' })], 'PUBLISHED')))
    vi.stubGlobal('fetch', fetch)
    show()
    expect(await screen.findByText('No stylist has a compatible portfolio photo for this look yet')).toBeInTheDocument()
    expect(screen.getByText(/the client sees them at once/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish to client' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Add a stylist by hand' }))
    await userEvent.selectOptions(await screen.findByLabelText('Stylist'), 'org-b')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    expect(fetch.mock.calls[2][0]).toBe('/api/admin/match-requests/req-1/matches')
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ orgId: 'org-b', note: null })
    expect(await screen.findByRole('heading', { name: 'Bo' })).toBeInTheDocument()
  })

  it('keeps the add-stylist form open and shows the error when adding fails', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(respond(200, review([], 'PUBLISHED')))
      .mockResolvedValueOnce(respond(200, [{ orgId: 'org-b', displayName: 'Bo', locationName: null, alreadyMatched: false }]))
      .mockResolvedValueOnce(respond(500, { detail: 'Could not add that stylist.' }))
    vi.stubGlobal('fetch', fetch)
    show()
    await userEvent.click(await screen.findByRole('button', { name: 'Add a stylist by hand' }))
    await userEvent.selectOptions(await screen.findByLabelText('Stylist'), 'org-b')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    expect(await screen.findByText('Could not add that stylist.')).toBeInTheDocument()
    expect(screen.getByLabelText('Stylist')).toHaveValue('org-b')
  })
})
