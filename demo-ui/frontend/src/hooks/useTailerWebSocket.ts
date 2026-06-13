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
 */
export function useTailerWebSocket(source: string) {
  const [events, setEvents] = useState<TailerEvent[]>([])
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<number | null>(null)

  useEffect(() => {
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
  }, [source])

  // Empties the in-memory buffer without dropping the WebSocket — used on demo
  // reset so the Mainframe→GG window starts the phase-2 beat clean (the reseed
  // burst that fills Kafka shouldn't clutter the window before the presenter
  // fires the in-flight transaction).
  const clear = useCallback(() => setEvents([]), [])

  return { events, connected, clear }
}
