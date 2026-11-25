import type { components } from './api.gen'
import type { Practice } from './stylists/types'

// API types come from api.gen.ts (`npm run api:types` after the backend rewrites openapi.json). The generated types
// don't mark nullable fields, so where the API sends null they're narrowed here; everything else is the generated type.

export type OrgType = 'STYLIST'

export type Membership = { orgId: string; orgName: string; orgType: OrgType; role: string }

export type Me = {
  subject: string
  issuer: string | null
  roles: string[]
  claimedRoles: string[]
  mfa: boolean
  memberships: Membership[]
}

export type Org = { id: string; name: string; type: OrgType; createdAt: string; members: number }
export type CreatedInvite = { id: string; token: string; acceptPath: string; expiresAt: string }
export type OpenInvite = { id: string; createdAt: string; expiresAt: string }
export type OrgMember = { id: string; email: string | null; role: string; joinedAt: string }
export type InvitePreview = { orgName: string; orgType: OrgType; role: string; expiresAt: string; usable: boolean }

export type UploadSlot = {
  photoId: string
  url: string
  method: string
  headers: Record<string, string>
  expiresAt: string
}

export class ApiError extends Error {
  status: number
  /** The problem detail's `code`, when the API gave one: branch on this, never on the message. */
  code: string | undefined
  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

/** Builds an API path, URL-encoding every interpolated segment: apiPath`/api/orgs/${orgId}`. */
export function apiPath(strings: TemplateStringsArray, ...values: (string | number)[]) {
  return strings.reduce((out, s, i) => out + s + (i < values.length ? encodeURIComponent(values[i]) : ''), '')
}

export const UNREACHABLE = "Couldn't reach Combpatible. Check your connection and try again."
export const UPLOAD_FAILED = "The photo didn't upload. Check your connection and try again."

/**
 * A fetch that never reached the server (offline, blocked, dropped) rejects with the browser's own
 * TypeError, e.g. "Failed to fetch"; people see `message` instead.
 */
export async function reach(request: () => Promise<Response>, message: string): Promise<Response> {
  try {
    return await request()
  } catch (e) {
    if (e instanceof TypeError) throw new Error(message, { cause: e })
    throw e
  }
}

/** PUTs a file straight to storage using a presigned upload slot. */
export async function uploadToStorage(slot: UploadSlot, file: Blob): Promise<void> {
  const response = await reach(() => fetch(slot.url, { method: slot.method, headers: slot.headers, body: file }), UPLOAD_FAILED)
  if (!response.ok) {
    // An expired slot (403) or a storage hiccup: asking again gets a fresh slot.
    throw new ApiError(response.status, `The photo didn't upload (storage answered ${response.status}). Please try again.`)
  }
}

// Client intake (signed out): /find and /m/:token. Types from the API description (api.gen.ts).


export type MatchRequestCreate = components['schemas']['IntakeControllerCreateRequest']
export type MatchRequestCreated = components['schemas']['IntakeControllerCreated']
export type ClientView = Omit<
  components['schemas']['IntakeServiceClientView'],
  'photoUrl' | 'photoDeletesAt' | 'firstName' | 'texture' | 'length' | 'emailHint'
> & {
  photoUrl: string | null
  /** A masked hint of the client's email ("a•••@example.com"), or null when they haven't given one. */
  emailHint: string | null
  photoDeletesAt: string | null
  firstName: string | null
  texture: string | null
  length: string | null
}
export type ClientMatch = components['schemas']['ClientMatch']
export type IntakeSettings = components['schemas']['IntakeServiceSettings']

/** The public API, for clients who never sign in: no token, the same problem-detail errors as {@link useApi}. */
export async function publicApi<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await reach(
    () =>
      fetch(path, {
        method,
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    UNREACHABLE,
  )
  if (!response.ok) {
    throw await problemError(
      response,
      response.status === 404 ? "We couldn't find that." : `Something went wrong (${response.status}). Please try again.`,
    )
  }
  return readBody<T>(response)
}

/** An ApiError from a failed response: the problem detail's text and `code` when the body is one, else the fallback. */
export async function problemError(response: Response, fallback: string): Promise<ApiError> {
  let detail = fallback
  let code: string | undefined
  try {
    const problem = await response.json()
    detail = problem.detail ?? problem.title ?? fallback
    code = typeof problem.code === 'string' ? problem.code : undefined
  } catch {
    // not JSON
  }
  return new ApiError(response.status, detail, code)
}

export async function readBody<T>(response: Response): Promise<T> {
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}
// Matching: the admin's review of a request's matches, and the fairness report (backend: matching module).

export type HairType = 'STRAIGHT' | 'WAVY' | 'CURLY' | 'COILY'
export type MatchRequestStatus = 'NEW' | 'MATCHING' | 'IN_REVIEW' | 'PUBLISHED' | 'CLOSED' | 'FAILED'

type ReviewSchemas = components['schemas']

export type MatchQueueItem = Omit<
  ReviewSchemas['ReviewServiceQueueItem'],
  'status' | 'statusReason' | 'service' | 'hairType' | 'texture' | 'length' | 'firstName'
> & {
  status: MatchRequestStatus
  statusReason: string | null
  service: string | null
  hairType: HairType | null
  texture: string | null
  length: string | null
  firstName: string | null
}

export type MatchRequestView = Omit<
  ReviewSchemas['ReviewServiceRequestView'],
  'status' | 'statusReason' | 'service' | 'hairType' | 'texture' | 'length' | 'budgetTier' | 'neighborhood' | 'notes' | 'firstName' | 'publishedAt'
> & {
  status: MatchRequestStatus
  statusReason: string | null
  service: string | null
  hairType: HairType | null
  texture: string | null
  length: string | null
  budgetTier: number | null
  neighborhood: string | null
  notes: string | null
  firstName: string | null
  publishedAt: string | null
}

export type EvidencePhoto = Omit<ReviewSchemas['ReviewServiceEvidencePhoto'], 'service' | 'hairType'> & {
  service: string | null
  hairType: string | null
}

export type MatchView = Omit<
  ReviewSchemas['ReviewServiceMatchView'],
  'stylistName' | 'locationName' | 'instagram' | 'finalRank' | 'adminNote' | 'source' | 'evidence'
> & {
  stylistName: string | null
  locationName: string | null
  instagram: string | null
  finalRank: number | null
  adminNote: string | null
  source: 'SYSTEM' | 'ADMIN'
  evidence: EvidencePhoto[]
}

export type MatchReview = { request: MatchRequestView; inspirationUrl: string | null; matches: MatchView[] }
export type StylistOption = Omit<ReviewSchemas['ReviewServiceStylistOption'], 'locationName'> & { locationName: string | null }

export type FairnessRow = Omit<ReviewSchemas['CandidateQueriesFairnessRow'], 'hairType' | 'meanTopScore'> & {
  hairType: HairType | 'UNKNOWN'
  meanTopScore: number | null
}
export type FairnessReport = { market: string; since: string; rows: FairnessRow[] }
// Tracking (booking clicks, manual bookings, pilot metrics): generated types, named for the pages.
import type { components as TrackingSchemas } from './api.gen'
export type Metrics = TrackingSchemas['schemas']['MetricsServiceMetrics']
export type MetricsWeek = TrackingSchemas['schemas']['TrackingQueriesWeekRow']
export type StylistStats = TrackingSchemas['schemas']['MetricsServiceStylistStats']
export type RecentMatch = TrackingSchemas['schemas']['TrackingQueriesRecentMatch']
export type RecordedEvent = TrackingSchemas['schemas']['TrackingServiceRecorded']
export type MatchEventView = TrackingSchemas['schemas']['MatchEventsEvent']
export type StylistMatch = TrackingSchemas['schemas']['StylistTrackingControllerStylistMatch']

// Matching publishes on its own (2026-11-20): the admin's monitor, the public stylist directory, directory bookings.
type Schemas = components['schemas']
export type MonitorMatch = Omit<Schemas['ReviewServiceMonitorMatch'], 'stylistName' | 'finalRank' | 'source'> & {
  stylistName: string | null
  finalRank: number | null
  source: 'SYSTEM' | 'ADMIN'
}
export type MonitorItem = Omit<
  Schemas['ReviewServiceMonitorItem'],
  'status' | 'statusReason' | 'service' | 'hairType' | 'firstName' | 'publishedAt' | 'firstViewedAt' | 'matches'
> & {
  status: MatchRequestStatus
  statusReason: string | null
  service: string | null
  hairType: HairType | null
  firstName: string | null
  publishedAt: string | null
  firstViewedAt: string | null
  matches: MonitorMatch[]
}
export type MatchMonitor = { reviewBeforePublish: boolean; requests: MonitorItem[] }
export type DirectoryStylist = Omit<
  Schemas['StylistDirectoryDirectoryStylist'],
  'locationName' | 'city' | 'instagram' | 'practice'
> & {
  locationName: string | null
  city: string | null
  instagram: string | null
  practice: Practice
}
export type DirectoryPage = Omit<Schemas['StylistDirectoryDirectoryPage'], 'service' | 'hairType' | 'stylists'> & {
  service: string | null
  hairType: string | null
  stylists: DirectoryStylist[]
}
export type DirectoryClick = Omit<
  Schemas['TrackingQueriesDirectoryClick'],
  'stylistName' | 'clientFirstName' | 'clientEmail' | 'service' | 'bookedEventId'
> & {
  stylistName: string | null
  clientFirstName: string | null
  clientEmail: string | null
  service: string | null
  bookedEventId: string | null
}

// The stylist weekly digest (backend: tracking, docs/tracking.md "Weekly digest").

/** What a digest Booked/Rebooked link is about: first name and service only. */
export type DigestLink = Omit<Schemas['WeeklyDigestsLinkInfo'], 'clientFirstName' | 'service' | 'eventId'> & {
  clientFirstName: string | null
  service: string | null
  eventId: string | null
}
export type DigestSettingView = Schemas['DigestControllerDigestSetting']
