import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Haircut } from './Haircut'

describe('Haircut', () => {
  it('draws the client in the chair with hair, and is decoration only', () => {
    const { container } = render(<Haircut hairType="COILY" done={false} onDone={() => {}} />)
    const wrap = container.querySelector('.haircut')
    expect(wrap).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelectorAll('.hc-hair path').length).toBe(24)
    expect(container.querySelector('.hc-mass')?.getAttribute('d')).toMatch(/^M.*Z$/)
  })

  it("never holds the picks back when nobody can watch the finish", () => {
    // No IntersectionObserver here, so the loop counts as off screen: the cut finishes at once.
    const onDone = vi.fn()
    const { rerender } = render(<Haircut hairType="STRAIGHT" done={false} onDone={onDone} />)
    expect(onDone).not.toHaveBeenCalled()
    rerender(<Haircut hairType="STRAIGHT" done onDone={onDone} />)
    expect(onDone).toHaveBeenCalledTimes(1)
  })
})
