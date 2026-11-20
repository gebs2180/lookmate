export const DEFAULT_MARKET = 'columbus-oh'
export const DEFAULT_BRAND = 'SALON_LOFTS'

export type LocationLine = { name: string; address: string | null; city: string | null; state: string | null; zip: string | null }

/**
 * One location per line, as pasted from a spreadsheet or the brand's site: "Name, street, city, state, zip" (only the
 * name is required) or the same separated by tabs. Blank lines are skipped.
 */
export function parseLocations(text: string): LocationLine[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [name, address, city, state, zip] = line.split(line.includes('\t') ? '\t' : ',').map((p) => p.trim())
      const or = (s: string | undefined) => (s ? s : null)
      return { name, address: or(address), city: or(city), state: or(state), zip: or(zip) }
    })
}
