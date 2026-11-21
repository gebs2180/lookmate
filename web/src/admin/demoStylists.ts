import type { HairLength, HairType, ProfileRequest, ServiceType, Texture } from '../stylists/types'

// Made-up stylists for the proof of concept (Zach, 2026-11-20), until real ones join. Every name and bio is
// invented. Booking links go to example.com, so a click is tracked but books nothing. Hair types are spread so
// matching on curly and coily hair has something to find (CLAUDE.md: fairness across hair types).

export type DemoPhoto = {
  /** Pick a photo with this file name on the demo page to use it instead of a drawn placeholder. */
  file: string
  caption: string
  service: ServiceType
  hairType: HairType
  texture: Texture
  length: HairLength
  /** The "before" half of a pair whose "after" is the next photo in the list. */
  beforeOfNext?: boolean
}

/** Practice (labels, prices) is drawn at random when seeding: see randomPractice in demoSeed.ts. */
export type DemoStylist = Omit<ProfileRequest, 'locationId' | 'specialties' | 'bookingUrl' | 'practice'> & {
  slug: string
  photos: DemoPhoto[]
}

const p = (file: string, caption: string, service: ServiceType, hairType: HairType, texture: Texture, length: HairLength, beforeOfNext = false): DemoPhoto => ({
  file,
  caption,
  service,
  hairType,
  texture,
  length,
  beforeOfNext,
})

