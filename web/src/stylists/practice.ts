// Wording for what to expect in a stylist's chair (docs/research/beyond-the-hair.md). Profile only: never a filter,
// never in ranking. An unticked label is never shown as "No"; it just isn't shown.
import {
  SERVICE_LABEL,
  type Consultation,
  type FirstVisit,
  type Language,
  type NewClients,
  type NewClientStatus,
  type Practice,
  type PracticeLabel,
} from './types'

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

// New-client status (feature idea 3): shown only, never a filter and never in ranking or matching.

/** What the stylist picks on their profile form. */
export const NEW_CLIENTS_FORM: Record<NewClientStatus, string> = {
  TAKING: "I'm taking new clients",
  TAKING_WITH_WAIT: "I'm taking new clients, with a wait",
  NOT_TAKING: "I'm not taking new clients right now",
}

/** "Taking new clients · usually about 3 weeks out", in plain words for clients. */
export function newClientsLine(newClients: NewClients | null | undefined): string | null {
  if (!newClients) return null
  switch (newClients.status) {
    case 'TAKING':
      return 'Taking new clients'
    case 'TAKING_WITH_WAIT': {
      const weeks = newClients.weeksOut ?? 0
      if (weeks < 1) return 'Taking new clients'
      return `Taking new clients · usually about ${weeks === 1 ? 'a week' : `${weeks} weeks`} out`
    }
    case 'NOT_TAKING':
      return 'Not taking new clients right now'
  }
}

// Before your first visit (feature idea 1): the stylist's own facts first, then the generic notes.

export const FIRST_VISIT_FORM = {
  colourConsultationFirst: 'New colour clients need a consultation first',
  depositForNewClients: 'I ask new clients for a deposit when they book',
} as const

/** The stylist's own facts as clients read them, in order: the two ticks, then their note. */
export function firstVisitFacts(firstVisit: FirstVisit | null | undefined): string[] {
  if (!firstVisit) return []
  return [
    firstVisit.colourConsultationFirst ? 'New colour clients book a consultation first.' : null,
    firstVisit.depositForNewClients ? 'Asks new clients for a deposit when they book (paid to the stylist, not Combpatible).' : null,
  ].filter((f): f is string => f !== null)
}
