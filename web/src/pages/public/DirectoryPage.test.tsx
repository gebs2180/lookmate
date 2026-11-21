import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { ClientView, DirectoryPage as Page } from '../../api'
import { DirectoryPage } from './DirectoryPage'

const TOKEN = 'c'.repeat(43)

const PAGE: Page = {
  market: 'columbus-oh',
  service: null,
  hairType: null,
  page: 0,
  pageSize: 12,
  total: 2,
  stylists: [
    {
      orgId: 'org-ana',
      displayName: 'Ana',
      locationName: 'Short North',
      city: 'Columbus',
      specialties: ['CUT', 'SILK_PRESS'],
      photoUrls: ['https://storage.test/a1?X-Amz-Signature=x'],
      demo: false,
      instagram: 'ana.cuts',
      practice: {
        labels: ['LGBTQ_AFFIRMING'],
        languages: ['SPANISH'],
        fromPrices: [
          { service: 'CUT', fromDollars: 65 },
          { service: 'SILK_PRESS', fromDollars: 80 },
        ],
        consultation: 'FREE',
        priceDependsOnHair: false,
      },
    },
    {
      orgId: 'org-bea',
      displayName: 'Bea',
      locationName: null,
      city: null,
      specialties: [],
      photoUrls: [],
      demo: true,
      instagram: null,
      practice: { labels: [], languages: [], fromPrices: [], consultation: null, priceDependsOnHair: false },
    },
  ],
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

function show(path: string, mine?: Partial<ClientView>) {
  const fetch = vi.fn(async (url: string) =>
    url.startsWith('/api/public/stylists') || url.includes('/stylists') ? json(PAGE) : json({ matches: [], ...mine }),
  )
  vi.stubGlobal('fetch', fetch)
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/stylists" element={<DirectoryPage />} />
        <Route path="/m/:token/stylists" element={<DirectoryPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return fetch
}

describe('DirectoryPage', () => {
  it("offers a tracked Book for every stylist from the client's own link, and marks their recommendations", async () => {
    const fetch = show(`/m/${TOKEN}/stylists`, { matches: [{ matchId: 'm1', orgId: 'org-ana' } as ClientView['matches'][number]] })
    const ana = (await screen.findByRole('heading', { name: 'Ana' })).closest('li')!
    // Scoped to the client's request, not the open listing.
    expect(fetch).toHaveBeenCalledWith(`/api/public/match-requests/${TOKEN}/stylists`, expect.anything())
    expect(within(ana).getByText('Short North, Columbus')).toBeInTheDocument()
    expect(within(ana).getByText('Cut · Silk press')).toBeInTheDocument()
    expect(within(ana).getByText('Cut from $65 · Silk press from $80')).toBeInTheDocument()
    expect(await within(ana).findByText('On your list')).toBeInTheDocument()
    const book = within(ana).getByRole('link', { name: 'Book with Ana' })
    expect(book).toHaveAttribute('href', `/api/public/book-stylist/${TOKEN}/org-ana`)
    expect(book.getAttribute('rel')).toContain('noreferrer')
    expect(within(ana).getByRole('img', { name: "Ana's work, photo 1" })).toHaveAttribute('referrerpolicy', 'no-referrer')

    const bea = screen.getByRole('heading', { name: 'Bea' }).closest('li')!
    expect(within(bea).getByText('Sample stylist')).toBeInTheDocument()
    expect(within(bea).queryByText('On your list')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to your matches' })).toHaveAttribute('href', `/m/${TOKEN}`)
  })

  it("opens a stylist's photos from a thumbnail", async () => {
    show('/stylists')
    const ana = (await screen.findByRole('heading', { name: 'Ana' })).closest('li')!
    await userEvent.setup().click(within(ana).getByRole('button', { name: "Ana's work, photo 1" }))
    const dialog = screen.getByRole('dialog', { name: 'Ana' })
    // What to expect in her chair comes with the directory: no extra fetch.
    const practice = within(dialog).getByRole('region', { name: 'What to expect in their chair' })
    expect(within(practice).getByText('LGBTQ+-affirming')).toBeInTheDocument()
    expect(within(practice).getByText('Also speaks Spanish')).toBeInTheDocument()
    expect(within(practice).getByText('Cut from $65 · Silk press from $80')).toBeInTheDocument()
    expect(within(practice).getByText('Free consultation')).toBeInTheDocument()
    // Never a "No" for what she didn't tick.
    expect(within(practice).queryByText(/Sensory|Kids|Gender-neutral/)).not.toBeInTheDocument()
  })

  it('without a client link, Book means starting a request: no booking link reaches the page', async () => {
    const fetch = show('/stylists')
    await screen.findByRole('heading', { name: 'Ana' })
    expect(screen.queryByRole('link', { name: /Book with/ })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Start a request to book' })[0]).toHaveAttribute('href', '/find')
    expect(fetch.mock.calls.every(([url]) => !url.includes('/match-requests/'))).toBe(true)
  })

  it('filters by service and hair type', async () => {
    const fetch = show('/stylists')
    await screen.findByRole('heading', { name: 'Ana' })
    await userEvent.selectOptions(screen.getByLabelText('Service'), 'BRAIDS')
    await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/public/stylists?service=BRAIDS', expect.anything()))
    await userEvent.selectOptions(screen.getByLabelText('Hair type'), 'COILY')
    await waitFor(() =>
      expect(fetch).toHaveBeenLastCalledWith('/api/public/stylists?service=BRAIDS&hairType=COILY', expect.anything()),
    )
  })
})