export const DEMO_STYLISTS: DemoStylist[] = [
  {
    slug: 'maya-brooks',
    displayName: 'Maya Brooks',
    bio: 'Curl specialist. Dry cuts, shaped for the way your curls actually fall.',
    instagram: null,
    priceTier: 3,
    photos: [
      p('maya-1.jpg', 'Before: grown-out curls', 'CUT', 'CURLY', 'MEDIUM', 'LONG', true),
      p('maya-2.jpg', 'After: dry-cut layers', 'CUT', 'CURLY', 'MEDIUM', 'MEDIUM'),
      p('maya-3.jpg', 'Rounded coily shape', 'CUT', 'COILY', 'THICK', 'SHORT'),
      p('maya-4.jpg', 'Curly shag', 'CUT', 'CURLY', 'FINE', 'MEDIUM'),
      p('maya-5.jpg', 'Deep-conditioning treatment', 'TREATMENT', 'COILY', 'THICK', 'MEDIUM'),
    ],
  },
  {
    slug: 'jordan-lee',
    displayName: 'Jordan Lee',
    bio: 'Lived-in color and soft balayage. Low-maintenance blondes.',
    instagram: null,
    priceTier: 3,
    photos: [
      p('jordan-1.jpg', 'Before: box dye', 'BALAYAGE', 'STRAIGHT', 'FINE', 'LONG', true),
      p('jordan-2.jpg', 'After: soft balayage', 'BALAYAGE', 'STRAIGHT', 'FINE', 'LONG'),
      p('jordan-3.jpg', 'Bronde balayage', 'BALAYAGE', 'WAVY', 'MEDIUM', 'LONG'),
      p('jordan-4.jpg', 'Copper all-over color', 'COLOR', 'WAVY', 'MEDIUM', 'MEDIUM'),
      p('jordan-5.jpg', 'Face-framing highlights', 'HIGHLIGHTS', 'STRAIGHT', 'MEDIUM', 'MEDIUM'),
    ],
  },
  {
    slug: 'priya-nair',
    displayName: 'Priya Nair',
    bio: 'Balayage and highlights on thick, wavy and curly hair.',
    instagram: null,
    priceTier: 2,
    photos: [
      p('priya-1.jpg', 'Caramel balayage on curls', 'BALAYAGE', 'CURLY', 'THICK', 'LONG'),
      p('priya-2.jpg', 'Chunky highlights', 'HIGHLIGHTS', 'WAVY', 'THICK', 'LONG'),
      p('priya-3.jpg', 'Honey balayage', 'BALAYAGE', 'WAVY', 'THICK', 'MEDIUM'),
      p('priya-4.jpg', 'Long layers', 'CUT', 'WAVY', 'THICK', 'LONG'),
      p('priya-5.jpg', 'Highlights on coils', 'HIGHLIGHTS', 'COILY', 'MEDIUM', 'MEDIUM'),
    ],
  },
  {
    slug: 'tasha-greene',
    displayName: 'Tasha Greene',
    bio: 'Knotless braids, boho braids and protective styles that last.',
    instagram: null,
    priceTier: 2,
    photos: [
      p('tasha-1.jpg', 'Knotless braids', 'BRAIDS', 'COILY', 'THICK', 'LONG'),
      p('tasha-2.jpg', 'Boho braids', 'BRAIDS', 'COILY', 'MEDIUM', 'LONG'),
      p('tasha-3.jpg', 'Stitch braids', 'BRAIDS', 'COILY', 'THICK', 'MEDIUM'),
      p('tasha-4.jpg', 'Braided bob', 'BRAIDS', 'CURLY', 'MEDIUM', 'SHORT'),
      p('tasha-5.jpg', 'Twist-out styling', 'STYLING', 'COILY', 'THICK', 'SHORT'),
    ],
  },
  {
    slug: 'elena-ruiz',
    displayName: 'Elena Ruiz',
    bio: 'Precision cuts: bobs, pixies and blunt lines.',
    instagram: null,
    priceTier: 2,
    photos: [
      p('elena-1.jpg', 'Before: one length', 'CUT', 'STRAIGHT', 'THICK', 'LONG', true),
      p('elena-2.jpg', 'After: blunt bob', 'CUT', 'STRAIGHT', 'THICK', 'SHORT'),
      p('elena-3.jpg', 'Textured pixie', 'CUT', 'WAVY', 'FINE', 'PIXIE'),
      p('elena-4.jpg', 'French bob', 'CUT', 'WAVY', 'MEDIUM', 'SHORT'),
      p('elena-5.jpg', 'Curtain bangs', 'CUT', 'STRAIGHT', 'FINE', 'MEDIUM'),
    ],
  },
  {
    slug: 'sam-okafor',
    displayName: 'Sam Okafor',
    bio: 'Starter locs, retwists and silk presses. Healthy hair first.',
    instagram: null,
    priceTier: 2,
    photos: [
      p('sam-1.jpg', 'Starter locs', 'LOCS', 'COILY', 'THICK', 'SHORT'),
      p('sam-2.jpg', 'Loc retwist and style', 'LOCS', 'COILY', 'MEDIUM', 'MEDIUM'),
      p('sam-3.jpg', 'Before: natural', 'SILK_PRESS', 'COILY', 'MEDIUM', 'MEDIUM', true),
      p('sam-4.jpg', 'After: silk press', 'SILK_PRESS', 'COILY', 'MEDIUM', 'MEDIUM'),
      p('sam-5.jpg', 'Silk press on curls', 'SILK_PRESS', 'CURLY', 'THICK', 'LONG'),
    ],
  },
  {
    slug: 'claire-dubois',
    displayName: 'Claire Dubois',
    bio: 'Hand-tied extensions and glossy color.',
    instagram: null,
    priceTier: 4,
    photos: [
      p('claire-1.jpg', 'Before: fine, shoulder length', 'EXTENSIONS', 'STRAIGHT', 'FINE', 'MEDIUM', true),
      p('claire-2.jpg', 'After: hand-tied extensions', 'EXTENSIONS', 'STRAIGHT', 'FINE', 'LONG'),
      p('claire-3.jpg', 'Volume extensions', 'EXTENSIONS', 'WAVY', 'MEDIUM', 'LONG'),
      p('claire-4.jpg', 'Glossy brunette', 'COLOR', 'STRAIGHT', 'MEDIUM', 'LONG'),
      p('claire-5.jpg', 'Blowout', 'STYLING', 'WAVY', 'MEDIUM', 'LONG'),
    ],
  },
  {
    slug: 'nia-carter',
    displayName: 'Nia Carter',
    bio: 'Color for curls and coils, without the damage.',
    instagram: null,
    priceTier: 3,
    photos: [
      p('nia-1.jpg', 'Copper curls', 'COLOR', 'CURLY', 'MEDIUM', 'MEDIUM'),
      p('nia-2.jpg', 'Honey highlights on coils', 'HIGHLIGHTS', 'COILY', 'THICK', 'MEDIUM'),
      p('nia-3.jpg', 'Burgundy tapered cut', 'COLOR', 'COILY', 'THICK', 'SHORT'),
      p('nia-4.jpg', 'Balayage on 3C curls', 'BALAYAGE', 'CURLY', 'THICK', 'LONG'),
      p('nia-5.jpg', 'Curly cut and color', 'CUT', 'CURLY', 'MEDIUM', 'MEDIUM'),
    ],
  },
]
