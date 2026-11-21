import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
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
  firstName: '',
}

const NOT_SURE: Option<'UNSURE'> = { value: 'UNSURE', label: 'Not sure' }

const STEPS = ['service', 'hairType', 'texture', 'length', 'budget', 'neighborhood', 'pastIssues', 'photo', 'name'] as const
type Step = (typeof STEPS)[number]

/** Created: the token and where its page is, kept so a failed photo upload can be retried without a second request. */
type Sent = { token: string; statusPath: string }

/**
 * The client's quiz, one question per screen, then the inspiration photo and an optional first name. No account and no
 * email: the client lands on their request's own page (/m/:token), which is their link, and can add an email there
 * after the reveal (CLAUDE.md, 2026-11-20).
 */
export function FindPage() {
  const [answers, setAnswers] = useState<Answers>(EMPTY)
  const [step, setStep] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState<Sent | null>(null)
  const navigate = useNavigate()
  const { data: settings } = useLoad(() => publicApi<IntakeSettings>('GET', '/api/public/intake/settings'), [])
  const keptDays = settings?.photoKeptDays ?? null

  const set = <K extends keyof Answers>(key: K, value: Answers[K]) => setAnswers((a) => ({ ...a, [key]: value }))
  const current: Step = STEPS[step]

  // Browsers sometimes autofill an email into the name field; we'd greet the client by their address.
  const nameLooksLikeEmail = answers.firstName.includes('@')

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
      case 'name':
        return !nameLooksLikeEmail
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
        request = { token: created.token, statusPath: created.statusPath }
        slot = created.upload
        setSent(request)
      }
      await sendPhoto(request.token, photo, slot)
      // Straight to the request's own page: the matching moment, then the reveal.
      navigate(request.statusPath)
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
        {current === 'name' && (
          <Question legend="What should we call you?" hint="Optional. We use it to say hello on your results page.">
            <label className="field">
              <span className="field-label">First name (optional)</span>
              <input
                autoComplete="given-name"
                maxLength={40}
                value={answers.firstName}
                aria-invalid={nameLooksLikeEmail || undefined}
                aria-describedby={nameLooksLikeEmail ? 'first-name-error' : undefined}
                onChange={(e) => set('firstName', e.target.value)}
              />
            </label>
            {nameLooksLikeEmail && (
              <p id="first-name-error" className="error" role="alert">
                That looks like an email address. Put just your first name here, or leave it blank.
              </p>
            )}
            <p className="muted small">
              No email needed: your results open on their own page. Bookmark that page, or email it to yourself from there.
            </p>
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
              horizontal
              width={120}
              height={32}
              bend={0.38}
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
