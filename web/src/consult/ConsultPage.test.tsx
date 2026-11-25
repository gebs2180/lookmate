import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConsultResult, Finder, Outcome } from './api'
import { GeneralConsultPage, PartnerConsultPage } from './ConsultPage'
import { formatHours } from './format'

const SESSION = '11111111-2222-3333-4444-555555555555'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function finder(channel: Finder['channel'], credit: string | null = null): Finder {
  return {
    channel,
    name: 'Not sure what to book?',
    credit,
    rulesetVersion: 1,
    questions: [
      { id: 'hair_type', text: 'Which is closest to your natural hair?', multiple: false, help: 'NOTE TO US: photo idea', answers: ['Straight', 'Curly'] },
      {
        id: 'history',
        text: 'What has been on your hair in the last 2 years?',
        multiple: true,
        help: null,
        answers: ['Salon colour', 'Box dye from a shop', 'Henna'],
      },
    ],
  }
}

const BALAYAGE: Outcome = {
  id: 'BALAYAGE_PARTIAL',
  kind: 'BOOK',
  serviceName: 'Partial balayage + toner',
  serviceType: 'BALAYAGE',
  hours: 2.5,
  fromPrice: 150,
  bookingUrl: null,
  whatToExpect: 'Hand-painted brightness around your face.',
  why: 'Soft regrowth suits the upkeep you want.',
  firstVisitNotes: ['Plan for a long appointment.', 'Bring two or three photos.'],
}

const CONSULT: Outcome = {
  id: 'CONSULT_CORRECTION',
  kind: 'CONSULTATION',
  serviceName: 'Colour correction consultation',
  serviceType: 'COLOR',
  hours: 0.5,
  fromPrice: 0,
  bookingUrl: null,
  whatToExpect: 'Your stylist looks at your hair and gives you the plan.',
  why: 'Box dye can react with lightener.',
  firstVisitNotes: [],
}

type Call = { method: string; url: string; body: unknown }

function stubApi({
  channel,
  outcome,
  credit = null,
  submit,
}: {
  channel: Finder['channel']
  outcome: Outcome
  credit?: string | null
  submit?: () => Response
}) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined })
      if (url === '/api/public/consult/finder' || url === '/api/public/consult/c/jason/finder') return json(200, finder(channel, credit))
      if (url.endsWith('/finder')) return json(404, { detail: 'Not found', code: 'NOT_FOUND' })
      if (url.endsWith('/sessions') && method === 'POST') {
        if (submit) return submit()
        const result: ConsultResult = { sessionId: SESSION, channel, rulesetVersion: 1, credit, outcome }
        return json(201, result)
      }
      if (url.endsWith('/book') || url.endsWith('/find-stylist')) return new Response(null, { status: 204 })
      return json(404, { detail: 'nope' })
    }),
  )
  return calls
}

function FindStub() {
  const location = useLocation()
  return <h1>Find page {location.search}</h1>
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/consult" element={<GeneralConsultPage />} />
        <Route path="/c/:slug" element={<PartnerConsultPage />} />
        <Route path="/find" element={<FindStub />} />
      </Routes>
    </MemoryRouter>,
  )
}

