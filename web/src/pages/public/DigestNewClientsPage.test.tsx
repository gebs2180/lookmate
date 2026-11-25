import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from '../../App'
import { stubApi } from '../../stylists/testing'
import { auth0, respond } from '../../test/auth0'
import { isPublicPath } from './publicPaths'

const TOKEN = 'n'.repeat(43)

function open() {
  render(
    <MemoryRouter initialEntries={[`/digest/new-clients/${TOKEN}`]}>
      <App />
    </MemoryRouter>,
  )
}

describe('the digest new-client status confirm page', () => {
  it('needs no sign-in and confirms only on the button', async () => {
    const calls = stubApi({
      [`POST /api/public/digest/new-clients/${TOKEN}`]: () =>
        respond(200, { newClients: { status: 'TAKING_WITH_WAIT', weeksOut: 3 } }),
    })
    open()

    expect(await screen.findByRole('heading', { name: 'Is your new-client status still right?' })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Yes, still right' }))

    expect(await screen.findByRole('heading', { name: 'Thanks, confirmed' })).toBeInTheDocument()
    expect(screen.getByText(/usually about 3 weeks out/)).toBeInTheDocument()
    expect(calls[0].headers).not.toHaveProperty('Authorization')
    expect(auth0.getAccessTokenSilently).not.toHaveBeenCalled()
  })

  it('says so when the link is no good', async () => {
    stubApi({})
    open()

    await userEvent.click(await screen.findByRole('button', { name: 'Yes, still right' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This link has expired.')
  })

  it('is public', () => {
    expect(isPublicPath(`/digest/new-clients/${TOKEN}`)).toBe(true)
  })
})
