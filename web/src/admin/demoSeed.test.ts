import { describe, expect, it, vi } from 'vitest'
import { seedDemoStylists } from './demoSeed'
import { DEMO_STYLISTS, type DemoStylist } from './demoStylists'
import type { Location } from '../stylists/types'

const LOCATION: Location = { id: 'loc-1', brand: 'SALON_LOFTS', name: 'Short North', market: 'columbus-oh', address: null, city: null, state: null, zip: null, latitude: null, longitude: null }

const STYLIST: DemoStylist = {
  slug: 'test-stylist',
  displayName: 'Test Stylist',
  bio: 'Made up',
  instagram: null,
  priceTier: 2,
  photos: [
    { file: 'a.jpg', caption: 'Before', service: 'CUT', hairType: 'CURLY', texture: 'MEDIUM', length: 'LONG', beforeOfNext: true },
    { file: 'b.jpg', caption: 'After', service: 'CUT', hairType: 'CURLY', texture: 'MEDIUM', length: 'SHORT' },
    { file: 'c.jpg', caption: 'Braids', service: 'BRAIDS', hairType: 'COILY', texture: 'THICK', length: 'LONG' },
  ],
}

describe('seedDemoStylists', () => {
  it('creates the org, profile, demo mark, photos with tags and pairs, then publishes', async () => {
    const calls: { method: string; path: string; body?: unknown }[] = []
    let n = 0
    const api = vi.fn(async (method: string, path: string, body?: unknown) => {
      calls.push({ method, path, body })
      if (path === '/api/admin/orgs') return { id: 'org-1', name: 'Demo: Test Stylist' }
      if (method === 'POST' && path === '/api/orgs/org-1/photos') return { photoId: `p${++n}`, url: 'u', method: 'PUT', headers: {}, expiresAt: '' }
      return {}
    }) as never
    const upload = vi.fn(async () => {})

    const ids = await seedDemoStylists([STYLIST], [LOCATION], {
      api,
      upload,
      photoFor: async () => new Blob(['x'], { type: 'image/jpeg' }),
      progress: () => {},
    })

    expect(ids).toEqual(['org-1'])
    expect(calls[0].body).toEqual({ name: 'Demo: Test Stylist', type: 'STYLIST' })
    expect(calls[1].body).toMatchObject({ locationId: 'loc-1', specialties: ['CUT', 'BRAIDS'], bookingUrl: 'https://example.com/book/test-stylist' })
    expect(calls[2].path).toBe('/api/admin/stylists/org-1/demo')
    expect(upload).toHaveBeenCalledTimes(3)
    const tags = calls.filter((c) => c.method === 'PUT' && c.path.endsWith('/tags'))
    expect(tags.map((t) => [t.path, (t.body as { pairPhotoId: string | null }).pairPhotoId, (t.body as { before: boolean }).before])).toEqual([
      ['/api/orgs/org-1/photos/p2/tags', null, false],
      ['/api/orgs/org-1/photos/p1/tags', 'p2', true],
      ['/api/orgs/org-1/photos/p3/tags', null, false],
    ])
    expect(calls.at(-1)?.path).toBe('/api/orgs/org-1/stylist/publish')
  })

  it('needs a location', async () => {
    await expect(seedDemoStylists([STYLIST], [], { api: vi.fn() as never, upload: vi.fn(), photoFor: vi.fn(), progress: vi.fn() })).rejects.toThrow(
      /Add at least one active location/,
    )
  })

  it('has made-up stylists covering every hair type, with enough tagged photos to go live', () => {
    const types = new Set(DEMO_STYLISTS.flatMap((s) => s.photos.map((p) => p.hairType)))
    expect([...types].sort()).toEqual(['COILY', 'CURLY', 'STRAIGHT', 'WAVY'])
    expect(DEMO_STYLISTS.every((s) => s.photos.length >= 3)).toBe(true)
    expect(new Set(DEMO_STYLISTS.flatMap((s) => s.photos.map((p) => p.file))).size).toBe(DEMO_STYLISTS.length * 5)
  })
})
