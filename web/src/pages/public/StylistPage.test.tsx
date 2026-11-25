import { screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderAt, stubApi } from '../../stylists/testing'
import type { StylistPublicPage } from '../../stylists/types'
import { StylistPage } from './StylistPage'

const PAGE: StylistPublicPage = {
  orgId: 'org-1',
  slug: 'ana-curls',
  displayName: 'Ana',
  locationName: 'Salon Lofts Short North',
  city: 'Columbus',
  bio: 'Curly cuts, mostly.',
  specialties: ['CUT', 'COLOR'],
  instagram: 'curls.by.ana',
  demo: false,
  practice: {
    labels: ['LGBTQ_AFFIRMING'],
    languages: [],
    fromPrices: [{ service: 'CUT', fromDollars: 85 }],
    consultation: null,
    priceDependsOnHair: false,
    firstVisit: { colourConsultationFirst: false, depositForNewClients: false, note: 'Loft 12, second floor.' },
    newClients: { status: 'TAKING_WITH_WAIT', weeksOut: 3 },
  },
  photoGroups: [
    {
      hairType: 'CURLY',
      photos: [
        { photoId: 'p1', url: 'https://storage.test/p1', service: 'CUT', hairType: 'CURLY', before: false, width: 800, height: 1000 },
        { photoId: 'p2', url: 'https://storage.test/p2', service: 'CUT', hairType: 'CURLY', before: false, width: 800, height: 1000 },
      ],
    },
    {
      hairType: 'COILY',
      photos: [{ photoId: 'p3', url: 'https://storage.test/p3', service: 'COLOR', hairType: 'COILY', before: false, width: 800, height: 1000 }],
    },
  ],
  finderPath: null,
}

afterEach(() => vi.unstubAllGlobals())

describe('StylistPage', () => {
  it('shows the work by hair type, what to expect, and a Book link tracked as the profile source', async () => {
    stubApi({ 'GET /api/public/stylists/by-slug/ana-curls': () => PAGE })
    renderAt('/s/ana-curls?via=qr', '/s/:slug', <StylistPage />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana' })).toBeInTheDocument()
    expect(screen.getByText('Salon Lofts Short North, Columbus')).toBeInTheDocument()
    expect(screen.getByText('Cut · Color')).toBeInTheDocument()
    expect(screen.getByText('Taking new clients · usually about 3 weeks out')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'What to expect in their chair' })).getByText('Cut from $85')).toBeInTheDocument()
    expect(screen.getByText(/Loft 12, second floor/)).toBeInTheDocument()

    const curly = screen.getByRole('region', { name: 'Curly hair' })
    // The first curly photo is the hero; the second sits in its group.
    expect(within(curly).getAllByRole('img')).toHaveLength(1)
    expect(within(screen.getByRole('region', { name: 'Coily hair' })).getAllByRole('img')).toHaveLength(1)

    const book = screen.getByRole('link', { name: 'Book with Ana' })
    expect(book).toHaveAttribute('href', '/api/public/stylists/org-1/book?via=qr')
    expect(book).toHaveAttribute('rel', expect.stringContaining('noreferrer'))
    expect(screen.queryByRole('link', { name: 'Not sure what to book?' })).not.toBeInTheDocument()
    expect(screen.getByText(/Combpatible never takes payments/)).toBeInTheDocument()
  })

  it('shows the finder for the partner, and tags an unknown via as a plain link', async () => {
    stubApi({ 'GET /api/public/stylists/by-slug/ana-curls': () => ({ ...PAGE, finderPath: '/c/partner' }) })
    renderAt('/s/ana-curls?via=<script>', '/s/:slug', <StylistPage />)

    expect(await screen.findByRole('link', { name: 'Not sure what to book?' })).toHaveAttribute('href', '/c/partner')
    expect(screen.getByRole('link', { name: 'Book with Ana' })).toHaveAttribute('href', '/api/public/stylists/org-1/book?via=link')
  })

  it("says not found for an address that isn't a live stylist's page", async () => {
    stubApi({})
    renderAt('/s/nobody', '/s/:slug', <StylistPage />)
    expect(await screen.findByRole('heading', { name: /not found|couldn't find/i })).toBeInTheDocument()
  })
})
