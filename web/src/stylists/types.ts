// Types for the stylists module's API (docs/modules/stylists.md). Hand-written; the backend's records are in
// com.lookmate.app.stylists (ProfileService, PhotoService, LocationController, StylistController).

export const SERVICES = [
  'CUT',
  'COLOR',
  'BALAYAGE',
  'HIGHLIGHTS',
  'EXTENSIONS',
  'BRAIDS',
  'LOCS',
  'SILK_PRESS',
  'PERM',
  'TREATMENT',
  'STYLING',
  'OTHER',
] as const
export type ServiceType = (typeof SERVICES)[number]

export const HAIR_TYPES = ['STRAIGHT', 'WAVY', 'CURLY', 'COILY'] as const
export type HairType = (typeof HAIR_TYPES)[number]

export const TEXTURES = ['FINE', 'MEDIUM', 'THICK'] as const
export type Texture = (typeof TEXTURES)[number]

export const LENGTHS = ['PIXIE', 'SHORT', 'MEDIUM', 'LONG'] as const
export type HairLength = (typeof LENGTHS)[number]

export type ProfileStatus = 'DRAFT' | 'LIVE' | 'HIDDEN'
export type BookingPlatform = 'BOOKSY' | 'VAGARO' | 'STYLESEAT' | 'GLOSSGENIUS' | 'SQUARE' | 'OTHER'
export type Missing = 'TERMS' | 'PROFILE' | 'LOCATION' | 'BOOKING_URL' | 'TAGGED_PHOTOS'

export const SERVICE_LABEL: Record<ServiceType, string> = {
  CUT: 'Cut',
  COLOR: 'Color',
  BALAYAGE: 'Balayage',
  HIGHLIGHTS: 'Highlights',
  EXTENSIONS: 'Extensions',
  BRAIDS: 'Braids',
  LOCS: 'Locs',
  SILK_PRESS: 'Silk press',
  PERM: 'Perm',
  TREATMENT: 'Treatment',
  STYLING: 'Styling',
  OTHER: 'Other',
}

export const HAIR_TYPE_LABEL: Record<HairType, string> = {
  STRAIGHT: 'Straight',
  WAVY: 'Wavy',
  CURLY: 'Curly',
  COILY: 'Coily',
}

export const PLATFORM_LABEL: Record<BookingPlatform, string> = {
  BOOKSY: 'Booksy',
  VAGARO: 'Vagaro',
  STYLESEAT: 'StyleSeat',
  GLOSSGENIUS: 'GlossGenius',
  SQUARE: 'Square',
  OTHER: 'your own site',
}

export type Location = {
  id: string
  brand: string
  name: string
  market: string
  address: string | null
  city: string | null
  state: string | null
  zip: string | null
  latitude: number | null
  longitude: number | null
}

export type AdminLocation = Location & { active: boolean; createdAt: string }

export type Profile = {
  displayName: string
  locationId: string | null
  market: string
  bio: string | null
  specialties: ServiceType[]
  bookingUrl: string | null
  bookingPlatform: BookingPlatform | null
  instagram: string | null
  priceTier: number | null
  status: ProfileStatus
  publishedAt: string | null
  updatedAt: string
  /** A made-up stylist for the proof of concept. */
  demo: boolean
  /** Their public page's address: combpatible.app/s/<slug>. */
  slug: string
  practice: Practice
  /** When the new-client status was last set or confirmed. */
  newClientsConfirmedAt: string
  /** True once that's over 30 days ago: the dashboard asks "still accurate?". */
  newClientsConfirmDue: boolean
}

export type ProfileRequest = {
  displayName: string
  locationId: string | null
  bio: string | null
  specialties: ServiceType[]
  bookingUrl: string | null
  instagram: string | null
  priceTier: number | null
  /** The whole "What to expect in my chair" section; all optional. */
  practice: PracticeRequest | null
  /** Left out or null: unchanged. Saving it counts as confirming it. */
  newClients?: NewClients | null
  /** The public page's address; left out or null: unchanged. */
  slug?: string | null
}

export type StylistHome = {
  orgId: string
  orgName: string | null
  termsAccepted: boolean
  profile: Profile | null
  readyPhotos: number
  taggedPhotos: number
  minTaggedPhotos: number
  maxPhotos: number
  missing: Missing[]
}

export type PhotoStatus = 'PENDING_UPLOAD' | 'READY' | 'HIDDEN' | 'DELETED'

