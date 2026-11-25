import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderAt, stubApi } from '../../stylists/testing'
import { respond } from '../../test/auth0'
import { StylistBookingsPage } from './RecordBookingPage'

const ORG = 'org-1'

describe('StylistBookingsPage', () => {
  it('lists clients who picked the stylist from the directory and records a booking on their own org', async () => {
    const calls = stubApi({
      [`GET /api/orgs/${ORG}/tracking/matches`]: () => [],
      [`GET /api/orgs/${ORG}/tracking/directory-picks`]: () => [
        {
          requestId: 'req-1',
          clientFirstName: 'Dana',
          service: 'COLOR',
          lastClickedAt: '2026-11-18T15:00:00Z',
          clicks: 1,
          recommended: false,
          bookedEventId: null,
        },
      ],
      [`POST /api/orgs/${ORG}/match-requests/req-1/events`]: () => respond(201, { id: 'ev-1', type: 'BOOKED' }),
    })
    renderAt(`/orgs/${ORG}/bookings`, '/orgs/:orgId/bookings', <StylistBookingsPage />)

    const section = (await screen.findByRole('heading', { name: 'Picked from the directory' })).closest('section')!
    const row = (await within(section).findByText('Dana')).closest('tr')!
    expect(within(section).queryByRole('columnheader', { name: 'Stylist' })).not.toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: 'Record booking' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Recorded a booked for Dana.')
    expect(calls.find((c) => c.method === 'POST')?.body).toEqual({ type: 'BOOKED' })
  })
})
