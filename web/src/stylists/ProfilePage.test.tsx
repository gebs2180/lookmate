import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { respond } from '../test/auth0'
import { ProfilePage } from './ProfilePage'
import { renderAt, stubApi } from './testing'

const ORG = 'org-1'

const HOME = {
  orgId: ORG,
  orgName: 'Curl Studio',
  termsAccepted: true,
  profile: null,
  readyPhotos: 0,
  taggedPhotos: 0,
  minTaggedPhotos: 3,
  maxPhotos: 60,
  missing: ['PROFILE', 'TAGGED_PHOTOS'],
}

const LOCATIONS = [{ id: 'loc-1', brand: 'SALON_LOFTS', name: 'Short North', market: 'columbus-oh', address: null, city: 'Columbus', state: 'OH', zip: null, latitude: null, longitude: null }]

describe('ProfilePage', () => {
  it('saves the profile and goes back home', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () => HOME,
      'GET /api/locations': () => LOCATIONS,
      [`PUT /api/orgs/${ORG}/stylist/profile`]: () => ({}),
    })
    renderAt(`/stylist/${ORG}/profile`, '/stylist/:orgId/profile', <ProfilePage />)

    const name = await screen.findByLabelText('Name clients know you by')
    expect(name).toHaveValue('Curl Studio')
    await userEvent.selectOptions(screen.getByLabelText('Your location'), 'loc-1')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Silk press' }))
    await userEvent.type(screen.getByLabelText('Booking link'), 'https://booksy.com/ana')
    await userEvent.type(screen.getByLabelText('Instagram'), '@curls.by.ana')
    await userEvent.click(screen.getByRole('radio', { name: '$$' }))
    // What to expect in my chair: all optional.
    await userEvent.click(screen.getByRole('checkbox', { name: /^LGBTQ\+-affirming/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Spanish' }))
    await userEvent.type(screen.getByLabelText('Silk press from $'), '80')
    await userEvent.click(screen.getByRole('radio', { name: 'Free' }))
    // New clients and the first visit: shown to clients, never a filter.
    await userEvent.click(screen.getByRole('radio', { name: "I'm taking new clients, with a wait" }))
    await userEvent.type(screen.getByLabelText('Usual wait for a new client, in weeks'), '3')
    await userEvent.click(screen.getByRole('checkbox', { name: 'I ask new clients for a deposit when they book' }))
    await userEvent.type(screen.getByLabelText('A note for new clients'), 'Park on Fourth Street.')
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(await screen.findByText('Elsewhere')).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({
      displayName: 'Curl Studio',
      locationId: 'loc-1',
      bio: null,
      specialties: ['SILK_PRESS'],
      bookingUrl: 'https://booksy.com/ana',
      instagram: '@curls.by.ana',
      // A new profile: the page address comes from the name.
      slug: null,
      priceTier: 2,
      practice: {
        labels: ['LGBTQ_AFFIRMING'],
        languages: ['SPANISH'],
        fromPrices: [{ service: 'SILK_PRESS', fromDollars: 80 }],
        consultation: 'FREE',
        priceDependsOnHair: false,
        firstVisit: { colourConsultationFirst: false, depositForNewClients: true, note: 'Park on Fourth Street.' },
      },
      newClients: { status: 'TAKING_WITH_WAIT', weeksOut: 3 },
    })
  })

  it('only sends prices for services still offered', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () => HOME,
      'GET /api/locations': () => LOCATIONS,
      [`PUT /api/orgs/${ORG}/stylist/profile`]: () => ({}),
    })
    renderAt(`/stylist/${ORG}/profile`, '/stylist/:orgId/profile', <ProfilePage />)

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Cut' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Color' }))
    await userEvent.type(screen.getByLabelText('Cut from $'), '65')
    await userEvent.type(screen.getByLabelText('Color from $'), '120')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Color' }))
    expect(screen.queryByLabelText('Color from $')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }))

    await screen.findByText('Elsewhere')
    const put = calls.find((c) => c.method === 'PUT')!
    expect((put.body as { practice: unknown }).practice).toEqual({
      labels: [],
      languages: [],
      fromPrices: [{ service: 'CUT', fromDollars: 65 }],
      consultation: null,
      priceDependsOnHair: false,
      firstVisit: { colourConsultationFirst: false, depositForNewClients: false, note: null },
    })
    expect((put.body as { newClients: unknown }).newClients).toEqual({ status: 'TAKING', weeksOut: null })
  })

  it("shows the API's reason when the profile is refused", async () => {
    stubApi({
      [`GET /api/orgs/${ORG}/stylist`]: () => HOME,
      'GET /api/locations': () => LOCATIONS,
      [`PUT /api/orgs/${ORG}/stylist/profile`]: () =>
        respond(400, { detail: 'The booking link must start with https://', code: 'INVALID_FIELD' }),
    })
    renderAt(`/stylist/${ORG}/profile`, '/stylist/:orgId/profile', <ProfilePage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Save profile' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The booking link must start with https://')
  })
})