export type PortfolioPhoto = {
  id: string
  status: PhotoStatus
  url: string | null
  contentType: string
  width: number | null
  height: number | null
  service: ServiceType | null
  hairType: HairType | null
  texture: Texture | null
  length: HairLength | null
  before: boolean
  pairPhotoId: string | null
  position: number
  createdAt: string
}

export type TagRequest = {
  service: ServiceType | null
  hairType: HairType | null
  texture: Texture | null
  length: HairLength | null
  before: boolean
  pairPhotoId: string | null
}

export type ConsentStatus = { version: string; text: string; acceptedAt: string | null }

export type AdminStylist = {
  orgId: string
  orgName: string | null
  status: ProfileStatus | null
  displayName: string | null
  market: string | null
  bookingPlatform: BookingPlatform | null
  readyPhotos: number
  taggedPhotos: number
  publishedAt: string | null
  demo: boolean
}

// What to expect in a stylist's chair (decided 2026-11-20): shown on the profile only, never a filter or in ranking.
// Wording in ./practice.ts.
export const PRACTICE_LABELS = ['GENDER_NEUTRAL_PRICING', 'LGBTQ_AFFIRMING', 'SENSORY_FRIENDLY', 'KIDS_WELCOME'] as const
export type PracticeLabel = (typeof PRACTICE_LABELS)[number]

/** Besides English, which is implied. */
export const LANGUAGES = ['SPANISH', 'SOMALI', 'NEPALI', 'ARABIC', 'AMHARIC', 'FRENCH', 'MANDARIN', 'PORTUGUESE', 'VIETNAMESE', 'ASL'] as const
export type Language = (typeof LANGUAGES)[number]

export const CONSULTATIONS = ['FREE', 'PAID', 'INCLUDED'] as const
export type Consultation = (typeof CONSULTATIONS)[number]

export type FromPrice = { service: ServiceType; fromDollars: number }

export type Practice = {
  labels: PracticeLabel[]
  languages: Language[]
  /** In the order of the specialties; only services offered. */
  fromPrices: FromPrice[]
  consultation: Consultation | null
  priceDependsOnHair: boolean
  /** The stylist's own "Before your first visit" facts; shown before the generic notes. */
  firstVisit: FirstVisit
  /** Shown only: never removes a stylist from matching or the directory, never changes the order. */
  newClients: NewClients
}

/** The new-client status is saved apart from the practice section (ProfileRequest.newClients). */
export type PracticeRequest = Partial<Omit<Practice, 'newClients' | 'firstVisit'>> & { firstVisit?: Partial<FirstVisit> | null }

export const FIRST_VISIT_NOTE_MAX = 280

export type FirstVisit = {
  /** New colour clients need a consultation first. */
  colourConsultationFirst: boolean
  /** A fact only: the stylist takes it, never Combpatible. */
  depositForNewClients: boolean
  note: string | null
}

export const NEW_CLIENT_STATUSES = ['TAKING', 'TAKING_WITH_WAIT', 'NOT_TAKING'] as const
export type NewClientStatus = (typeof NEW_CLIENT_STATUSES)[number]
export const NEW_CLIENTS_MAX_WEEKS = 26

/** weeksOut is set only with TAKING_WITH_WAIT. */
export type NewClients = { status: NewClientStatus; weeksOut: number | null }

/** A generic "Before your first visit" note (Jason's sheet; our draft until his arrives). */
export type FirstVisitNote = { key: string; text: string }

/** "SILK_PRESS" as "Silk press". */
export function words(value: string) {
  const lower = value.replaceAll('_', ' ').toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

/** One photo on a stylist's public page. */
export type PagePhoto = {
  photoId: string
  url: string
  service: ServiceType | null
  hairType: HairType | null
  before: boolean
  width: number | null
  height: number | null
}

/** A stylist's public page (/s/<slug>) or the directory's portfolio dialog. hairType null: untagged photos. */
export type StylistPublicPage = {
  orgId: string
  /** Null for demo stylists: they have no page of their own. */
  slug: string | null
  displayName: string
  locationName: string | null
  city: string | null
  bio: string | null
  specialties: ServiceType[]
  instagram: string | null
  demo: boolean
  practice: Practice
  photoGroups: { hairType: HairType | null; photos: PagePhoto[] }[]
  /** The "Not sure what to book?" link, only for the finder partner. */
  finderPath: string | null
}
