import { useEffect } from 'react'

/** The link is the client's key: no page they open from here, and no image host, learns it from a Referer header. */
export function useNoReferrer() {
  useEffect(() => {
    const meta = document.createElement('meta')
    meta.name = 'referrer'
    meta.content = 'no-referrer'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])
}
