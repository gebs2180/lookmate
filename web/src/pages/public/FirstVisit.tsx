// "Before your first visit" and the new-client status, wherever clients see a stylist (feature ideas 1 and 3). Both are
// shown only: never a filter, never in matching or the order.
import { firstVisitFacts, newClientsLine } from '../../stylists/practice'
import type { FirstVisit, FirstVisitNote, NewClients } from '../../stylists/types'

/** "Taking new clients · usually about 3 weeks out". Book stays: it's a heads-up, not a gate. */
export function NewClientsLine({ newClients }: { newClients: NewClients | null | undefined }) {
  const line = newClientsLine(newClients)
  if (!line) return null
  const not = newClients?.status === 'NOT_TAKING'
  return <p className={not ? 'new-clients not-taking' : 'new-clients'}>{line}</p>
}

/**
 * Before your first visit: the stylist's own facts and note first, then the generic notes. Nothing at all when there's
 * nothing to say.
 */
export function FirstVisitBlock({
  name,
  firstVisit,
  notes = [],
  heading = 'Before your first visit',
}: {
  name: string
  firstVisit: FirstVisit | null | undefined
  notes?: FirstVisitNote[]
  heading?: string
}) {
  const facts = firstVisitFacts(firstVisit)
  const own = firstVisit?.note ?? null
  if (facts.length === 0 && !own && notes.length === 0) return null
  return (
    <section className="first-visit" aria-label={heading}>
      <h3 className="eyebrow">{heading}</h3>
      {(facts.length > 0 || own) && (
        <ul className="fv-own">
          {facts.map((f) => (
            <li key={f}>{f}</li>
          ))}
          {own && (
            <li>
              <span className="fv-from">From {name}:</span> {own}
            </li>
          )}
        </ul>
      )}
      {notes.length > 0 && (
        <ul className="fv-notes">
          {notes.map((n) => (
            <li key={n.key}>{n.text}</li>
          ))}
        </ul>
      )}
    </section>
  )
}
