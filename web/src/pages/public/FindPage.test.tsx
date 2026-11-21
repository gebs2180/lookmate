import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { FindPage } from './FindPage'
import { PrivacyNote } from './PhotoPicker'

const TOKEN = 'a'.repeat(43)

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function stubApi(confirm: () => Response = () => json(200, { stage: 'RECEIVED' })) {
  const calls: { method: string; url: string; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ method, url, body: typeof init.body === 'string' ? JSON.parse(init.body) : init.body })
      if (url === '/api/public/match-requests' && method === 'POST') {
        return json(201, {
          token: TOKEN,
          statusPath: `/m/${TOKEN}`,
          upload: { url: 'https://storage.test/put', method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, expiresAt: '2026-11-20T00:10:00Z' },
        })
      }
      if (url === '/api/public/intake/settings') return json(200, { photoKeptDays: 21, maxPhotoBytes: 26214400 })
      if (url === 'https://storage.test/put') return new Response(null, { status: 200 })
      if (url.endsWith('/inspiration/confirm')) return confirm()
      return json(404, { detail: 'nope' })
    }),
  )
  return calls
}

async function answerEverything({ asks = false } = {}) {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/find']}>
      <Routes>
        <Route path="/find" element={<FindPage />} />
        <Route path="/m/:token" element={<h1>Results page</h1>} />
      </Routes>
    </MemoryRouter>,
  )
  expect(screen.getByText('Question 1 of 8')).toBeInTheDocument()
  const next = () => user.click(screen.getByRole('button', { name: 'Next' }))
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  await user.click(screen.getByLabelText('Cut'))
  await next()
  await user.click(screen.getByLabelText(/Curly/))
  await next()
  await user.click(screen.getByLabelText(/Thick/))
  await next()
  await user.click(screen.getByLabelText('Not sure'))
  await next()
  await user.click(screen.getByLabelText(/^\$\$Mid-range/))
  await next()
  await user.type(screen.getByLabelText('Neighborhood'), 'Clintonville')
  await next()
  await user.click(screen.getByLabelText("They didn't listen"))
  if (asks) {
    await user.click(screen.getByLabelText('A quiet appointment'))
    await user.click(screen.getByLabelText('Honest advice about what my hair can do'))
  }
  await next()
  expect(screen.getByText(/never public, and we delete it 21 days after your request/)).toBeInTheDocument()
  await user.upload(screen.getByLabelText('Choose from your photos'), new File(['jpeg-bytes'], 'look.jpg', { type: 'image/jpeg' }))
  // The photo is the last step: no name, no email.
  expect(screen.getByText('Question 8 of 8')).toBeInTheDocument()
  expect(screen.queryByLabelText(/Email/)).not.toBeInTheDocument()
  expect(screen.queryByLabelText(/name/i)).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Find my stylists' }))
  return user
}

describe('FindPage', () => {
  it('says the photo is deleted on a schedule, without a number, until the setting loads', () => {
    render(<PrivacyNote keptDays={null} />)
    expect(screen.getByText(/we delete it on a set schedule/)).toBeInTheDocument()
  })

  it('asks one question per screen, then sends the quiz without an email, uploads the photo and opens the results page', async () => {
    const calls = stubApi()
    await answerEverything()

    // No "Request sent" page: straight to the request's own page, where matching runs and the picks appear.
    expect(await screen.findByRole('heading', { name: 'Results page' })).toBeInTheDocument()
    const create = calls.find((c) => c.url === '/api/public/match-requests')!
    expect(create.body).toEqual({
      hairType: 'CURLY',
      texture: 'THICK',
      budgetTier: 2,
      neighborhood: 'Clintonville',
      service: 'CUT',
      pastIssues: ['DIDNT_LISTEN'],
      inspiration: { contentType: 'image/jpeg', sizeBytes: 10 },
    })
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/public/intake/settings',
      'POST /api/public/match-requests',
      'PUT https://storage.test/put',
      `POST /api/public/match-requests/${TOKEN}/inspiration/confirm`,
    ])
  })

  it('keeps the request when the photo is refused, and points to the request page', async () => {
    stubApi(() => json(422, { detail: 'no', code: 'IMAGE_REJECTED' }))
    await answerEverything()

    expect(await screen.findByRole('alert')).toHaveTextContent("We couldn't use that photo")
    expect(screen.getByRole('link', { name: 'add the photo from your request page' })).toHaveAttribute('href', `/m/${TOKEN}`)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Try the photo again' })).toBeEnabled())
  })

  it('sends the quiet and honest-advice chips with the past issues', async () => {
    const calls = stubApi()
    await answerEverything({ asks: true })
    expect(await screen.findByRole('heading', { name: 'Results page' })).toBeInTheDocument()
    expect(calls.find((c) => c.url === '/api/public/match-requests')!.body).toMatchObject({
      pastIssues: ['DIDNT_LISTEN', 'QUIET_APPOINTMENT', 'HONEST_ADVICE'],
    })
  })

  it('says why when the connection has made too many requests', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url === '/api/public/intake/settings'
          ? json(200, { photoKeptDays: 21, maxPhotoBytes: 26214400 })
          : json(429, { detail: 'Too many tries from your connection. Wait a little and try again.', code: 'RATE_LIMITED' }),
      ),
    )
    await answerEverything()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many tries from your connection.')
  })
})
