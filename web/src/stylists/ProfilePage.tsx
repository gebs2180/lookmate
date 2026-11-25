import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError, apiPath } from '../api'
import { useApi } from '../useApi'
import { message } from '../format'
import { Field, Loading, PageError } from '../ui'
import { useFormError } from '../useFormError'
import { useLoad } from '../useLoad'
import './stylists.css'
import { CONSULTATION_FORM, FIRST_VISIT_FORM, LABEL_FORM, LANGUAGE_NAME, NEW_CLIENTS_FORM } from './practice'
import {
  CONSULTATIONS,
  FIRST_VISIT_NOTE_MAX,
  LANGUAGES,
  NEW_CLIENTS_MAX_WEEKS,
  NEW_CLIENT_STATUSES,
  PRACTICE_LABELS,
  SERVICES,
  SERVICE_LABEL,
  type Consultation,
  type Language,
  type Location,
  type NewClientStatus,
  type PracticeLabel,
  type Profile,
  type ProfileRequest,
  type ServiceType,
  type StylistHome,
} from './types'

const PRICE_TIERS = [
  { value: 1, label: '$' },
  { value: 2, label: '$$' },
  { value: 3, label: '$$$' },
  { value: 4, label: '$$$$' },
]

/**
 * The stylist's profile form: name, location, specialties, booking link, Instagram, price tier, bio, and what to
 * expect in their chair (labels, languages, from-prices, consultation). All of the last part is optional.
 */
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
  const [slug, setSlug] = useState(profile?.slug ?? '')
  const [priceTier, setPriceTier] = useState<number | null>(profile?.priceTier ?? null)
  const [bio, setBio] = useState(profile?.bio ?? '')
  const practice = profile?.practice
  const [labels, setLabels] = useState<PracticeLabel[]>(practice?.labels ?? [])
  const [languages, setLanguages] = useState<Language[]>(practice?.languages ?? [])
  const [prices, setPrices] = useState<Partial<Record<ServiceType, string>>>(() =>
    Object.fromEntries((practice?.fromPrices ?? []).map((p) => [p.service, String(p.fromDollars)])),
  )
  const [consultation, setConsultation] = useState<Consultation | null>(practice?.consultation ?? null)
  const [priceDependsOnHair, setPriceDependsOnHair] = useState(practice?.priceDependsOnHair ?? false)
  const [colourConsultationFirst, setColourConsultationFirst] = useState(practice?.firstVisit.colourConsultationFirst ?? false)
  const [depositForNewClients, setDepositForNewClients] = useState(practice?.firstVisit.depositForNewClients ?? false)
  const [firstVisitNote, setFirstVisitNote] = useState(practice?.firstVisit.note ?? '')
  const [newClients, setNewClients] = useState<NewClientStatus>(practice?.newClients.status ?? 'TAKING')
  const [weeksOut, setWeeksOut] = useState(practice?.newClients.weeksOut != null ? String(practice.newClients.weeksOut) : '')
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const formError = useFormError(saveError)

  function toggle(service: ServiceType) {
    setSpecialties((s) => (s.includes(service) ? s.filter((x) => x !== service) : [...s, service]))
  }

  function flip<T>(set: (f: (xs: T[]) => T[]) => void, value: T) {
    set((xs) => (xs.includes(value) ? xs.filter((x) => x !== value) : [...xs, value]))
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
      // Blank: unchanged (a new profile gets one from the name).
      slug: slug.trim() || null,
      priceTier,
      practice: {
        labels,
        languages,
        // Only for services still ticked; a blank box means no price shown.
        fromPrices: specialties
          .filter((s) => (prices[s] ?? '').trim() !== '')
          .map((s) => ({ service: s, fromDollars: Number((prices[s] ?? '').trim()) })),
        consultation,
        priceDependsOnHair,
        firstVisit: { colourConsultationFirst, depositForNewClients, note: firstVisitNote.trim() || null },
      },
      // Saving counts as confirming the status is still right.
      newClients: { status: newClients, weeksOut: newClients === 'TAKING_WITH_WAIT' ? Number(weeksOut.trim()) || null : null },
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

        <Field label="Your location" hint={locations.length === 0 ? 'No locations yet: ask the Combpatible team to add yours.' : undefined}>
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

        {profile && (
          <Field label="Your page's address" hint={`${window.location.host}/s/${slug.trim() || profile.slug}. Letters, numbers and hyphens. Changing it breaks links and QR cards you already shared.`}>
            {(control) => (
              <input {...control} value={slug} onChange={(e) => setSlug(e.target.value)} autoCapitalize="none" autoCorrect="off" spellCheck={false} />
            )}
          </Field>
        )}

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

        <fieldset className="tag-group">
          <legend className="field-label">New clients</legend>
          <p className="field-hint">
            Shown on your profile in plain words. You stay in matching and the directory whatever you pick.
          </p>
          <div className="choices wide">
            {NEW_CLIENT_STATUSES.map((s) => (
              <label key={s} className="choice">
                <input type="radio" name="newClients" checked={newClients === s} onChange={() => setNewClients(s)} />
                {NEW_CLIENTS_FORM[s]}
              </label>
            ))}
          </div>
          {newClients === 'TAKING_WITH_WAIT' && (
            <Field label="Usual wait for a new client, in weeks">
              {(control) => (
                <input
                  {...control}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={NEW_CLIENTS_MAX_WEEKS}
                  step={1}
                  required
                  value={weeksOut}
                  onChange={(e) => setWeeksOut(e.target.value)}
                />
              )}
            </Field>
          )}
        </fieldset>

        <section className="practice-form" aria-labelledby="practice-heading">
          <h2 id="practice-heading">What to expect in my chair</h2>
          <p className="field-hint">
            All optional, and none of it is needed to go live. Clients see what you tick on your profile; anything you leave
            blank just isn't shown.
          </p>

          <fieldset className="tag-group">
            <legend className="field-label">Tick what's true for you</legend>
            <div className="choices wide">
              {PRACTICE_LABELS.map((l) => (
                <label key={l} className="choice">
                  <input type="checkbox" checked={labels.includes(l)} onChange={() => flip(setLabels, l)} />
                  {LABEL_FORM[l]}
                </label>
              ))}
            </div>
          </fieldset>

          <fieldset className="tag-group">
            <legend className="field-label">Languages you can do a consultation in</legend>
            <p className="field-hint">Besides English.</p>
            <div className="choices">
              {LANGUAGES.map((l) => (
                <label key={l} className="choice">
                  <input type="checkbox" checked={languages.includes(l)} onChange={() => flip(setLanguages, l)} />
                  {LANGUAGE_NAME[l]}
                </label>
              ))}
            </div>
          </fieldset>

          <fieldset className="tag-group">
            <legend className="field-label">What does a client pay?</legend>
            <p className="field-hint">
              {specialties.length === 0
                ? 'Tick your specialties above, then give a starting price for each.'
                : 'Give a starting price for each service you offer, in whole dollars.'}
            </p>
            <div className="prices">
              {specialties.map((s) => (
                <Field key={s} label={`${SERVICE_LABEL[s]} from $`}>
                  {(control) => (
                    <input
                      {...control}
                      type="number"
                      inputMode="numeric"
                      min={1}
                      max={10000}
                      step={1}
                      value={prices[s] ?? ''}
                      onChange={(e) => setPrices((p) => ({ ...p, [s]: e.target.value }))}
                    />
                  )}
                </Field>
              ))}
            </div>
            <label className="check">
              <input type="checkbox" checked={priceDependsOnHair} onChange={(e) => setPriceDependsOnHair(e.target.checked)} />
              My price depends on length and density
            </label>
          </fieldset>

          <fieldset className="tag-group">
            <legend className="field-label">Consultation</legend>
            <div className="choices">
              {CONSULTATIONS.map((c) => (
                <label key={c} className="choice">
                  <input type="radio" name="consultation" checked={consultation === c} onChange={() => setConsultation(c)} />
                  {CONSULTATION_FORM[c]}
                </label>
              ))}
              <label className="choice">
                <input type="radio" name="consultation" checked={consultation === null} onChange={() => setConsultation(null)} />
                Don't show
              </label>
            </div>
          </fieldset>

          <fieldset className="tag-group">
            <legend className="field-label">Before a client's first visit</legend>
            <p className="field-hint">
              Shown first under "Before your first visit", above Combpatible's general tips. A deposit is between you and your
              client: Combpatible never takes payments.
            </p>
            <label className="check">
              <input type="checkbox" checked={colourConsultationFirst} onChange={(e) => setColourConsultationFirst(e.target.checked)} />
              {FIRST_VISIT_FORM.colourConsultationFirst}
            </label>
            <label className="check">
              <input type="checkbox" checked={depositForNewClients} onChange={(e) => setDepositForNewClients(e.target.checked)} />
              {FIRST_VISIT_FORM.depositForNewClients}
            </label>
            <Field label="A note for new clients" hint={`Optional, up to ${FIRST_VISIT_NOTE_MAX} characters. Parking, what to bring, how to arrive.`}>
              {(control) => (
                <textarea
                  {...control}
                  rows={2}
                  value={firstVisitNote}
                  onChange={(e) => setFirstVisitNote(e.target.value)}
                  maxLength={FIRST_VISIT_NOTE_MAX}
                />
              )}
            </Field>
          </fieldset>
        </section>

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
