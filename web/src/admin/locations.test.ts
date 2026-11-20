import { describe, expect, it } from 'vitest'
import { parseLocations } from './locations'

describe('parseLocations', () => {
  it('reads comma lines, tab lines and name-only lines, skipping blanks', () => {
    expect(
      parseLocations('Short North, 123 N High St, Columbus, OH, 43215\n\nEaston\tTown Square\tColumbus\tOH\t43219\r\nPolaris\n'),
    ).toEqual([
      { name: 'Short North', address: '123 N High St', city: 'Columbus', state: 'OH', zip: '43215' },
      { name: 'Easton', address: 'Town Square', city: 'Columbus', state: 'OH', zip: '43219' },
      { name: 'Polaris', address: null, city: null, state: null, zip: null },
    ])
  })
})
