import type { MatchRequestCreate } from '../../api'

/** The quiz's answer codes and the words clients see for them. Codes are the API's (V2 check constraints). */

export type Service = MatchRequestCreate['service']
export type HairType = MatchRequestCreate['hairType']
export type Texture = NonNullable<MatchRequestCreate['texture']>
export type Length = NonNullable<MatchRequestCreate['length']>
export type PastIssue = NonNullable<MatchRequestCreate['pastIssues']>[number]

export type Option<T extends string> = { value: T; label: string; hint?: string }

export const SERVICES: Option<Service>[] = [
  { value: 'CUT', label: 'Cut' },
  { value: 'COLOR', label: 'Color' },
  { value: 'BALAYAGE', label: 'Balayage' },
  { value: 'HIGHLIGHTS', label: 'Highlights' },
  { value: 'SILK_PRESS', label: 'Silk press' },
  { value: 'BRAIDS', label: 'Braids' },
  { value: 'LOCS', label: 'Locs' },
  { value: 'EXTENSIONS', label: 'Extensions' },
  { value: 'PERM', label: 'Perm' },
  { value: 'TREATMENT', label: 'Treatment' },
  { value: 'STYLING', label: 'Styling' },
  { value: 'OTHER', label: 'Something else' },
]

export const HAIR_TYPES: Option<HairType>[] = [
  { value: 'STRAIGHT', label: 'Straight', hint: 'Lies flat from root to tip' },
  { value: 'WAVY', label: 'Wavy', hint: 'Loose S-shaped bends' },
  { value: 'CURLY', label: 'Curly', hint: 'Defined loops or spirals' },
  { value: 'COILY', label: 'Coily', hint: 'Tight coils or zig-zags' },
]

export const TEXTURES: Option<Texture>[] = [
  { value: 'FINE', label: 'Fine', hint: 'A single strand is hard to feel' },
  { value: 'MEDIUM', label: 'Medium' },
  { value: 'THICK', label: 'Thick', hint: 'A single strand feels like thread' },
]

export const LENGTHS: Option<Length>[] = [
  { value: 'PIXIE', label: 'Pixie or cropped' },
  { value: 'SHORT', label: 'Short', hint: 'Above the shoulders' },
  { value: 'MEDIUM', label: 'Medium', hint: 'Shoulder length' },
  { value: 'LONG', label: 'Long', hint: 'Past the shoulders' },
]

export const BUDGETS: Option<'1' | '2' | '3' | '4'>[] = [
  { value: '1', label: '$', hint: 'Keep it affordable' },
  { value: '2', label: '$$', hint: 'Mid-range' },
  { value: '3', label: '$$$', hint: 'Happy to spend for the right stylist' },
  { value: '4', label: '$$$$', hint: 'Top of the range' },
]

export const PAST_ISSUES: Option<PastIssue>[] = [
  { value: 'DIDNT_LISTEN', label: "They didn't listen" },
  { value: 'NOT_THE_LOOK_I_ASKED_FOR', label: "It wasn't the look I asked for" },
  { value: 'CUT_TOO_SHORT', label: 'Cut too short' },
  { value: 'COLOR_CAME_OUT_WRONG', label: 'Color came out wrong' },
  { value: 'DAMAGED_MY_HAIR', label: 'Damaged my hair' },
  { value: 'NO_EXPERIENCE_WITH_MY_HAIR_TYPE', label: "Didn't know my hair type" },
  { value: 'RUSHED', label: 'Felt rushed' },
  { value: 'PRICE_SURPRISE', label: 'The price was a surprise' },
  { value: 'HARD_TO_BOOK', label: 'Hard to book' },
  { value: 'OTHER', label: 'Something else' },
]

/**
 * What the client asks of their next appointment: two chips on the past-issues step, sent in the same `pastIssues`
 * list (docs/research/beyond-the-hair.md, items 2 and 3). They never affect ranking; the results page turns them into a
 * note to paste when booking (`bookingNote`), since we can't pass them to the stylist's booking tool.
 */
export const ASKS: Option<PastIssue>[] = [
  { value: 'QUIET_APPOINTMENT', label: 'A quiet appointment' },
  { value: 'HONEST_ADVICE', label: 'Honest advice about what my hair can do' },
]

const BOOKING_NOTES: Partial<Record<PastIssue, string>> = {
  QUIET_APPOINTMENT: 'Quiet appointment, please.',
  HONEST_ADVICE: "I'd like honest advice about what my hair can do.",
}

/** The note for the stylist's booking form, from the client's asks; null when they asked for neither. */
export function bookingNote(asks: readonly string[] | null | undefined): string | null {
  const lines = ASKS.filter((a) => asks?.includes(a.value)).map((a) => BOOKING_NOTES[a.value])
  return lines.length > 0 ? lines.join(' ') : null
}

export function labelOf<T extends string>(options: Option<T>[], value: string | null | undefined) {
  return options.find((o) => o.value === value)?.label ?? null
}
