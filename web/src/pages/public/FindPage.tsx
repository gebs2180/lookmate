import { useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import { publicApi, type IntakeSettings, type MatchRequestCreate, type MatchRequestCreated } from '../../api'
import { Strand, type StrandShape } from '../../strand/Strand'
import { useLoad } from '../../useLoad'
import { message } from '../../format'
import { PhotoPicker, PrivacyNote } from './PhotoPicker'
import { sendPhoto } from './photo'
import {
  BUDGETS,
  HAIR_TYPES,
  LENGTHS,
  PAST_ISSUES,
  SERVICES,
  TEXTURES,
  type HairType,
  type Length,
  type Option,
  type PastIssue,
  type Service,
  type Texture,
} from './quiz'

type Answers = {
  service: Service | null
  hairType: HairType | null
  texture: Texture | 'UNSURE' | null
  length: Length | 'UNSURE' | null
  budget: string | null
  neighborhood: string
  pastIssues: PastIssue[]
  pastIssuesNote: string
  photo: Blob | null
  email: string
  firstName: string
}

const EMPTY: Answers = {
  service: null,
  hairType: null,
  texture: null,
  length: null,
  budget: null,
  neighborhood: '',
  pastIssues: [],
  pastIssuesNote: '',
  photo: null,
  email: '',
  firstName: '',
}

const NOT_SURE: Option<'UNSURE'> = { value: 'UNSURE', label: 'Not sure' }

const STEPS = ['service', 'hairType', 'texture', 'length', 'budget', 'neighborhood', 'pastIssues', 'photo', 'contact'] as const
type Step = (typeof STEPS)[number]

/** Sent: the token, and whether the photo still has to go up (the upload or its check failed). */
type Sent = { token: string; statusPath: string; photoSent: boolean }

/** The client's quiz, one question per screen, then the inspiration photo and an email for the link. No account. */
export function FindPage() {
  const [answers, setAnswers] = useState<Answers>(EMPTY)
  const [step, setStep] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState<Sent | null>(null)
  const { data: settings } = useLoad(() => publicApi<IntakeSettings>('GET', '/api/public/intake/settings'), [])
  const keptDays = settings?.photoKeptDays ?? null

  const set = <K extends keyof Answers>(key: K, value: Answers[K]) => setAnswers((a) => ({ ...a, [key]: value }))
  const current: Step = STEPS[step]

  function canContinue(): boolean {
    switch (current) {
      case 'service':
        return answers.service !== null
      case 'hairType':
        return answers.hairType !== null
      case 'texture':
        return answers.texture !== null
      case 'length':
        return answers.length !== null
      case 'budget':
        return answers.budget !== null
      case 'photo':
        return answers.photo !== null
      case 'contact':
        return /^\S+@\S+\.\S+$/.test(answers.email.trim())
      default:
        return true
    }
  }

  async function submit() {
    const photo = answers.photo
    if (!photo || !answers.service || !answers.hairType) return
    setBusy(true)
    setError(null)
    const body: MatchRequestCreate = {
      email: answers.email.trim(),
      firstName: answers.firstName.trim() || undefined,
      hairType: answers.hairType,
      texture: answers.texture === 'UNSURE' ? undefined : (answers.texture ?? undefined),
      length: answers.length === 'UNSURE' ? undefined : (answers.length ?? undefined),
      budgetTier: answers.budget && answers.budget !== 'UNSURE' ? Number(answers.budget) : undefined,
      neighborhood: answers.neighborhood.trim() || undefined,
      service: answers.service,
      pastIssues: answers.pastIssues,
      pastIssuesNote: answers.pastIssuesNote.trim() || undefined,
      inspiration: { contentType: photo.type === 'image/png' ? 'image/png' : 'image/jpeg', sizeBytes: photo.size },
    } as MatchRequestCreate
    try {
      let request = sent
      let slot: MatchRequestCreated['upload'] | undefined
      if (!request) {
        const created = await publicApi<MatchRequestCreated>('POST', '/api/public/match-requests', body)
        request = { token: created.token, statusPath: created.statusPath, photoSent: false }
        slot = created.upload
        setSent(request)
      }
      await sendPhoto(request.token, photo, slot)
      setSent({ ...request, photoSent: true })
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(false)
    }
  }

  function next(event: FormEvent) {
    event.preventDefault()
    if (!canContinue() || busy) return
    if (step === STEPS.length - 1) {
      void submit()
    } else {
      setStep(step + 1)
    }
  }

  if (sent?.photoSent) {
    return (
      <section className="find">
        <h1 className="display">Request sent</h1>
        {answers.hairType && (
          <div className="sent-strand" aria-hidden="true">
            <Strand shape={answers.hairType as StrandShape} horizontal width={240} height={56} bend={0.5} strokeWidth={3} />
          </div>
        )}
        <p>
          Thanks{answers.firstName.trim() ? `, ${answers.firstName.trim()}` : ''}. A Combpatible stylist is matching you with stylists whose
          own work looks like your photo, on hair like yours. We'll email <strong>{answers.email.trim()}</strong> when your matches are
          ready, usually within a day or two.
        </p>
        <p>We've also emailed you a link to this request. Keep it to yourself: anyone with it can see your request.</p>
        <div className="actions">
          <Link className="button" to={sent.statusPath}>
            See your request
          </Link>
          <Link className="button secondary" to={`${sent.statusPath}/stylists`}>
            Browse all stylists
          </Link>
        </div>
        <p className="muted small">
          Your photo is private{keptDays ? ` and deleted after ${keptDays} days` : ''}.
        </p>
      </section>
    )
  }

  return (
    <section className="find">
      <div className="quiz-head">
        <p className="folio" aria-live="polite">
          <span className="sr-only">
            Question {step + 1} of {STEPS.length}
          </span>
          <span aria-hidden="true">
            <b>{String(step + 1).padStart(2, '0')}</b> / {String(STEPS.length).padStart(2, '0')}
          </span>
        </p>
        <progress className="quiz-progress" max={STEPS.length} value={step + 1} aria-label="Quiz progress" />
      </div>
      {step === 0 && (
        <p className="muted small">
          We'll match you with stylists whose own work looks like the style you want. Or{' '}
          <Link to="/stylists">see all stylists</Link> first.
        </p>
      )}
      <form className="form" onSubmit={next} noValidate>
        {current === 'service' && (
          <Choices legend="What do you want done?" options={SERVICES} value={answers.service} onChange={(v) => set('service', v)} />
        )}
        {current === 'hairType' && <HairTypes value={answers.hairType} onChange={(v) => set('hairType', v)} />}
        {current === 'texture' && (
          <Choices
            legend="How thick are your strands?"
            options={[...TEXTURES, NOT_SURE] as Option<Texture | 'UNSURE'>[]}
            value={answers.texture}
            onChange={(v) => set('texture', v)}
          />
        )}
        {current === 'length' && (
          <Choices
            legend="How long is your hair now?"
            options={[...LENGTHS, NOT_SURE] as Option<Length | 'UNSURE'>[]}
            value={answers.length}
            onChange={(v) => set('length', v)}
          />
        )}
        {current === 'budget' && (
          <Choices
            legend="What's your budget?"
            options={[...BUDGETS, NOT_SURE] as Option<string>[]}
            value={answers.budget}
            onChange={(v) => set('budget', v)}
          />
        )}
        {current === 'neighborhood' && (
          <Question legend="Where in Columbus would you like to go?" hint="A neighborhood or area. Optional.">
            <input
              aria-label="Neighborhood"
              value={answers.neighborhood}
              maxLength={80}
              placeholder="e.g. Short North, Clintonville, Dublin"
              onChange={(e) => set('neighborhood', e.target.value)}
              autoComplete="address-level3"
            />
          </Question>
        )}
        {current === 'pastIssues' && (
          <Question legend="Has a stylist let you down before?" hint="Pick any that happened. Optional: it helps us pick better.">
            <div className="choices">
              {PAST_ISSUES.map((o) => (
                <label key={o.value} className="choice">
                  <input
                    type="checkbox"
                    checked={answers.pastIssues.includes(o.value)}
                    onChange={(e) =>
                      set('pastIssues', e.target.checked ? [...answers.pastIssues, o.value] : answers.pastIssues.filter((p) => p !== o.value))
                    }
                  />
                  <span>{o.label}</span>
                </label>
              ))}
            </div>
            <label className="field">
              <span className="field-label">Anything else? (optional)</span>
              <textarea value={answers.pastIssuesNote} maxLength={500} rows={3} onChange={(e) => set('pastIssuesNote', e.target.value)} />
            </label>
          </Question>
        )}
        {current === 'photo' && (
          <Question legend="Show us the look you want" hint="One photo of the style: a screenshot, a saved post, or a photo you took.">
            <PhotoPicker photo={answers.photo} onChange={(p) => set('photo', p)} />
            <PrivacyNote keptDays={keptDays} />
          </Question>
        )}
        {current === 'contact' && (
          <Question legend="Where should we send your matches?" hint="We email you a private link. No account, no password.">
            <label className="field">
              <span className="field-label">Email</span>
              <input
                type="email"
                inputMode="email"
                autoComplete="email"
                required
                maxLength={254}
                value={answers.email}
                onChange={(e) => set('email', e.target.value)}
              />
            </label>
            <label className="field">
              <span className="field-label">First name (optional)</span>
              <input autoComplete="given-name" maxLength={40} value={answers.firstName} onChange={(e) => set('firstName', e.target.value)} />
            </label>
          </Question>
        )}

        {error && (
          <div className="alert alert-danger" role="alert">
            <p>{error}</p>
            {sent && (
              <p>
                Your answers are saved. Try again, or <Link to={sent.statusPath}>add the photo from your request page</Link>.
              </p>
            )}
          </div>
        )}
        <div className="actions">
          {step > 0 && !sent && (
            <button type="button" className="secondary" onClick={() => setStep(step - 1)} disabled={busy}>
              Back
            </button>
          )}
          <button type="submit" disabled={!canContinue() || busy} aria-busy={busy}>
            {step < STEPS.length - 1 ? 'Next' : sent ? 'Try the photo again' : busy ? 'Sending…' : 'Find my stylists'}
          </button>
        </div>
      </form>
      <p className="muted small">
        Our picks are suggestions: you can book any stylist you like. Combpatible doesn't take bookings or payments; you book on the stylist's
        own page.
      </p>
    </section>
  )
}

function Question({ legend, hint, children }: { legend: string; hint?: string; children: ReactNode }) {
  return (
    <fieldset className="question">
      <legend>
        <h1>{legend}</h1>
      </legend>
      {hint && <p className="field-hint">{hint}</p>}
      {children}
    </fieldset>
  )
}

/**
 * Hair type, the question matching cares most about: four cards, each with its own strand, and one large strand above
 * that cycles through every type until you pick, then takes your shape. The strands are decoration; the radios and
 * their names carry the meaning.
 */
function HairTypes({ value, onChange }: { value: HairType | null; onChange: (value: HairType) => void }) {
  return (
    <Question legend="What's your hair type?" hint="Stylists who work with your hair type rank first.">
      <div className="type-strand" aria-hidden="true">
        <Strand shape={value ?? 'STRAIGHT'} cycle={value === null} horizontal width={300} height={72} bend={0.6} strokeWidth={3} />
      </div>
      <div className="types">
        {HAIR_TYPES.map((o) => (
          <label key={o.value} className="type">
            <input className="sr-only" type="radio" name="hairType" checked={value === o.value} onChange={() => onChange(o.value)} />
            <Strand
              shape={o.value as StrandShape}
              width={40}
              height={56}
              bend={0.75}
              strokeWidth={2.5}
              drawKey={value === o.value ? 'on' : 'off'}
            />
            <span>
              <span className="name">{o.label}</span>
              {o.hint && <span className="choice-hint">{o.hint}</span>}
            </span>
          </label>
        ))}
      </div>
    </Question>
  )
}

function Choices<T extends string>({
  legend,
  hint,
  options,
  value,
  onChange,
}: {
  legend: string
  hint?: string
  options: Option<T>[]
  value: T | null
  onChange: (value: T) => void
}) {
  return (
    <Question legend={legend} hint={hint}>
      <div className="choices">
        {options.map((o) => (
          <label key={o.value} className="choice">
            <input type="radio" name={legend} checked={value === o.value} onChange={() => onChange(o.value)} />
            <span>
              {o.label}
              {o.hint && <span className="choice-hint">{o.hint}</span>}
            </span>
          </label>
        ))}
      </div>
    </Question>
  )
}
