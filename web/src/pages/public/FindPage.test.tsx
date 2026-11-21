import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
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

async function answerEverything(firstName = 'Ada') {
  const user = userEvent.setup()
  render(
    <MemoryRouter>
      <FindPage />
    </MemoryRouter>,
  )
  expect(screen.getByText('Question 1 of 9')).toBeInTheDocument()
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
  await next()
  expect(screen.getByText(/never public, and we delete it 21 days after your request/)).toBeInTheDocument()
  await user.upload(screen.getByLabelText('Choose from your photos'), new File(['jpeg-bytes'], 'look.jpg', { type: 'image/jpeg' }))
  await next()
  await user.type(screen.getByLabelText('Email'), 'ada@example.com')
  await user.type(screen.getByLabelText('First name (optional)'), firstName)
  if (firstName.includes('@')) return user
  await user.click(screen.getByRole('button', { name: 'Find my stylists' }))
  return user
}

describe('FindPage', () => {
  it("won't send an email address as the first name", async () => {
    const calls = stubApi()
    const user = await answerEverything('ada@example.com')

    expect(screen.getByRole('alert')).toHaveTextContent('That looks like an email address')
    expect(screen.getByRole('button', { name: 'Find my stylists' })).toBeDisabled()
    expect(calls.some((c) => c.url === '/api/public/match-requests')).toBe(false)

    await user.clear(screen.getByLabelText('First name (optional)'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Find my stylists' })).toBeEnabled()
  })

  it('says the photo is deleted on a schedule, without a number, until the setting loads', () => {
    render(<PrivacyNote keptDays={null} />)
    expect(screen.getByText(/we delete it on a set schedule/)).toBeInTheDocument()
  })

  it('asks one question per screen, then sends the quiz, uploads the photo and confirms it', async () => {
    const calls = stubApi()
    await answerEverything()

    expect(await screen.findByRole('heading', { name: 'Request sent' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See your request' })).toHaveAttribute('href', `/m/${TOKEN}`)
    const create = calls.find((c) => c.url === '/api/public/match-requests')!
    expect(create.body).toEqual({
      email: 'ada@example.com',
      firstName: 'Ada',
      hairType: 'CURLY',
      texture: 'THICK',
      budgetTier: 2,
      neighborhood: 'Clintonville',
      service: 'CUT',
      pastIssues: ['DIDNT_LISTEN'],
      inspiration: { contentType: 'image/jpeg', sizeBytes: 10 },
    })
    expect(screen.getByText('Your photo is private and deleted after 21 days.')).toBeInTheDocument()
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

  it('says why when the address already has requests open', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url === '/api/public/intake/settings'
          ? json(200, { photoKeptDays: 21, maxPhotoBytes: 26214400 })
          : json(429, { detail: 'You already have requests in progress.', code: 'TOO_MANY_OPEN_REQUESTS' }),
      ),
    )
    await answerEverything()
    expect(await screen.findByRole('alert')).toHaveTextContent('You already have requests in progress.')
  })
})
