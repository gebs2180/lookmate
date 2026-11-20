import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { axis, points } from './chart'
import { metricsPath } from './metricsPath'
import { WeeklyLine } from './WeeklyLine'

const weeks = [
  { weekStart: '2026-09-28', requestsPublished: 0, clientsMatched: 0, bookingClicks: 0, bookings: 1, rebooks: 0 },
  { weekStart: '2026-10-05', requestsPublished: 1, clientsMatched: 1, bookingClicks: 2, bookings: 1, rebooks: 0 },
  { weekStart: '2026-10-12', requestsPublished: 1, clientsMatched: 1, bookingClicks: 7, bookings: 1, rebooks: 1 },
]

describe('axis', () => {
  it('rounds the top up to a nice step and starts at zero', () => {
    expect(axis(7)).toEqual({ top: 8, ticks: [0, 2, 4, 6, 8] })
    expect(axis(0).ticks[0]).toBe(0)
    expect(axis(130).top).toBe(150)
  })
})

describe('points', () => {
  it('puts zero on the baseline and the top value at the top of the plot', () => {
    const p = points(weeks, 'bookingClicks', 8)
    expect(p[0].y).toBeGreaterThan(p[2].y)
    expect(p[0].x).toBeLessThan(p[2].x)
  })
})

describe('WeeklyLine', () => {
  it('names every series, shows a tooltip on hover and has the numbers in a table', () => {
    render(<WeeklyLine weeks={weeks} />)
    const legend = screen.getByRole('list', { name: 'Legend' })
    expect(legend).toHaveTextContent('Booking clicks')
    expect(legend).toHaveTextContent('Rebooks')
    fireEvent.mouseEnter(screen.getByLabelText(/Week of Oct 12: 7 booking clicks/))
    expect(screen.getByRole('status')).toHaveTextContent('Booking clicks: 7')
    expect(screen.getAllByRole('row')).toHaveLength(4)
  })
})

describe('metricsPath', () => {
  it('sends only the filters that are set', () => {
    expect(metricsPath({ market: '', from: '', to: '' })).toBe('/api/admin/metrics')
    expect(metricsPath({ market: 'columbus-oh', from: '2026-10-01', to: '' })).toBe(
      '/api/admin/metrics?market=columbus-oh&from=2026-10-01',
    )
  })
})
