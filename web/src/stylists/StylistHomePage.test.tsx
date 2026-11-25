import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { StylistHomePage } from './StylistHomePage'
import { renderAt, stubApi } from './testing'
import type { StylistHome } from './types'

const ORG = 'org-1'

function home(over: Partial<StylistHome>): StylistHome {
  return {
    orgId: ORG,
    orgName: 'Curl Studio',
    termsAccepted: true,
    profile: null,
    readyPhotos: 0,
    taggedPhotos: 0,
    minTaggedPhotos: 3,
    maxPhotos: 60,
    missing: ['PROFILE', 'TAGGED_PHOTOS'],
    ...over,
  }
}

const profile = {
  displayName: 'Ana',
  locationId: 'loc',
  market: 'columbus-oh',
  bio: null,
  specialties: [],
  bookingUrl: 'https://booksy.com/a',
  bookingPlatform: 'BOOKSY' as const,
  instagram: null,
  priceTier: null,
  practice: { labels: [], languages: [], fromPrices: [], consultation: null, priceDependsOnHair: false, firstVisit: { colourConsultationFirst: false, depositForNewClients: false, note: null }, newClients: { status: 'TAKING' as const, weeksOut: null } },
  status: 'DRAFT' as const,
  publishedAt: null,
  updatedAt: '2026-11-20T00:00:00Z',
  demo: false,
  newClientsConfirmedAt: '2026-11-20T00:00:00Z',
  newClientsConfirmDue: false,
}

describe('StylistHomePage', () => {
  it('shows the terms first and accepts the version shown', async () => {
    let accepted = false
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () => home(accepted ? {} : { termsAccepted: false, missing: ['TERMS', 'PROFILE', 'TAGGED_PHOTOS'] }),
      [`GET /api/orgs/${ORG}/consent`]: () => ({ version: 'stylist-2026-11-20b', text: 'The terms text', acceptedAt: null }),
      [`POST /api/orgs/${ORG}/consent`]: () => {
        accepted = true
        return { version: 'stylist-2026-11-20b', text: 'The terms text', acceptedAt: '2026-11-20T00:00:00Z' }
      },
    })
    renderAt(`/stylist/${ORG}`, '/stylist/:orgId', <StylistHomePage />)

    expect(await screen.findByText('The terms text')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'I accept these terms' }))

    expect(await screen.findByRole('heading', { name: 'Getting ready to go live' })).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ version: 'stylist-2026-11-20b' })
  })

  it("can't go live with something missing, and says what", async () => {
    stubApi({ [`GET /api/orgs/${ORG}/stylist`]: () => home({ readyPhotos: 4, taggedPhotos: 1 }) })
    renderAt(`/stylist/${ORG}`, '/stylist/:orgId', <StylistHomePage />)

    expect(await screen.findByRole('button', { name: 'Go live' })).toBeDisabled()
    expect(screen.getByRole('link', { name: /At least 3 tagged photos \(1 of 4 tagged\)/ })).toHaveAttribute('href', `/stylist/${ORG}/portfolio`)
  })

  it('goes live when nothing is missing, then offers to hide', async () => {
    let live = false
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () =>
        home({ profile: { ...profile, status: live ? 'LIVE' : 'DRAFT' }, readyPhotos: 3, taggedPhotos: 3, missing: [] }),
      [`POST /api/orgs/${ORG}/stylist/publish`]: () => {
        live = true
        return { ...profile, status: 'LIVE' }
      },
    })
    renderAt(`/stylist/${ORG}`, '/stylist/:orgId', <StylistHomePage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Go live' }))

    expect(await screen.findByRole('button', { name: 'Hide my profile' })).toBeInTheDocument()
    expect(screen.getByText(/Live: clients can be matched/)).toBeInTheDocument()
    await waitFor(() => expect(calls.some((c) => c.url === `/api/orgs/${ORG}/stylist/publish`)).toBe(true))
  })

  it('asks whether the new-client status is still accurate after 30 days, and one click confirms it', async () => {
    let due = true
    const waiting = { ...profile.practice, newClients: { status: 'TAKING_WITH_WAIT' as const, weeksOut: 4 } }
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () =>
        home({ profile: { ...profile, practice: waiting, newClientsConfirmDue: due }, readyPhotos: 3, taggedPhotos: 3, missing: [] }),
      [`POST /api/orgs/${ORG}/stylist/new-clients/confirm`]: () => {
        due = false
        return { ...profile, practice: waiting }
      },
    })
    renderAt(`/stylist/${ORG}`, '/stylist/:orgId', <StylistHomePage />)

    expect(await screen.findByRole('heading', { name: 'Still accurate?' })).toBeInTheDocument()
    expect(screen.getByText('Taking new clients · usually about 4 weeks out')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Change it' })).toHaveAttribute('href', `/stylist/${ORG}/profile`)
    await userEvent.click(screen.getByRole('button', { name: 'Yes, still accurate' }))

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Still accurate?' })).not.toBeInTheDocument())
    expect(calls.some((c) => c.method === 'POST' && c.url === `/api/orgs/${ORG}/stylist/new-clients/confirm`)).toBe(true)
  })

  it("doesn't ask while the status is recent", async () => {
    stubApi({ [`GET /api/orgs/${ORG}/stylist`]: () => home({ profile, readyPhotos: 3, taggedPhotos: 3, missing: [] }) })
    renderAt(`/stylist/${ORG}`, '/stylist/:orgId', <StylistHomePage />)
    expect(await screen.findByRole('heading', { name: 'Getting ready to go live' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Still accurate?' })).not.toBeInTheDocument()
  })
})
