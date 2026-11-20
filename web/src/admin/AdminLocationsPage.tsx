import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, useApi } from '../api'
import { message } from '../format'
import type { AdminLocation } from '../stylists/types'
import { Chip, EmptyState, Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import { DEFAULT_BRAND, DEFAULT_MARKET, parseLocations } from './locations'

/** Admin: the salon locations stylists pick from. Add one, paste many, or switch one off. */
export function AdminLocationsPage() {
  const api = useApi()
  const { data: locations, error, reload } = useLoad(() => api<AdminLocation[]>('GET', '/api/admin/locations'), [api])
  const [market, setMarket] = useState(DEFAULT_MARKET)
  const [brand, setBrand] = useState(DEFAULT_BRAND)
  const [lines, setLines] = useState('')
  const [busy, setBusy] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)
  const [added, setAdded] = useState<number | null>(null)
  const formError = useFormError(addError)

  async function add(e: FormEvent) {
    e.preventDefault()
    const parsed = parseLocations(lines)
    setBusy(true)
    setAddError(null)
    setAdded(null)
    let count = 0
    try {
      for (const l of parsed) {
        await api('POST', '/api/admin/locations', { ...l, brand: brand.trim(), market: market.trim() })
        count++
      }
      setLines('')
    } catch (err) {
      // The lines already added stay added; leave the rest in the box to fix and send again.
      setLines(
        lines
          .split(/\r?\n/)
          .filter((l) => l.trim())
          .slice(count)
          .join('\n'),
      )
      setAddError(`Line ${count + 1}: ${message(err)}`)
    } finally {
      setAdded(count)
      setBusy(false)
      reload()
    }
  }

  async function setActive(l: AdminLocation, active: boolean) {
    try {
      const { brand, name, market, address, city, state, zip, latitude, longitude } = l
      await api('PUT', apiPath`/api/admin/locations/${l.id}`, { brand, name, market, address, city, state, zip, latitude, longitude, active })
      reload()
    } catch (err) {
      setAddError(message(err))
    }
  }

  if (error) return <PageError message={error} />
  if (!locations) return <Loading />

  return (
    <>
      <p>
        <Link to="/admin/stylists" className="back">
          ‹ Stylists
        </Link>
      </p>
      <h1>Salon locations</h1>

      <form className="form panel" onSubmit={add} aria-describedby={formError.describedBy}>
        <h2>Add locations</h2>
        <div className="field-row">
          <Field label="Brand">{(control) => <input {...control} value={brand} onChange={(e) => setBrand(e.target.value)} required />}</Field>
          <Field label="Market">
            {(control) => <input {...control} value={market} onChange={(e) => setMarket(e.target.value)} required pattern="[a-z0-9]+(-[a-z0-9]+)*" />}
          </Field>
        </div>
        <Field label="Locations, one per line" hint="Name, street, city, state, zip. Only the name is required. Tabs work too, for a paste from a spreadsheet.">
          {(control) => (
            <textarea
              {...control}
              rows={6}
              value={lines}
              onChange={(e) => setLines(e.target.value)}
              required
              placeholder={'Short North, 123 N High St, Columbus, OH, 43215'}
            />
          )}
        </Field>
        {formError.message}
        {added !== null && added > 0 && <p role="status">Added {added === 1 ? '1 location' : `${added} locations`}.</p>}
        <button type="submit" disabled={busy} aria-busy={busy}>
          Add {parseLocations(lines).length || ''} {parseLocations(lines).length === 1 ? 'location' : 'locations'}
        </button>
      </form>

      {locations.length === 0 ? (
        <EmptyState title="No locations yet">
          <p>Add the Salon Lofts buildings in Columbus so stylists can pick theirs.</p>
        </EmptyState>
      ) : (
        <ul className="stack" aria-label="Locations">
          {locations.map((l) => (
            <li key={l.id} className="tile">
              <h3>{l.name}</h3>
              <p className="muted small">
                {[l.address, l.city, l.state, l.zip].filter(Boolean).join(', ') || 'No address'} · {l.market} · {l.brand}
              </p>
              <p className="row">
                <Chip tone={l.active ? 'success' : 'neutral'}>{l.active ? 'active' : 'off'}</Chip>
                <button className="secondary" onClick={() => setActive(l, !l.active)}>
                  {l.active ? 'Switch off' : 'Switch on'}
                </button>
              </p>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
