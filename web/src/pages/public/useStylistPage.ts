import { useEffect, useState } from 'react'
import { apiPath, publicApi } from '../../api'
import type { StylistPublicPage } from '../../stylists/types'

/**
 * One live stylist's whole portfolio, grouped by hair type, with their practice (/api/public/stylists/{orgId}). Undefined
 * while loading; null with no orgId or when it couldn't be loaded (the dialog then shows the photos it was given).
 */
export function useStylistPage(orgId: string | undefined): StylistPublicPage | null | undefined {
  const [page, setPage] = useState<{ orgId: string; page: StylistPublicPage | null } | null>(null)
  useEffect(() => {
    if (!orgId) return
    let live = true
    publicApi<StylistPublicPage>('GET', apiPath`/api/public/stylists/${orgId}`)
      .then((p) => live && setPage({ orgId, page: p }))
      .catch(() => live && setPage({ orgId, page: null }))
    return () => {
      live = false
    }
  }, [orgId])
  if (!orgId) return null
  return page?.orgId === orgId ? page.page : undefined
}
