import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from '../../App'
import { stubApi } from '../../stylists/testing'
import { auth0, respond } from '../../test/auth0'
import { isPublicPath } from './publicPaths'

const TOKEN = 'u'.repeat(43)

function open() {
  render(
    <MemoryRouter initialEntries={[`/digest/unsubscribe/${TOKEN}`]}>
      <App />
    </MemoryRouter>,
  )
}

describe('the digest unsubscribe page', () => {
  it('needs no sign-in and unsubscribes only on the button', async () => {
    const calls = stubApi({ [`POST /api/public/digest/unsubscribe/${TOKEN}`]: () => respond(204) })
    open()

    expect(await screen.findByRole('heading', { name: 'Stop the weekly email?' })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
    await userEvent.click(screen.getByRole('button', { name: 'Unsubscribe' }))

    expect(await screen.findByRole('heading', { name: "You're unsubscribed" })).toBeInTheDocument()
    expect(calls[0].headers).not.toHaveProperty('Authorization')
    expect(auth0.getAccessTokenSilently).not.toHaveBeenCalled()
  })

  it('says so when the link is no good', async () => {
    stubApi({})
    open()

    await userEvent.click(await screen.findByRole('button', { name: 'Unsubscribe' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This link has expired.')
  })

  it('is public, while the Booked links are not', () => {
    expect(isPublicPath(`/digest/unsubscribe/${TOKEN}`)).toBe(true)
    expect(isPublicPath(`/digest/${TOKEN}`)).toBe(false)
  })
})
