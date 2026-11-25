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

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => vi.unstubAllGlobals())

describe('Portfolio', () => {
  it("fetches what to expect in the stylist's chair by orgId when the page doesn't have it (a client's picks)", async () => {
    const fetch = vi.fn(async () =>
      json(200, {
        labels: ['GENDER_NEUTRAL_PRICING', 'SENSORY_FRIENDLY'],
        languages: ['SOMALI', 'ARABIC'],
        fromPrices: [{ service: 'BRAIDS', fromDollars: 1200 }],
        consultation: null,
        priceDependsOnHair: true,
      }),
    )
    vi.stubGlobal('fetch', fetch)
    render(<Portfolio stylist={{ ...STYLIST, orgId: 'org-1' }} onClose={() => {}} />)

    const practice = await screen.findByRole('region', { name: 'What to expect in their chair' })
    expect(fetch).toHaveBeenCalledWith('/api/public/stylists/org-1/practice', expect.objectContaining({ method: 'GET' }))
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

  it("doesn't fetch when the practice came with the page", () => {
    const fetch = vi.fn()
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
    expect(fetch).not.toHaveBeenCalled()
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
