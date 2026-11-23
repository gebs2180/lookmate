import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiPath, type CreatedInvite, type Org } from '../api'
import { useApi } from '../useApi'
import { message } from '../format'
import '../stylists/stylists.css'
import { PLATFORM_LABEL, type AdminStylist } from '../stylists/types'
import { Chip, EmptyState, Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'

/**
 * Admin: every stylist, their profile status and photo counts; add a stylist (an org plus a one-time invite link to
 * send them) or make a new invite link for one who lost theirs.
 */
export function AdminStylistsPage() {
  const api = useApi()
  const { data: stylists, error, reload } = useLoad(() => api<AdminStylist[]>('GET', '/api/admin/stylists'), [api])
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [invite, setInvite] = useState<{ orgName: string; link: string } | null>(null)
  const formError = useFormError(createError)

  async function inviteLink(orgId: string, orgName: string) {
    const created = await api<CreatedInvite>('POST', apiPath`/api/admin/orgs/${orgId}/invites`)
    setInvite({ orgName, link: `${window.location.origin}${created.acceptPath}` })
  }

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setCreateError(null)
    setInvite(null)
    try {
      const org = await api<Org>('POST', '/api/admin/orgs', { name: name.trim(), type: 'STYLIST' })
      await inviteLink(org.id, org.name)
      setName('')
      reload()
    } catch (err) {
      setCreateError(message(err))
    } finally {
      setBusy(false)
    }
  }

  async function newLink(s: AdminStylist) {
    setInvite(null)
    setCreateError(null)
    try {
      await inviteLink(s.orgId, s.orgName ?? 'this stylist')
    } catch (err) {
      setCreateError(message(err))
    }
  }

  if (error) return <PageError message={error} />
  if (!stylists) return <Loading />

  return (
    <>
      <h1>Stylists</h1>
      <p>
        <Link to="/admin/locations">Salon locations</Link> · <Link to="/admin/demo">Demo stylists</Link>
      </p>

      <form className="form panel" onSubmit={create} aria-describedby={formError.describedBy}>
        <h2>Add a stylist</h2>
        <Field label="Business or stylist name">
          {(control) => <input {...control} value={name} onChange={(e) => setName(e.target.value)} required maxLength={200} />}
        </Field>
        {formError.message}
        <button type="submit" disabled={busy} aria-busy={busy}>
          Add and make invite link
        </button>
      </form>

      {invite && (
        <div className="alert alert-success" role="status">
          <p className="alert-title">Invite link for {invite.orgName}</p>
          <p>Send this to them. It works once and expires in 7 days; it won't be shown again.</p>
          <p>
            <code>{invite.link}</code>
          </p>
          <button className="secondary" onClick={() => navigator.clipboard?.writeText(invite.link)}>
            Copy link
          </button>
        </div>
      )}

      {stylists.length === 0 ? (
        <EmptyState title="No stylists yet" />
      ) : (
        <ul className="stack" aria-label="Stylists">
          {stylists.map((s) => (
            <li key={s.orgId} className="tile">
              <h3>
                <Link to={`/stylist/${s.orgId}`}>{s.displayName ?? s.orgName ?? s.orgId}</Link>
              </h3>
              <p className="row">
                <Chip tone={s.status === 'LIVE' ? 'success' : s.status === 'HIDDEN' ? 'warning' : 'neutral'}>
                  {s.status ? s.status.toLowerCase() : 'no profile'}
                </Chip>
                {s.demo && <Chip tone="info">demo</Chip>}
                <span className="muted small">
                  {s.taggedPhotos} of {s.readyPhotos} photos tagged
                  {s.bookingPlatform ? ` · books on ${PLATFORM_LABEL[s.bookingPlatform]}` : ''}
                </span>
              </p>
              <div className="actions">
                <button className="secondary" onClick={() => newLink(s)}>
                  New invite link
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
