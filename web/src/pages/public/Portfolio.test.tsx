import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Portfolio, type StylistView } from './Portfolio'

const STYLIST: StylistView = {
  name: 'Ana',
  location: 'Short North',
  demo: false,
  photoUrls: ['https://storage.test/a1'],
  instagram: null,
  book: { href: '/api/public/book/t/m1' },
}

const PAGE = {
  orgId: 'org-1',
  slug: 'ana',
  displayName: 'Ana',
  locationName: null,
  city: null,
  bio: null,
  specialties: [],
  instagram: null,
  demo: false,
  practice: null,
  photoGroups: [
    { hairType: 'STRAIGHT', photos: [{ photoId: 's1', url: 'https://storage.test/s1', service: 'CUT', hairType: 'STRAIGHT', before: false, width: 1, height: 1 }] },
    { hairType: 'WAVY', photos: [{ photoId: 'w1', url: 'https://storage.test/w1', service: 'CUT', hairType: 'WAVY', before: false, width: 1, height: 1 }] },
    { hairType: 'COILY', photos: [{ photoId: 'c1', url: 'https://storage.test/c1', service: 'CUT', hairType: 'COILY', before: false, width: 1, height: 1 }] },
  ],
  finderPath: null,
}

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => vi.unstubAllGlobals())

describe('Portfolio', () => {
  it("loads the stylist's page by orgId: what to expect in their chair, when the page doesn't have it (a client's picks)", async () => {
    const fetch = vi.fn(async () =>
      json(200, {
        ...PAGE,
        practice: {
          labels: ['GENDER_NEUTRAL_PRICING', 'SENSORY_FRIENDLY'],
          languages: ['SOMALI', 'ARABIC'],
          fromPrices: [{ service: 'BRAIDS', fromDollars: 1200 }],
          consultation: null,
          priceDependsOnHair: true,
        },
      }),
    )
    vi.stubGlobal('fetch', fetch)
    render(<Portfolio stylist={{ ...STYLIST, orgId: 'org-1' }} onClose={() => {}} />)

    const practice = await screen.findByRole('region', { name: 'What to expect in their chair' })
    expect(fetch).toHaveBeenCalledWith('/api/public/stylists/org-1', expect.objectContaining({ method: 'GET' }))
    expect(within(practice).getByText('Gender-neutral pricing')).toBeInTheDocument()
    expect(within(practice).getByText('Sensory-friendly on request')).toBeInTheDocument()
    expect(within(practice).getByText('Also speaks Somali and Arabic')).toBeInTheDocument()
    expect(within(practice).getByText('Braids from $1,200')).toBeInTheDocument()
    expect(within(practice).getByText(/Price depends on length and density/)).toBeInTheDocument()
    expect(within(practice).queryByText(/consultation$/i)).not.toBeInTheDocument()
  })

  it('shows nothing when the stylist gave nothing, or it could not be loaded', async () => {
    const fetch = vi.fn(async () => json(404, { detail: 'Stylist not found' }))
    vi.stubGlobal('fetch', fetch)
    render(<Portfolio stylist={{ ...STYLIST, orgId: 'org-gone' }} onClose={() => {}} />)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'What to expect in their chair' })).not.toBeInTheDocument()
  })

  it('shows the whole portfolio by hair type once it has loaded', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, PAGE)))
    render(<Portfolio stylist={{ ...STYLIST, orgId: 'org-1' }} onClose={() => {}} />)

    const wavy = await screen.findByRole('region', { name: 'Wavy hair' })
    expect(within(wavy).getByRole('img')).toHaveAttribute('src', 'https://storage.test/w1')
    expect(screen.getByRole('region', { name: 'Coily hair' })).toBeInTheDocument()
    // The hero is now the first photo of the first group.
    expect(screen.getAllByRole('img')[0]).toHaveAttribute('src', 'https://storage.test/s1')
  })

  it('uses the practice that came with the page', async () => {
    const fetch = vi.fn(async () => json(404, {}))
    vi.stubGlobal('fetch', fetch)
    render(
      <Portfolio
        stylist={{
          ...STYLIST,
          orgId: 'org-1',
          practice: { labels: [], languages: [], fromPrices: [], consultation: null, priceDependsOnHair: false, firstVisit: { colourConsultationFirst: false, depositForNewClients: false, note: null }, newClients: { status: 'TAKING' as const, weeksOut: null } },
        }}
        onClose={() => {}}
      />,
    )
    await vi.waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByRole('region', { name: 'What to expect in their chair' })).not.toBeInTheDocument()
    expect(screen.getByText('Taking new clients')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Before your first visit' })).not.toBeInTheDocument()
  })

  it("shows the status and the stylist's own first-visit facts before the generic notes for the client's look", async () => {
    const fetch = vi.fn(async (url: string) =>
      url.startsWith('/api/public/stylists/first-visit-notes')
        ? json(200, [
            { key: 'CUT+CURLY', text: 'Come with your curls dry and unstyled.' },
            { key: 'any.photos', text: 'Bring two or three photos.' },
          ])
        : json(404, {}),
    )
    vi.stubGlobal('fetch', fetch)
    render(
      <Portfolio
        stylist={{
          ...STYLIST,
          visit: { service: 'CUT', hairType: 'CURLY' },
          practice: {
            labels: [],
            languages: [],
            fromPrices: [],
            consultation: null,
            priceDependsOnHair: false,
            firstVisit: { colourConsultationFirst: true, depositForNewClients: true, note: "I'm in loft 12." },
            newClients: { status: 'NOT_TAKING', weeksOut: null },
          },
        }}
        onClose={() => {}}
      />,
    )

    expect(screen.getByText('Not taking new clients right now')).toBeInTheDocument()
    // Book stays: the status is a heads-up, not a gate.
    expect(screen.getByRole('link', { name: 'Book with Ana' })).toBeInTheDocument()
    const block = await screen.findByRole('region', { name: 'Before your first visit' })
    await within(block).findByText('Come with your curls dry and unstyled.')
    expect(fetch).toHaveBeenCalledWith('/api/public/stylists/first-visit-notes?service=CUT&hairType=CURLY', expect.anything())
    const items = within(block).getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toEqual([
      'New colour clients book a consultation first.',
      'Asks new clients for a deposit when they book (paid to the stylist, not Combpatible).',
      "From Ana: I'm in loft 12.",
      'Come with your curls dry and unstyled.',
      'Bring two or three photos.',
    ])
  })
})
