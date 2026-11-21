// Wording for what to expect in a stylist's chair (docs/research/beyond-the-hair.md). Profile only: never a filter,
// never in ranking. An unticked label is never shown as "No"; it just isn't shown.
import { SERVICE_LABEL, type Consultation, type Language, type Practice, type PracticeLabel } from './types'

/** What the stylist ticks on their profile form: the promise, in their words. */
export const LABEL_FORM: Record<PracticeLabel, string> = {
  GENDER_NEUTRAL_PRICING: 'Gender-neutral pricing: my prices depend on the service and your hair, not your gender.',
  LGBTQ_AFFIRMING: "LGBTQ+-affirming: I use clients' names and pronouns and style for how they want to look.",
  SENSORY_FRIENDLY: 'Sensory-friendly on request: music down or off, unscented products, scissors instead of clippers.',
  KIDS_WELCOME: "Kids' cuts welcome",
}

/** What clients see on the profile. */
export const LABEL_PUBLIC: Record<PracticeLabel, string> = {
  GENDER_NEUTRAL_PRICING: 'Gender-neutral pricing',
  LGBTQ_AFFIRMING: 'LGBTQ+-affirming',
  SENSORY_FRIENDLY: 'Sensory-friendly on request',
  KIDS_WELCOME: "Kids' cuts welcome",
}

export const LANGUAGE_NAME: Record<Language, string> = {
  SPANISH: 'Spanish',
  SOMALI: 'Somali',
  NEPALI: 'Nepali',
  ARABIC: 'Arabic',
  AMHARIC: 'Amharic',
  FRENCH: 'French',
  MANDARIN: 'Mandarin',
  PORTUGUESE: 'Portuguese',
  VIETNAMESE: 'Vietnamese',
  ASL: 'ASL',
}

export const CONSULTATION_FORM: Record<Consultation, string> = {
  FREE: 'Free',
  PAID: 'Paid',
  INCLUDED: 'Included with the appointment',
}

export const CONSULTATION_PUBLIC: Record<Consultation, string> = {
  FREE: 'Free consultation',
  PAID: 'Consultation available (paid)',
  INCLUDED: 'Consultation included',
}

export const PRICE_DEPENDS = 'Price depends on length and density. Ask at your consultation.'

/** "Also speaks Spanish and Somali". Null when English only. */
export function languagesLine(languages: Language[]): string | null {
  const names = languages.map((l) => LANGUAGE_NAME[l])
  if (names.length === 0) return null
  const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
  return `Also speaks ${list}`
}

/** "Cut from $65 · Color from $120", the first `limit` services only. Null when there are no prices. */
export function fromPriceLine(practice: Practice | null | undefined, limit = Infinity): string | null {
  const prices = practice?.fromPrices ?? []
  if (prices.length === 0) return null
  return prices
    .slice(0, limit)
    .map((p) => `${SERVICE_LABEL[p.service]} from $${p.fromDollars.toLocaleString('en-US')}`)
    .join(' · ')
}

/** True when there's anything to show in "What to expect in my chair". */
export function hasPractice(practice: Practice | null | undefined): practice is Practice {
  return (
    !!practice &&
    (practice.labels.length > 0 ||
      practice.languages.length > 0 ||
      practice.fromPrices.length > 0 ||
      practice.consultation !== null ||
      practice.priceDependsOnHair)
  )
}
