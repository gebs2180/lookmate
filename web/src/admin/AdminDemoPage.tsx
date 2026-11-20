import { useState, type ChangeEvent } from 'react'
import { Link } from 'react-router'
import { uploadToStorage, useApi } from '../api'
import { message } from '../format'
import type { AdminStylist, Location } from '../stylists/types'
import { Loading, PageError } from '../ui'
import '../stylists/stylists.css'
import { useLoad } from '../useLoad'
import { seedDemoStylists } from './demoSeed'
import { DEMO_STYLISTS } from './demoStylists'
import { drawPlaceholder } from './placeholder'

/**
 * Admin: made-up stylists for the proof of concept. Creates them live, with tagged portfolio photos (drawn
 * placeholders, or photos the admin picks whose file names match), and hides them all once real stylists join.
 */
export function AdminDemoPage() {
  const api = useApi()
  const { data, error, reload } = useLoad(
    () => Promise.all([api<AdminStylist[]>('GET', '/api/admin/stylists'), api<Location[]>('GET', '/api/locations')]),
    [api],
  )
  const [files, setFiles] = useState<Map<string, File>>(new Map())
  const [log, setLog] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [runError, setRunError] = useState<string | null>(null)

  function pick(e: ChangeEvent<HTMLInputElement>) {
    setFiles(new Map(Array.from(e.target.files ?? []).map((f) => [f.name.toLowerCase(), f])))
  }

  async function create(locations: Location[]) {
    setBusy(true)
    setRunError(null)
    setLog([])
    try {
      await seedDemoStylists(DEMO_STYLISTS, locations, {
        api,
        upload: uploadToStorage,
        photoFor: async (photo, stylist) => files.get(photo.file.toLowerCase()) ?? drawPlaceholder(photo, stylist),
        progress: (line) => setLog((l) => [...l, line]),
      })
    } catch (e) {
      setRunError(message(e))
    } finally {
      setBusy(false)
      reload()
    }
  }

  async function hideAll() {
    setBusy(true)
    setRunError(null)
    try {
      const { hidden } = await api<{ hidden: number }>('POST', '/api/admin/stylists/demo/hide')
      setLog([`Hid ${hidden} demo ${hidden === 1 ? 'stylist' : 'stylists'}.`])
    } catch (e) {
      setRunError(message(e))
    } finally {
      setBusy(false)
      reload()
    }
  }

  if (error) return <PageError message={error} />
  if (!data) return <Loading />
  const [stylists, locations] = data
  const demos = stylists.filter((s) => s.demo)
  const liveDemos = demos.filter((s) => s.status === 'LIVE').length
  const matched = DEMO_STYLISTS.flatMap((s) => s.photos).filter((p) => files.has(p.file.toLowerCase())).length

  return (
    <>
      <p>
        <Link to="/admin/stylists" className="back">
          ‹ Stylists
        </Link>
      </p>
      <h1>Demo stylists</h1>
      <p>
        {DEMO_STYLISTS.length} made-up stylists for trying LookMate before real stylists join. They go live like real ones, marked as demo,
        with booking links to example.com.
      </p>
      <p>
        {demos.length === 0 ? 'None created yet.' : `${demos.length} demo stylists exist, ${liveDemos} live.`}{' '}
        {locations.length === 0 ? (
          <>
            <Link to="/admin/locations">Add a location</Link> first: they need one to go live.
          </>
        ) : (
          `They'll be spread across your ${locations.length} active ${locations.length === 1 ? 'location' : 'locations'}.`
        )}
      </p>

      <section className="panel">
        <h2>Photos</h2>
        <p className="field-hint">
          Without photos, each gets drawn placeholders showing its tags. To use real photos, pick files named like the list in
          src/admin/demoStylists.ts (maya-1.jpg to nia-5.jpg); any you leave out stay placeholders. Use only photos you have the rights to.
        </p>
        <label className="button secondary">
          Choose photos (optional)
          <input type="file" accept="image/jpeg,image/png" multiple onChange={pick} className="visually-hidden" aria-label="Choose demo photos" />
        </label>
        {files.size > 0 && (
          <p role="status">
            {matched} of {files.size} picked photos match a demo photo name.
          </p>
        )}
      </section>

      <div className="actions">
        <button className="block" onClick={() => create(locations)} disabled={busy || locations.length === 0} aria-busy={busy}>
          {demos.length > 0 ? `Create ${DEMO_STYLISTS.length} more demo stylists` : `Create ${DEMO_STYLISTS.length} demo stylists`}
        </button>
        {liveDemos > 0 && (
          <button className="secondary" onClick={hideAll} disabled={busy}>
            Hide all demo stylists
          </button>
        )}
      </div>
      {runError && (
        <p className="error" role="alert">
          {runError}
        </p>
      )}
      {log.length > 0 && (
        <ul className="uploads" aria-live="polite">
          {log.map((line, i) => (
            <li key={i}>{line}</li>
          ))}
        </ul>
      )}
    </>
  )
}