async function answerAll() {
  const user = userEvent.setup()
  await screen.findByRole('heading', { name: 'Which is closest to your natural hair?' })
  expect(screen.getByText('Question 1 of 2')).toBeInTheDocument()
  // The help field is a note to us, never client copy.
  expect(screen.queryByText(/NOTE TO US/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  await user.click(screen.getByLabelText('Curly'))
  await user.click(screen.getByRole('button', { name: 'Next' }))
  expect(screen.getByRole('heading', { name: 'What has been on your hair in the last 2 years?' })).toBeInTheDocument()
  // Multi-select: several answers at once.
  await user.click(screen.getByLabelText('Box dye from a shop'))
  await user.click(screen.getByLabelText('Henna'))
  expect(screen.getByLabelText('Box dye from a shop')).toBeChecked()
  expect(screen.getByLabelText('Henna')).toBeChecked()
  await user.click(screen.getByRole('button', { name: 'See what to book' }))
  return user
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('the service finder', () => {
  it('formats hours the way people say them', () => {
    expect(formatHours(0.25)).toBe('About 15 minutes')
    expect(formatHours(1)).toBe('About 1 hour')
    expect(formatHours(2.5)).toBe('About 2½ hours')
  })

  it('goes back to the last question with its answer kept', async () => {
    stubApi({ channel: 'GENERAL', outcome: BALAYAGE })
    renderAt('/consult')
    const user = userEvent.setup()
    await screen.findByRole('heading', { name: 'Which is closest to your natural hair?' })
    await user.click(screen.getByLabelText('Straight'))
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.click(screen.getByRole('button', { name: 'Back' }))
    expect(screen.getByRole('heading', { name: 'Which is closest to your natural hair?' })).toBeInTheDocument()
    expect(screen.getByLabelText('Straight')).toBeChecked()
    expect(screen.queryByRole('button', { name: 'Back' })).not.toBeInTheDocument()
  })

  it('general channel: shows the service and hands off to find-my-stylist with it filled in', async () => {
    const calls = stubApi({ channel: 'GENERAL', outcome: { ...BALAYAGE, fromPrice: null } })
    renderAt('/consult')
    const user = await answerAll()

    expect(await screen.findByRole('heading', { name: 'Book Partial balayage + toner' })).toBeInTheDocument()
    expect(screen.getByText('About 2½ hours')).toBeInTheDocument()
    expect(screen.getByText('Soft regrowth suits the upkeep you want.')).toBeInTheDocument()
    expect(screen.getByText('Hand-painted brightness around your face.')).toBeInTheDocument()
    expect(screen.getByText('Your stylist confirms at your appointment.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Before your first visit' })).toBeInTheDocument()
    expect(screen.getByText('Plan for a long appointment.')).toBeInTheDocument()
    expect(screen.queryByText(/Questions and answers by/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Book / })).not.toBeInTheDocument()

    const submitted = calls.find((c) => c.url === '/api/public/consult/sessions')
    expect(submitted?.body).toEqual({ answers: { hair_type: ['Curly'], history: ['Box dye from a shop', 'Henna'] } })

    await user.click(screen.getByRole('button', { name: 'Find a stylist for this' }))
    expect(await screen.findByRole('heading', { name: 'Find page ?service=BALAYAGE' })).toBeInTheDocument()
    expect(calls.some((c) => c.method === 'POST' && c.url === `/api/public/consult/sessions/${SESSION}/find-stylist`)).toBe(true)
  })

  it('consultation: says to book a consultation first, with its reason', async () => {
    stubApi({ channel: 'GENERAL', outcome: CONSULT })
    renderAt('/consult')
    await answerAll()
    expect(await screen.findByRole('heading', { name: 'Book a consultation first' })).toBeInTheDocument()
    expect(screen.getByText('Colour correction consultation')).toBeInTheDocument()
    expect(screen.getByText('Box dye can react with lightener.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Before your first visit' })).not.toBeInTheDocument()
    expect(screen.getByText('Your stylist confirms at your appointment.')).toBeInTheDocument()
    // A from-price of 0 is a gap in the data, not "free".
    expect(screen.queryByText(/\$0/)).not.toBeInTheDocument()
  })

  it('partner channel without a booking link: tells the client to book directly, and offers find-my-stylist', async () => {
    const calls = stubApi({ channel: 'PARTNER', outcome: BALAYAGE, credit: 'Jason, Salon Lofts' })
    renderAt('/c/jason')
    expect(await screen.findByText(/Questions by Jason, Salon Lofts/)).toBeInTheDocument()
    await answerAll()
    expect(await screen.findByText('About 2½ hours, from $150')).toBeInTheDocument()
    expect(screen.getByText(/with Jason, Salon Lofts directly/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Send my answers to the stylist')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Book / })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Find a stylist for this' })).toBeInTheDocument()
    expect(calls.some((c) => c.url === '/api/public/consult/c/jason/sessions')).toBe(true)
  })

  it('partner channel with a booking link: the tick box is off by default and its value is sent with Book', async () => {
    const tab = { opener: {} as unknown, location: { href: '' } }
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    const url = 'https://book.example/jason?utm_source=combpatible'
    const calls = stubApi({ channel: 'PARTNER', outcome: { ...BALAYAGE, bookingUrl: url } })
    renderAt('/c/jason')
    const user = await answerAll()

    const box = await screen.findByLabelText('Send my answers to the stylist')
    expect(box).not.toBeChecked()
    expect(screen.queryByRole('button', { name: 'Find a stylist for this' })).not.toBeInTheDocument()
    await user.click(box)
    await user.click(screen.getByRole('button', { name: 'Book Partial balayage + toner' }))

    await waitFor(() => expect(tab.location.href).toBe(url))
    expect(open).toHaveBeenCalled()
    expect(tab.opener).toBeNull()
    const book = calls.find((c) => c.url === `/api/public/consult/sessions/${SESSION}/book`)
    expect(book?.body).toEqual({ shareAnswers: true })
  })

  it('sends shareAnswers false when the box is left unticked', async () => {
    const tab = { opener: null, location: { href: '' } }
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window)
    const calls = stubApi({ channel: 'PARTNER', outcome: { ...BALAYAGE, bookingUrl: 'https://book.example/x' } })
    renderAt('/c/jason')
    const user = await answerAll()
    await user.click(await screen.findByRole('button', { name: 'Book Partial balayage + toner' }))
    await waitFor(() => expect(tab.location.href).toBe('https://book.example/x'))
    expect(calls.find((c) => c.url.endsWith('/book'))?.body).toEqual({ shareAnswers: false })
  })

  it('an unknown partner link shows a friendly page', async () => {
    stubApi({ channel: 'PARTNER', outcome: BALAYAGE })
    renderAt('/c/nobody')
    expect(await screen.findByRole('heading', { name: "We couldn't find that stylist's link" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Not sure what to book?' })).toHaveAttribute('href', '/consult')
  })

  it('says to wait when rate limited, keeping the answers', async () => {
    stubApi({
      channel: 'GENERAL',
      outcome: BALAYAGE,
      submit: () => json(429, { detail: 'Too many tries from your connection.', code: 'RATE_LIMITED' }),
    })
    renderAt('/consult')
    await answerAll()
    expect(await screen.findByRole('alert')).toHaveTextContent(/Wait a few minutes/)
    expect(screen.getByLabelText('Henna')).toBeChecked()
    expect(screen.queryByRole('button', { name: 'Start again' })).not.toBeInTheDocument()
  })

  it('offers to start again when the answers no longer fit', async () => {
    stubApi({
      channel: 'GENERAL',
      outcome: BALAYAGE,
      submit: () => json(400, { detail: "Unknown answer 'Henna'", code: 'INVALID_ANSWERS' }),
    })
    renderAt('/consult')
    const user = await answerAll()
    expect(await screen.findByRole('alert')).toHaveTextContent(/start again/i)
    await user.click(screen.getByRole('button', { name: 'Start again' }))
    expect(await screen.findByText('Question 1 of 2')).toBeInTheDocument()
  })
})
