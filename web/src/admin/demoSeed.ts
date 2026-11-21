import { apiPath, type Org, type UploadSlot } from '../api'
import {
  CONSULTATIONS,
  LANGUAGES,
  type Location,
  type PortfolioPhoto,
  type Practice,
  type PracticeLabel,
  type Profile,
  type ServiceType,
} from '../stylists/types'
import type { DemoPhoto, DemoStylist } from './demoStylists'

type Api = <T>(method: string, path: string, body?: unknown) => Promise<T>

export type SeedDeps = {
  api: Api
  upload: (slot: UploadSlot, file: Blob) => Promise<void>
  /** The photo for one slot: the admin's own file if they picked one with this name, else a drawn placeholder. */
  photoFor: (photo: DemoPhoto, stylist: DemoStylist) => Promise<Blob>
  progress: (line: string) => void
  /** For tests; Math.random otherwise. */
  random?: () => number
}

/** A mid-range ($$) starting price per service, in dollars: made up, roughly Columbus salon-suite prices. */
const BASE_PRICE: Record<ServiceType, number> = {
  CUT: 55,
  COLOR: 110,
  BALAYAGE: 210,
  HIGHLIGHTS: 150,
  EXTENSIONS: 400,
  BRAIDS: 180,
  LOCS: 120,
  SILK_PRESS: 75,
  PERM: 120,
  TREATMENT: 45,
  STYLING: 50,
  OTHER: 60,
}
const TIER_SCALE = [0.7, 1, 1.4, 1.9]
const LABEL_ODDS: Record<PracticeLabel, number> = {
  GENDER_NEUTRAL_PRICING: 0.5,
  LGBTQ_AFFIRMING: 0.45,
  SENSORY_FRIENDLY: 0.25,
  KIDS_WELCOME: 0.35,
}
const PRICED_BY_HAIR: ServiceType[] = ['BRAIDS', 'LOCS', 'EXTENSIONS', 'SILK_PRESS']

/**
 * What to expect in a made-up stylist's chair, some of it at random so the demo shows a spread: labels, maybe a
 * language, a from-price per service (scaled by price tier, rounded to $5) and a consultation, or none.
 */
export function randomPractice(specialties: ServiceType[], priceTier: number | null, random: () => number = Math.random): Practice {
  const scale = TIER_SCALE[(priceTier ?? 2) - 1] ?? 1
  const pick = <T,>(xs: readonly T[]) => xs[Math.floor(random() * xs.length)]
  const consultation = pick([...CONSULTATIONS, null])
  return {
    labels: (Object.keys(LABEL_ODDS) as PracticeLabel[]).filter((l) => random() < LABEL_ODDS[l]),
    languages: random() < 0.3 ? [pick(LANGUAGES)] : [],
    fromPrices: specialties.map((service) => ({
      service,
      fromDollars: Math.max(5, Math.round((BASE_PRICE[service] * scale * (0.85 + random() * 0.3)) / 5) * 5),
    })),
    consultation,
    priceDependsOnHair: random() < (specialties.some((s) => PRICED_BY_HAIR.includes(s)) ? 0.6 : 0.2),
  }
}

/**
 * Creates each made-up stylist through the same API a real one uses, acting as the admin: an org, the profile (on one
 * of the active locations, round robin), marked demo, the photos uploaded and tagged (before/after pairs too), then
 * published. Returns the new org ids.
 */
export async function seedDemoStylists(stylists: DemoStylist[], locations: Location[], deps: SeedDeps): Promise<string[]> {
  if (locations.length === 0) throw new Error('Add at least one active location first: demo stylists need one to go live.')
  const { api, upload, photoFor, progress, random = Math.random } = deps
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
      practice: randomPractice(specialties, s.priceTier, random),
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
