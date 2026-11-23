/** Display helpers shared by the pages. */

/** A 0..1 share as a percent with at most one decimal. */
export function percent(share: number) {
  return `${Math.round(share * 1000) / 10}%`
}

/** An enum value as lower-case words, e.g. "IN_REVIEW" becomes "in review". */
export function label(value: string) {
  return value.replaceAll('_', ' ').toLowerCase()
}

/** A date the short way a phone shows it, "Nov 16". */
export function shortDate(at: string | Date) {
  return new Date(at).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
}

/** The text of a thrown value. */
export function message(err: unknown) {
  return err instanceof Error ? err.message : String(err)
}
