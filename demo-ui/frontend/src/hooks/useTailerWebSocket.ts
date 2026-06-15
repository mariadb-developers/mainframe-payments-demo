import { useCallback, useEffect, useRef, useState } from 'react'
import type { TailerEvent } from '@/types/api'

const MAX_BUFFER = 50

/**
 * One hook instance per tailer source (gg-to-postgres, gg-to-mariadb, cdc).
 * Holds the last N events; auto-prunes so phase-5's load run doesn't OOM the
 * browser (CLAUDE.md §5).
 *
 * Connection retries on close with a small backoff so the UI survives backend
 * restarts during demo dev.
 *
 * `enabled` (default true) gates the subscription: when false the socket is
 * closed and no reconnect is attempted. The GG→Postgres / GG→MariaDB streams are
 * disabled under high load — those stores aren't scaled for the load run, so their
 * panels are hidden and there's no reason to keep streaming (CLAUDE.md §3/§5).
 */
export function useTailerWebSocket(source: string, enabled: boolean = true) {
  const [events, setEvents] = useState<TailerEvent[]>([])
  const [connected, setConnected] = useState(false)
  // Events/sec on this path, measured from client-side arrival times over a 1s window. Decays
  // to 0 when events stop (the 500ms interval prunes), so summary mode reverts cleanly. Used by
  // the tailer to switch to flow/summary rendering above a threshold (CLAUDE.md §3/§5).
  const [rate, setRate] = useState(0)
  const arrivalsRef = useRef<number[]>([])
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<number | null>(null)

  useEffect(() => {
    const id = window.setInterval(() => {
      const cutoff = Date.now() - 1000
      arrivalsRef.current = arrivalsRef.current.filter((t) => t >= cutoff)
      setRate((prev) => (prev === arrivalsRef.current.length ? prev : arrivalsRef.current.length))
    }, 500)
    return () => window.clearInterval(id)
  }, [])

  useEffect(() => {
    if (!enabled) {
      // Stream disabled (e.g. under high load) — make sure any open socket is closed
      // and skip connecting. Flipping `enabled` back to true re-runs this effect and
      // reconnects. The in-memory event buffer is left intact.
      setConnected(false)
      return
    }
    let cancelled = false
    function connect() {
      if (cancelled) return
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const ws = new WebSocket(`${protocol}//${window.location.host}/api/tailers/${source}`)
      wsRef.current = ws
      ws.onopen = () => setConnected(true)
      ws.onmessage = (e) => {
        try {
          const ev: TailerEvent = JSON.parse(e.data)
          arrivalsRef.current.push(Date.now())
          setEvents((prev) => {
            const next = [...prev, ev]
            if (next.length > MAX_BUFFER) next.splice(0, next.length - MAX_BUFFER)
            return next
          })
        } catch {
          /* ignore malformed frames */
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (!cancelled) {
          reconnectRef.current = window.setTimeout(connect, 2000)
        }
      }
      ws.onerror = () => {
        setConnected(false)
      }
    }
    connect()
    return () => {
      cancelled = true
      if (reconnectRef.current) window.clearTimeout(reconnectRef.current)
      wsRef.current?.close()
    }
  }, [source, enabled])

  // Empties the in-memory buffer without dropping the WebSocket — used on demo
  // reset so the Mainframe→GG window starts the phase-2 beat clean (the reseed
  // burst that fills Kafka shouldn't clutter the window before the presenter
  // fires the in-flight transaction).
  const clear = useCallback(() => setEvents([]), [])

  return { events, connected, clear, rate }
}
