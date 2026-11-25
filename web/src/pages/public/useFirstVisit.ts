// The data behind "Before your first visit" and the new-client status (feature ideas 1 and 3): a stylist's practice and
// the generic notes for what the client asked for.
import { useEffect, useState } from 'react'
import { apiPath, publicApi } from '../../api'
import type { FirstVisitNote, Practice } from '../../stylists/types'

/** What the client asked for, so the generic notes fit it. Either may be unknown. */
export type Visit = { service: string | null; hairType: string | null }

/**
 * A stylist's practice: the one given, or fetched by orgId when it's left out (a client's picks don't carry it).
 * Undefined while loading; null when there's none or it couldn't be loaded (an optional extra on a page that works
 * without it).
 */
export function usePractice(orgId: string | undefined, given?: Practice | null): Practice | null | undefined {
  const [fetched, setFetched] = useState<Practice | null | undefined>(undefined)
  useEffect(() => {
    if (given !== undefined || !orgId) return
    let live = true
    publicApi<Practice>('GET', apiPath`/api/public/stylists/${orgId}/practice`)
      .then((p) => live && setFetched(p))
      .catch(() => live && setFetched(null))
    return () => {
      live = false
    }
  }, [given, orgId])
  if (given !== undefined) return given
  return orgId ? fetched : null
}

/** The generic notes for a service and hair type, most specific first (at most four); empty until loaded. */
export function useFirstVisitNotes(visit: Visit | undefined): FirstVisitNote[] {
  const [notes, setNotes] = useState<FirstVisitNote[]>([])
  const service = visit?.service ?? null
  const hairType = visit?.hairType ?? null
  const wanted = visit !== undefined
  useEffect(() => {
    if (!wanted) return
    let live = true
    const query = new URLSearchParams()
    if (service) query.set('service', service)
    if (hairType) query.set('hairType', hairType)
    const qs = query.toString()
    publicApi<FirstVisitNote[]>('GET', `/api/public/stylists/first-visit-notes${qs ? `?${qs}` : ''}`)
      .then((n) => live && setNotes(n))
      // Advice, not the page: leave it out if it can't load.
      .catch(() => {})
    return () => {
      live = false
    }
  }, [wanted, service, hairType])
  return notes
}
