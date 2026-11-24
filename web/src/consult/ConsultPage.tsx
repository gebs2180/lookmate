import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api'
import { Strand } from '../strand/Strand'
import { Loading, PageError } from '../ui'
import { useLoad } from '../useLoad'
import { focusHeading } from '../useRouteFocus'
import {
  loadFinder,
  recordBook,
  recordFindStylist,
  submitAnswers,
  submitError,
  type Answers,
  type ConsultResult,
  type Finder,
  type FinderQuestion,
  type Source,
} from './api'
import { formatHours } from './format'

/** "Not sure what to book?" behind the Combpatible button: no prices or links, hands off to find-my-stylist. */
export function GeneralConsultPage() {
  return <ConsultPage source={{ channel: 'GENERAL' }} />
}

/** A partner stylist's own link, /c/:slug: their prices and, when they gave one, their booking link. */
export function PartnerConsultPage() {
  const { slug = '' } = useParams()
  return <ConsultPage key={slug} source={{ channel: 'PARTNER', slug }} />
}

type Loaded = { finder: Finder } | { notFound: true }

function ConsultPage({ source }: { source: Source }) {
  const slug = source.channel === 'PARTNER' ? source.slug : null
  const { data, error, reload } = useLoad<Loaded>(
    () =>
      loadFinder(source).then(
        (finder) => ({ finder }),
        (e: unknown) => {
          if (e instanceof ApiError && e.status === 404) return { notFound: true as const }
          throw e
        },
      ),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [slug],
  )
  if (error) {
    return (
      <section className="find">
        <PageError message={error} />
        <p>
          <button type="button" className="secondary" onClick={reload}>
            Try again
          </button>
        </p>
      </section>
    )
  }
  if (!data) return <Loading>Loading the questions…</Loading>
  if ('notFound' in data) return <LinkNotFound />
  return <Quiz source={source} finder={data.finder} onRestart={reload} />
}

/** An unknown or retired partner slug: say so kindly, and offer the general finder and the main quiz instead. */
function LinkNotFound() {
  return (
    <section className="find consult-missing">
      <h1>We couldn't find that stylist's link</h1>
      <p>It may have changed, or there may be a typo in it. You can still answer the same few questions here.</p>
      <div className="consult-actions">
        <Link className="button block" to="/consult">
          Not sure what to book?
        </Link>
        <Link className="button secondary block" to="/find">
          Find my stylist
        </Link>
      </div>
    </section>
  )
}

function Quiz({ source, finder, onRestart }: { source: Source; finder: Finder; onRestart: () => void }) {
  const [answers, setAnswers] = useState<Answers>({})
  const [step, setStep] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ text: string; restart: boolean } | null>(null)
  const [result, setResult] = useState<ConsultResult | null>(null)
  const questions = finder.questions
  const total = questions.length
  const question = questions[step]

  // A step change swaps the question without a route change: move focus to the new heading so a screen reader says it,
  // and name the tab after it, as a route change would.
  const firstStep = useRef(true)
  useEffect(() => {
    if (firstStep.current) firstStep.current = false
    else focusHeading()
    const h1 = document.querySelector('main h1')
    if (h1) document.title = `${h1.textContent} · Combpatible`
  }, [step, result])

  const chosen = (q: FinderQuestion) => answers[q.id] ?? []

  function toggle(q: FinderQuestion, answer: string, on: boolean) {
    setAnswers((a) => {
      const current = a[q.id] ?? []
      const next = q.multiple ? (on ? [...current.filter((x) => x !== answer), answer] : current.filter((x) => x !== answer)) : [answer]
      return { ...a, [q.id]: next }
    })
  }

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      // Only the questions on screen, in the finder's own answer order.
      const body: Answers = {}
      for (const q of questions) {
        const picked = q.answers.filter((a) => chosen(q).includes(a))
        if (picked.length) body[q.id] = picked
      }
      setResult(await submitAnswers(source, body))
    } catch (e) {
      setError(submitError(e))
    } finally {
      setBusy(false)
    }
  }

  function next(event: FormEvent) {
    event.preventDefault()
    if (busy || chosen(question).length === 0) return
    if (step === total - 1) void submit()
    else setStep(step + 1)
  }

  function restart() {
    setAnswers({})
    setResult(null)
    setError(null)
    setStep(0)
    onRestart()
  }

  if (result) return <ResultView result={result} finder={finder} onRestart={restart} />

  return (
    <section className="find consult">
      <div className="quiz-head">
        <p className="folio" aria-live="polite">
          <span className="sr-only">
            Question {step + 1} of {total}
          </span>
          <span aria-hidden="true">
            <b>{String(step + 1).padStart(2, '0')}</b> / {String(total).padStart(2, '0')}
          </span>
        </p>
        <progress className="quiz-progress" max={total} value={step + 1} aria-label="Quiz progress" />
      </div>
      {step === 0 && (
        <>
          <p className="eyebrow">{finder.name}</p>
          <p className="muted small">
            A few taps about your hair, and we'll tell you which service to book, or whether to start with a consultation.
            {finder.credit && <> Questions by {finder.credit}.</>}
          </p>
        </>
      )}
      <form className="form" onSubmit={next} noValidate>
        {/* The help text is deliberately not shown: in our example data it's notes to us, not client copy. */}
        <fieldset className="question" key={question.id}>
          <legend>
            <h1>{question.text}</h1>
          </legend>
          {question.multiple && <p className="field-hint">Pick all that apply.</p>}
          <div className="choices">
            {question.answers.map((answer) => (
              <label key={answer} className="choice">
                <input
                  type={question.multiple ? 'checkbox' : 'radio'}
                  name={question.id}
                  checked={chosen(question).includes(answer)}
                  onChange={(e) => toggle(question, answer, e.target.checked)}
                />
                <span>{answer}</span>
              </label>
            ))}
          </div>
        </fieldset>
        {error && (
          <div className="alert alert-danger" role="alert">
            <p>{error.text}</p>
            {error.restart && (
              <p>
                <button type="button" className="secondary" onClick={restart}>
                  Start again
                </button>
              </p>
            )}
          </div>
        )}
        <div className="actions">
          {step > 0 && (
            <button type="button" className="secondary" onClick={() => setStep(step - 1)} disabled={busy}>
              Back
            </button>
          )}
          <button type="submit" disabled={chosen(question).length === 0 || busy} aria-busy={busy}>
            {step < total - 1 ? 'Next' : busy ? 'Working it out…' : 'See what to book'}
          </button>
        </div>
      </form>
      <p className="muted small">This is a suggestion from a stylist's own rules, not a diagnosis. Combpatible doesn't take bookings or payments.</p>
    </section>
  )
}

