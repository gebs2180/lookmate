import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from '../../App'

describe('client pages', () => {
  it('open without signing in, outside the signed-in shell', () => {
    render(
      <MemoryRouter initialEntries={['/find']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: 'What do you want done?' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stylist log in' })).not.toBeInTheDocument()
  })

  it('are offered on the signed-out home page', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'Find my stylist' })).toHaveAttribute('href', '/find')
  })
})
