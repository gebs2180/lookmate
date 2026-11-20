import { useEffect, useState, type RefObject } from 'react'

const REDUCE = '(prefers-reduced-motion: reduce)'

/** True when the person asked their device for less motion. Tracks changes while the page is open. */
export function useReducedMotion(): boolean {
  const [reduce, setReduce] = useState(() => typeof window !== 'undefined' && !!window.matchMedia?.(REDUCE).matches)
  useEffect(() => {
    const query = window.matchMedia?.(REDUCE)
    if (!query) return
    const onChange = () => setReduce(query.matches)
    query.addEventListener?.('change', onChange)
    return () => query.removeEventListener?.('change', onChange)
  }, [])
  return reduce
}

/**
 * True while the element is on screen, so looping animations can stop when it isn't. Without IntersectionObserver
 * (old browsers, tests) it reports false: loops stay still rather than run where we can't tell.
 */
export function useInView(ref: RefObject<Element | null>, rootMargin = '0px'): boolean {
  const [inView, setInView] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => setInView(entry.isIntersecting), { rootMargin })
    observer.observe(el)
    return () => observer.disconnect()
  }, [ref, rootMargin])
  return inView
}

/** A loop runs only on screen, with motion allowed, in a visible tab. */
export function useLoopRunning(ref: RefObject<Element | null>): boolean {
  const reduce = useReducedMotion()
  const inView = useInView(ref)
  const [visible, setVisible] = useState(() => typeof document === 'undefined' || document.visibilityState !== 'hidden')
  useEffect(() => {
    const onChange = () => setVisible(document.visibilityState !== 'hidden')
    document.addEventListener('visibilitychange', onChange)
    return () => document.removeEventListener('visibilitychange', onChange)
  }, [])
  return inView && visible && !reduce
}

/**
 * True once the element has been on screen, and stays true: for something that plays once when first seen (a strand
 * drawing itself in a cover further along the row). Without IntersectionObserver it's true at once, so nothing waits
 * forever where we can't tell.
 */
export function useSeenOnce(ref: RefObject<Element | null>): boolean {
  const [seen, setSeen] = useState(() => typeof IntersectionObserver === 'undefined')
  useEffect(() => {
    const el = ref.current
    if (seen || !el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) setSeen(true)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [ref, seen])
  return seen
}
