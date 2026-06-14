import { useEffect, useRef, useState } from 'react'

/**
 * Returns a counter that increments whenever [trigger] changes, but at most once per
 * [intervalMs] — leading edge (fires immediately if idle) plus a trailing edge (one bump
 * after a burst). Used to derive panel reloadKeys from the tailer event count: at low rate
 * each landed event refreshes the panel immediately (the phase 3/4 beat stays snappy); at
 * phase-6 load the 200 events/sec coalesce into ~1 refresh/sec instead of 200 backend queries.
 */
export function useThrottledBump(trigger: number, intervalMs: number): number {
  const [bump, setBump] = useState(0)
  const lastFiredRef = useRef(0)
  const timerRef = useRef<number | null>(null)
  const firstRef = useRef(true)

  useEffect(() => {
    // Skip the initial mount so the bump starts at 0 (the panel's own mount handles first load).
    if (firstRef.current) {
      firstRef.current = false
      return
    }
    const now = Date.now()
    const since = now - lastFiredRef.current
    if (since >= intervalMs) {
      lastFiredRef.current = now
      setBump((b) => b + 1) // leading edge
    } else if (timerRef.current == null) {
      timerRef.current = window.setTimeout(() => {
        lastFiredRef.current = Date.now()
        timerRef.current = null
        setBump((b) => b + 1) // trailing edge — coalesces the rest of the burst
      }, intervalMs - since)
    }
  }, [trigger, intervalMs])

  useEffect(
    () => () => {
      if (timerRef.current) window.clearTimeout(timerRef.current)
    },
    [],
  )

  return bump
}
