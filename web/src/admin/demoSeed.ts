import { apiPath, type Org, type UploadSlot } from '../api'
import type { Location, PortfolioPhoto, Profile, ServiceType } from '../stylists/types'
import type { DemoPhoto, DemoStylist } from './demoStylists'

type Api = <T>(method: string, path: string, body?: unknown) => Promise<T>

export type SeedDeps = {
  api: Api
  upload: (slot: UploadSlot, file: Blob) => Promise<void>
  /** The photo for one slot: the admin's own file if they picked one with this name, else a drawn placeholder. */
  photoFor: (photo: DemoPhoto, stylist: DemoStylist) => Promise<Blob>
  progress: (line: string) => void
}

/**
 * Creates each made-up stylist through the same API a real one uses, acting as the admin: an org, the profile (on one
 * of the active locations, round robin), marked demo, the photos uploaded and tagged (before/after pairs too), then
 * published. Returns the new org ids.
 */
export async function seedDemoStylists(stylists: DemoStylist[], locations: Location[], deps: SeedDeps): Promise<string[]> {
  if (locations.length === 0) throw new Error('Add at least one active location first: demo stylists need one to go live.')
  const { api, upload, photoFor, progress } = deps
  const created: string[] = []
  for (const [i, s] of stylists.entries()) {
    progress(`${s.displayName}: creating`)
    const org = await api<Org>('POST', '/api/admin/orgs', { name: `Demo: ${s.displayName}`, type: 'STYLIST' })
    const specialties = [...new Set(s.photos.map((ph) => ph.service))] as ServiceType[]
    await api<Profile>('PUT', apiPath`/api/orgs/${org.id}/stylist/profile`, {
      displayName: s.displayName,
      locationId: locations[i % locations.length].id,
      bio: s.bio,
      specialties,
      bookingUrl: `https://example.com/book/${s.slug}`,
      instagram: s.instagram,
      priceTier: s.priceTier,
    })
    await api<Profile>('POST', apiPath`/api/admin/stylists/${org.id}/demo`)

    const ids: string[] = []
    for (const photo of s.photos) {
      const file = await photoFor(photo, s)
      const slot = await api<UploadSlot>('POST', apiPath`/api/orgs/${org.id}/photos`, { contentType: file.type, sizeBytes: file.size })
      await upload(slot, file)
      await api<PortfolioPhoto>('POST', apiPath`/api/orgs/${org.id}/photos/${slot.photoId}/confirm`)
      ids.push(slot.photoId)
    }
    for (const [j, photo] of s.photos.entries()) {
      const pairsWithNext = photo.beforeOfNext && j + 1 < s.photos.length
      const pairedByPrevious = j > 0 && s.photos[j - 1].beforeOfNext
      // The "after" is tagged first, then the "before" pairs with it (pairing sets both halves).
      if (pairedByPrevious) continue
      const tag = (k: number, pair: string | null, before: boolean) =>
        api('PUT', apiPath`/api/orgs/${org.id}/photos/${ids[k]}/tags`, {
          service: s.photos[k].service,
          hairType: s.photos[k].hairType,
          texture: s.photos[k].texture,
          length: s.photos[k].length,
          before,
          pairPhotoId: pair,
        })
      if (pairsWithNext) {
        await tag(j + 1, null, false)
        await tag(j, ids[j + 1], true)
      } else {
        await tag(j, null, false)
      }
    }
    await api<Profile>('POST', apiPath`/api/orgs/${org.id}/stylist/publish`)
    progress(`${s.displayName}: live with ${ids.length} photos`)
    created.push(org.id)
  }
  return created
}