const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })

function ResultView({ result, finder, onRestart }: { result: ConsultResult; finder: Finder; onRestart: () => void }) {
  const { outcome } = result
  const consultation = outcome.kind === 'CONSULTATION'
  const facts = [
    outcome.hours != null && outcome.hours > 0 ? formatHours(outcome.hours) : null,
    // A from-price of 0 is a gap in the example data, not a promise that it's free.
    outcome.fromPrice != null && outcome.fromPrice > 0 ? `from ${money.format(outcome.fromPrice)}` : null,
  ].filter((f): f is string => f !== null)
  const credit = result.credit ?? finder.credit

  return (
    <section className="find consult-result">
      <span className="eyebrow">Your result</span>
      <div className="consult-strand" aria-hidden="true">
        <Strand shape="WAVY" horizontal width={300} height={48} bend={0.5} strokeWidth={2.5} />
      </div>
      {consultation ? (
        <>
          <h1>Book a consultation first</h1>
          <p className="consult-service">{outcome.serviceName}</p>
        </>
      ) : (
        <h1>
          Book <em>{outcome.serviceName}</em>
        </h1>
      )}
      {facts.length > 0 && <p className="consult-facts">{facts.join(', ')}</p>}
      {outcome.why && (
        <div className="consult-why">
          <h2 className="eyebrow">{consultation ? 'Why a consultation' : 'Why this'}</h2>
          <p>{outcome.why}</p>
        </div>
      )}
      {outcome.whatToExpect && (
        <div className="consult-why">
          <h2 className="eyebrow">What to expect</h2>
          <p>{outcome.whatToExpect}</p>
        </div>
      )}
      <p className="consult-confirm">Your stylist confirms at your appointment.</p>
      {credit && <p className="muted small">Questions and answers by {credit}.</p>}

      <NextSteps result={result} credit={credit} />

      <p>
        <button type="button" className="link" onClick={onRestart}>
          Start again
        </button>
      </p>
    </section>
  )
}

function NextSteps({ result, credit }: { result: ConsultResult; credit: string | null }) {
  const { outcome } = result
  if (result.channel === 'PARTNER' && outcome.bookingUrl) {
    return <BookStep sessionId={result.sessionId} bookingUrl={outcome.bookingUrl} serviceName={outcome.serviceName} />
  }
  return (
    <div className="consult-actions">
      {result.channel === 'PARTNER' && (
        <p>
          Book <b>{outcome.serviceName}</b> with {credit ?? 'the stylist'} directly: ask for it by name when you book.
        </p>
      )}
      <FindStylistButton sessionId={result.sessionId} serviceType={outcome.serviceType} secondary={result.channel === 'PARTNER'} />
    </div>
  )
}

/**
 * The partner's Book button. The click is recorded first (with the tick, unticked by default), then the stylist's own
 * booking page opens. Recording is best effort: a failed record never keeps the client from booking.
 */
function BookStep({ sessionId, bookingUrl, serviceName }: { sessionId: string; bookingUrl: string; serviceName: string }) {
  const [share, setShare] = useState(false)
  const [busy, setBusy] = useState(false)

  async function book() {
    setBusy(true)
    // Open the tab inside the click, so a popup blocker allows it, then send it on once the click is recorded.
    const tab = window.open('', '_blank')
    try {
      await recordBook(sessionId, share)
    } catch {
      // best effort: the client books either way
    } finally {
      setBusy(false)
    }
    if (tab) {
      tab.opener = null
      tab.location.href = bookingUrl
    } else {
      window.location.assign(bookingUrl)
    }
  }

  return (
    <div className="consult-actions">
      <label className="check consult-share">
        <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} />
        <span>Send my answers to the stylist</span>
      </label>
      <button type="button" className="block" onClick={() => void book()} disabled={busy} aria-busy={busy}>
        Book {serviceName}
      </button>
      <p className="muted small">Opens the stylist's own booking page.</p>
    </div>
  )
}

/** Hands the outcome's service to find-my-stylist, which starts with it picked (FindPage reads ?service=). */
function FindStylistButton({ sessionId, serviceType, secondary }: { sessionId: string; serviceType: string; secondary: boolean }) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)

  async function go() {
    setBusy(true)
    try {
      await recordFindStylist(sessionId)
    } catch {
      // best effort: the hand-off doesn't wait on the record
    }
    navigate(`/find?service=${encodeURIComponent(serviceType)}`)
  }

  return (
    <button type="button" className={secondary ? 'secondary block' : 'block'} onClick={() => void go()} disabled={busy} aria-busy={busy}>
      Find a stylist for this
    </button>
  )
}
