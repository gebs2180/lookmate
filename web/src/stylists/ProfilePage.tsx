import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError, apiPath, useApi } from '../api'
import { message } from '../format'
import { Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import './stylists.css'
import { SERVICES, SERVICE_LABEL, type Location, type Profile, type ProfileRequest, type ServiceType, type StylistHome } from './types'

const PRICE_TIERS = [
  { value: 1, label: '$' },
  { value: 2, label: '$$' },
  { value: 3, label: '$$$' },
  { value: 4, label: '$$$$' },
]

/** The stylist's profile form: name, location, specialties, booking link, Instagram, price tier, bio. */
export function ProfilePage() {
  const { orgId = '' } = useParams()
  const api = useApi()
  const { data, error } = useLoad(
    () =>
      Promise.all([
        api<StylistHome>('GET', apiPath`/api/orgs/${orgId}/stylist`),
        api<Location[]>('GET', '/api/locations'),
      ]),
    [api, orgId],
  )

  if (error) return <PageError message={error} />
  if (!data) return <Loading />
  const [home, locations] = data
  return <ProfileForm orgId={orgId} profile={home.profile} orgName={home.orgName} locations={locations} />
}

function ProfileForm({
  orgId,
  profile,
  orgName,
  locations,
}: {
  orgId: string
  profile: Profile | null
  orgName: string | null
  locations: Location[]
}) {
  const api = useApi()
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState(profile?.displayName ?? orgName ?? '')
  const [locationId, setLocationId] = useState(profile?.locationId ?? '')
  const [specialties, setSpecialties] = useState<ServiceType[]>(profile?.specialties ?? [])
  const [bookingUrl, setBookingUrl] = useState(profile?.bookingUrl ?? '')
  const [instagram, setInstagram] = useState(profile?.instagram ? `@${profile.instagram}` : '')
  const [priceTier, setPriceTier] = useState<number | null>(profile?.priceTier ?? null)
  const [bio, setBio] = useState(profile?.bio ?? '')
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const formError = useFormError(saveError)

  function toggle(service: ServiceType) {
    setSpecialties((s) => (s.includes(service) ? s.filter((x) => x !== service) : [...s, service]))
  }

  async function save(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setSaveError(null)
    const body: ProfileRequest = {
      displayName: displayName.trim(),
      locationId: locationId || null,
      bio: bio.trim() || null,
      specialties,
      bookingUrl: bookingUrl.trim() || null,
      instagram: instagram.trim() || null,
      priceTier,
    }
    try {
      await api<Profile>('PUT', apiPath`/api/orgs/${orgId}/stylist/profile`, body)
      navigate(`/stylist/${orgId}`)
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : message(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <p>
        <Link to={`/stylist/${orgId}`} className="back">
          ‹ Back
        </Link>
      </p>
      <h1>Your profile</h1>
      <form className="form" onSubmit={save} aria-describedby={formError.describedBy}>
        <Field label="Name clients know you by">
          {(control) => (
            <input {...control} value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} autoComplete="name" />
          )}
        </Field>

        <Field label="Your location" hint={locations.length === 0 ? 'No locations yet: ask the LookMate team to add yours.' : undefined}>
          {(control) => (
            <select {...control} value={locationId} onChange={(e) => setLocationId(e.target.value)}>
              <option value="">Choose your location</option>
              {locations.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                  {l.city ? `, ${l.city}` : ''}
                </option>
              ))}
            </select>
          )}
        </Field>

        <fieldset className="tag-group">
          <legend className="field-label">Specialties</legend>
          <div className="choices">
            {SERVICES.map((s) => (
              <label key={s} className="choice">
                <input type="checkbox" checked={specialties.includes(s)} onChange={() => toggle(s)} />
                {SERVICE_LABEL[s]}
              </label>
            ))}
          </div>
        </fieldset>

        <Field label="Booking link" hint="The https:// link clients book you with: Booksy, Vagaro, StyleSeat, GlossGenius, Square or your own site.">
          {(control) => (
            <input
              {...control}
              type="url"
              inputMode="url"
              placeholder="https://"
              value={bookingUrl}
              onChange={(e) => setBookingUrl(e.target.value)}
              maxLength={500}
            />
          )}
        </Field>

        <Field label="Instagram" hint="Your handle, like @curls.by.ana">
          {(control) => (
            <input {...control} value={instagram} onChange={(e) => setInstagram(e.target.value)} autoCapitalize="none" autoCorrect="off" />
          )}
        </Field>

        <fieldset className="tag-group">
          <legend className="field-label">Price range</legend>
          <div className="choices">
            {PRICE_TIERS.map((t) => (
              <label key={t.value} className="choice">
                <input type="radio" name="price-tier" checked={priceTier === t.value} onChange={() => setPriceTier(t.value)} />
                {t.label}
              </label>
            ))}
          </div>
        </fieldset>

        <Field label="About you" hint="Optional. What you love doing, who you love working with.">
          {(control) => <textarea {...control} rows={4} value={bio} onChange={(e) => setBio(e.target.value)} maxLength={1000} />}
        </Field>

        {formError.message}
        <div className="actions">
          <button type="submit" className="block" disabled={busy} aria-busy={busy}>
            Save profile
          </button>
        </div>
      </form>
    </>
  )
}
