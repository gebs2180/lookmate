import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { FairnessReport, MatchMonitor, MonitorItem } from '../../api'
import { respond } from '../../test/auth0'
import { MatchMonitorPage } from './MatchMonitorPage'

const FAIRNESS: FairnessReport = {
  market: 'columbus-oh',
  since: '2026-08-22T00:00:00Z',
  rows: [
    {
      hairType: 'COILY',
      requests: 4,
      failed: 0,
      ranked: 4,
      reviewedByAdmin: 0,
      published: 3,
      noSystemMatches: 1,
      withAdminChanges: 0,
      shownMatches: 9,
      clickedRequests: 2,
      bookedRequests: 1,
      meanTopScore: 0.81,
    },
  ],
}

const ITEM: MonitorItem = {
  id: 'req-1',
  status: 'PUBLISHED',
  statusReason: null,
  market: 'columbus-oh',
  service: 'CUT',
  hairType: 'COILY',
  firstName: 'Sam',
  createdAt: '2026-11-19T12:00:00Z',
  publishedAt: '2026-11-19T12:01:00Z',
  adminChanged: false,
  firstViewedAt: null,
  directoryClicks: 2,
  directoryBookings: 1,
  matches: [
    { id: 'm2', orgId: 'org-bo', stylistName: 'Bo', stylistLive: true, finalRank: 2, systemRank: 2, hidden: false, source: 'SYSTEM', bookingClicks: 0, bookings: 0, rebooks: 0 },
    { id: 'm1', orgId: 'org-ana', stylistName: 'Ana', stylistLive: true, finalRank: 1, systemRank: 1, hidden: false, source: 'SYSTEM', bookingClicks: 3, bookings: 1, rebooks: 0 },
  ],
}

function show(monitor: MatchMonitor, more: Response[] = []) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith('/api/admin/match-monitor')) return respond(200, monitor)
    if (url.startsWith('/api/admin/match-reports/fairness')) return respond(200, FAIRNESS)
    if (url.startsWith('/api/admin/match-requests?status=')) return respond(200, [])
    if (init?.method === 'POST') return more.shift() ?? respond(200, {})
    return respond(404)
  })
  vi.stubGlobal('fetch', fetch)
  render(
    <MemoryRouter initialEntries={['/admin/matches']}>
      <MatchMonitorPage />
    </MemoryRouter>,
  )
  return fetch
}

describe('MatchMonitorPage', () => {
  it('puts fairness first, then what each client was shown and did', async () => {
    show({ reviewBeforePublish: false, requests: [ITEM] })
    expect(await screen.findByRole('heading', { name: 'Fairness by hair type, last 90 days' })).toBeInTheDocument()
    expect(await screen.findByRole('rowheader', { name: 'coily' })).toBeInTheDocument()
    expect(screen.getByText(/as soon as it's ranked/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Review queue' })).not.toBeInTheDocument()

    const row = (await screen.findByText('not opened yet')).closest('tr')!
    const shown = within(row).getAllByRole('listitem')
    expect(shown[0]).toHaveTextContent('Ana 3 · 1')
    expect(shown[1]).toHaveTextContent('Bo 0 · 0')
    expect(within(row).getByText('2 clicks · 1 booking')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/admin/matches/req-1')
  })

  it('brings the review queue back when review before publish is on', async () => {
    show({ reviewBeforePublish: true, requests: [] })
    expect(await screen.findByRole('heading', { name: 'Review queue' })).toBeInTheDocument()
    expect(screen.getByText(/lists wait for you in the queue below/)).toBeInTheDocument()
  })

  it('hides a stylist from every result after asking', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const fetch = show({ reviewBeforePublish: false, requests: [ITEM] })
    const row = (await screen.findByText('not opened yet')).closest('tr')!
    await userEvent.click(within(within(row).getAllByRole('listitem')[0]).getByRole('button', { name: 'Hide everywhere' }))
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Hide Ana from every client'))
    expect(fetch).toHaveBeenCalledWith('/api/admin/stylists/org-ana/hide', expect.objectContaining({ method: 'POST' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Ana is hidden from all results.')
  })
})
