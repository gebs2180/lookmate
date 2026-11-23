import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiPath } from '../api'
import { useApi } from '../useApi'
import { message } from '../format'
import { Chip, Loading, PageError, type Tone } from '../ui'
import { useLoad } from '../useLoad'
import './stylists.css'
import { TermsStep } from './TermsStep'
import { type Missing, type Profile, type ProfileStatus, type StylistHome } from './types'

const STATUS: Record<ProfileStatus, { label: string; tone: Tone }> = {
  DRAFT: { label: 'Draft: clients can’t see you yet', tone: 'neutral' },
  LIVE: { label: 'Live: clients can be matched with you', tone: 'success' },
  HIDDEN: { label: 'Hidden: clients can’t see you', tone: 'warning' },
}

/**
 * A stylist's home after joining: the terms first, then a checklist to going live (profile, location, booking link,
 * tagged photos), then publish or hide.
 */
export function StylistHomePage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data: home, error, reload } = useLoad(() => api<StylistHome>('GET', apiPath`/api/orgs/${orgId}/stylist`), [api, orgId])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  async function change(action: 'publish' | 'hide') {
    setBusy(true)
    setActionError(null)
    try {
      await api<Profile>('POST', action === 'publish' ? apiPath`/api/orgs/${orgId}/stylist/publish` : apiPath`/api/orgs/${orgId}/stylist/hide`)
      reload()
    } catch (e) {
      setActionError(message(e))
    } finally {
      setBusy(false)
    }
  }

  if (error) return <PageError message={error} />
  if (!home) return <Loading />

  if (!home.termsAccepted) {
    return (
      <>
        <h1>Welcome{home.orgName ? `, ${home.orgName}` : ''}</h1>
        <p>Before you set up your profile, please read and accept the stylist terms.</p>
        <TermsStep orgId={orgId} onAccepted={reload} />
      </>
    )
  }

  const has = (m: Missing) => !home.missing.includes(m)
  const status = home.profile?.status ?? 'DRAFT'
  return (
    <>
      <h1>{home.profile?.displayName ?? home.orgName ?? 'Your profile'}</h1>
      <p>
        <Chip tone={STATUS[status].tone}>{STATUS[status].label}</Chip>
      </p>

      <section className="panel" aria-labelledby="checklist">
        <h2 id="checklist">Getting ready to go live</h2>
        <ul className="stack checklist">
          <Step done={has('PROFILE')} to={`/stylist/${orgId}/profile`} label="Your name and specialties" />
          <Step done={home.profile != null && has('LOCATION')} to={`/stylist/${orgId}/profile`} label="Your Salon Lofts location" />
          <Step done={home.profile != null && has('BOOKING_URL')} to={`/stylist/${orgId}/profile`} label="Your booking link" />
          <Step
            done={has('TAGGED_PHOTOS')}
            to={`/stylist/${orgId}/portfolio`}
            label={`At least ${home.minTaggedPhotos} tagged photos (${home.taggedPhotos} of ${home.readyPhotos} tagged)`}
          />
        </ul>
        <p className="muted small">
          Clients are matched on your photos, so 10 to 20 tagged photos of your best work, on different hair types, works best.
        </p>
      </section>

      <div className="actions">
        <Link className="button secondary" to={`/stylist/${orgId}/profile`}>
          {home.profile ? 'Edit profile' : 'Set up profile'}
        </Link>
        <Link className="button secondary" to={`/stylist/${orgId}/portfolio`}>
          Portfolio ({home.readyPhotos})
        </Link>
      </div>

      <div className="actions">
        {status !== 'LIVE' ? (
          <button className="block" onClick={() => change('publish')} disabled={busy || home.missing.length > 0} aria-busy={busy}>
            Go live
          </button>
        ) : (
          <button className="block secondary" onClick={() => change('hide')} disabled={busy} aria-busy={busy}>
            Hide my profile
          </button>
        )}
      </div>
      {actionError && (
        <p className="error" role="alert">
          {actionError}
        </p>
      )}
    </>
  )
}

function Step({ done, to, label }: { done: boolean; to: string; label: string }) {
  return (
    <li>
      <span aria-hidden="true">{done ? '✓ ' : '○ '}</span>
      {done ? (
        <>
          {label}
          <span className="visually-hidden"> (done)</span>
        </>
      ) : (
        <Link to={to}>
          {label}
          <span className="visually-hidden"> (to do)</span>
        </Link>
      )}
    </li>
  )
}
