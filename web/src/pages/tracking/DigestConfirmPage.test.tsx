import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from '../../App'
import { renderAt, stubApi } from '../../stylists/testing'
import { auth0, respond } from '../../test/auth0'
import type { DigestLink } from '../../api'
import { DigestConfirmPage } from './DigestConfirmPage'

const TOKEN = 'd'.repeat(43)
const ORG = 'org-1'

const LINK: DigestLink = {
  orgId: ORG,
  clientFirstName: 'Sam',
  service: 'CUT',
  weekStart: '2026-11-16',
  directoryPick: false,
  used: false,
  expired: false,
  expiresAt: '2026-12-07T14:00:00Z',
  eventId: null,
}

function open(type: 'BOOKED' | 'REBOOKED' = 'BOOKED') {
  renderAt(`/digest/${TOKEN}?type=${type}`, '/digest/:token', <DigestConfirmPage />)
}

describe('DigestConfirmPage', () => {
  it('records the booking only when the stylist confirms, then can undo it', async () => {
    const calls = stubApi({
      [`GET /api/digest-links/${TOKEN}`]: () => LINK,
      [`POST /api/digest-links/${TOKEN}`]: () => respond(201, { id: 'ev-1', type: 'BOOKED' }),
      [`POST /api/orgs/${ORG}/events/ev-1/void`]: () => ({ id: 'ev-1' }),
    })
    open()

    expect(await screen.findByRole('heading', { name: 'Record Sam as booked?' })).toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'Yes, booked' }))
    expect(await screen.findByRole('heading', { name: 'Recorded' })).toBeInTheDocument()
    expect(calls.find((c) => c.url === `/api/digest-links/${TOKEN}` && c.method === 'POST')?.body).toEqual({ type: 'BOOKED' })

    await userEvent.click(screen.getByRole('button', { name: 'Undo' }))
    expect(await screen.findByRole('heading', { name: 'Undone' })).toBeInTheDocument()
    expect(calls.find((c) => c.url.endsWith('/void'))?.body).toEqual({ reason: 'Undone from the weekly digest' })
  })

  it('says why a rebook is refused, by its code', async () => {
    stubApi({
      [`GET /api/digest-links/${TOKEN}`]: () => LINK,
      [`POST /api/digest-links/${TOKEN}`]: () =>
        respond(409, { detail: 'Record the booking first.', code: 'REBOOK_WITHOUT_BOOKING' }),
    })
    open('REBOOKED')

    await userEvent.click(await screen.findByRole('button', { name: 'Yes, rebooked' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Record the first booking before a rebook.')
  })

  it('shows a used link as recorded, with undo', async () => {
    stubApi({ [`GET /api/digest-links/${TOKEN}`]: () => ({ ...LINK, used: true, eventId: 'ev-9' }) })
    open()

    expect(await screen.findByRole('heading', { name: 'Recorded' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Undo' })).toBeEnabled()
  })

  it('offers nothing to confirm on an expired link', async () => {
    stubApi({ [`GET /api/digest-links/${TOKEN}`]: () => ({ ...LINK, expired: true }) })
    open()

    expect(await screen.findByRole('heading', { name: 'This link has expired' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Yes/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'All your bookings' })).toHaveAttribute('href', `/orgs/${ORG}/bookings`)
  })

  it("asks a signed-out stylist to log in, and comes back to the link", async () => {
    render(
      <MemoryRouter initialEntries={[`/digest/${TOKEN}?type=BOOKED`]}>
        <App />
      </MemoryRouter>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Log in' }))
    await waitFor(() =>
      expect(auth0.loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: `/digest/${TOKEN}?type=BOOKED` } }),
    )
  })
})
