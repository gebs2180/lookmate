import { useEffect } from 'react'
import { useLocation, useNavigationType } from 'react-router'

/** Moves focus to the page's heading, so a screen reader announces it. Focusing doesn't scroll: the page says where it is. */
export function focusHeading() {
  const h1 = document.querySelector<HTMLElement>('main h1')
  if (!h1) return false
  h1.tabIndex = -1
  h1.focus({ preventScroll: true })
  return true
}

/**
 * What a full page load does and a client-side route change doesn't: start at the top (Back keeps its place), move
 * focus to the new heading and name the tab after it. Pages load their heading with their data, so this watches for it.
 */
export function useRouteFocus() {
  const { pathname, hash, key } = useLocation()
  const navigationType = useNavigationType()
  const firstLoad = key === 'default'

  useEffect(() => {
    if (navigationType !== 'POP' && !hash) window.scrollTo(0, 0)
    document.title = 'Combpatible'
    let focused = firstLoad
    const sync = () => {
      const h1 = document.querySelector('main h1')
      if (!h1) return false
      document.title = `${h1.textContent} · Combpatible`
      if (!focused) focused = focusHeading()
      return focused
    }
    // Once the heading is found and focused the job is done: stop watching every DOM change on the page.
    if (sync()) return
    const observer = new MutationObserver(() => {
      if (sync()) observer.disconnect()
    })
    observer.observe(document.body, { childList: true, subtree: true, characterData: true })
    return () => observer.disconnect()
  }, [pathname, hash, navigationType, firstLoad])
}
