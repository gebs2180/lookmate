import { ApiError, apiPath, publicApi } from '../api'
import type { components } from '../api.gen'

// The service finder's API (backend: the consult module, docs/modules/consult.md). The generated types don't mark
// nullable fields, so the ones the API can send as null are narrowed here.

type Schemas = components['schemas']

export type Channel = Schemas['ConsultServiceFinder']['channel']

export type FinderQuestion = Omit<Schemas['ConsultServiceQuestion'], 'help'> & {
  /** Notes to us in the example data (photo ideas), not client copy: never shown to clients. */
  help: string | null
}

export type Finder = Omit<Schemas['ConsultServiceFinder'], 'credit' | 'questions'> & {
  credit: string | null
  questions: FinderQuestion[]
}

export type Outcome = Omit<Schemas['ConsultServiceOutcome'], 'hours' | 'fromPrice' | 'bookingUrl' | 'whatToExpect' | 'why'> & {
  hours: number | null
  fromPrice: number | null
  bookingUrl: string | null
  whatToExpect: string | null
  why: string | null
}

export type ConsultResult = Omit<Schemas['ConsultServiceResult'], 'credit' | 'outcome'> & {
  credit: string | null
  outcome: Outcome
}

/** Answer texts by question id, exactly as the finder lists them. */
export type Answers = Record<string, string[]>

/** The general channel (the Combpatible button) or a partner's own link. */
export type Source = { channel: 'GENERAL' } | { channel: 'PARTNER'; slug: string }

function base(source: Source) {
  return source.channel === 'PARTNER' ? apiPath`/api/public/consult/c/${source.slug}` : '/api/public/consult'
}

export function loadFinder(source: Source) {
  return publicApi<Finder>('GET', `${base(source)}/finder`)
}

export function submitAnswers(source: Source, answers: Answers) {
  return publicApi<ConsultResult>('POST', `${base(source)}/sessions`, { answers })
}

export function recordBook(sessionId: string, shareAnswers: boolean) {
  return publicApi<void>('POST', apiPath`/api/public/consult/sessions/${sessionId}/book`, { shareAnswers })
}

export function recordFindStylist(sessionId: string) {
  return publicApi<void>('POST', apiPath`/api/public/consult/sessions/${sessionId}/find-stylist`)
}

/** What a client sees when sending answers fails: the limit and stale answers get their own words. */
export function submitError(e: unknown): { text: string; restart: boolean } {
  if (e instanceof ApiError && e.status === 429) {
    return { text: "You've tried a lot of times in a short while. Wait a few minutes, then try again.", restart: false }
  }
  if (e instanceof ApiError && e.status === 400) {
    return { text: 'Those answers no longer fit the questions, which may have just been updated. Please start again.', restart: true }
  }
  return { text: e instanceof Error ? e.message : String(e), restart: false }
}
