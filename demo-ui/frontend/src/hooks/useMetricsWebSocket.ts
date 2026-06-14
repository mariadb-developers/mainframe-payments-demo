import { useEffect, useRef, useState } from 'react'
import type { MetricsSnapshot } from '@/types/api'

const MAX_POINTS = 60 // ~60s of history at the backend's 1s push cadence

export interface MetricsPoint {
  tps: number
  latencyMs: number
}

export interface MetricsStream {
  latest: MetricsSnapshot | null
  history: MetricsPoint[]
  connected: boolean
}

/**
 * Subscribes to /api/metrics (generator throughput + GridGain execution latency, pushed ~1s)
 * and keeps a rolling window for the sparklines. The backend already collapses idle/stale state
 * to zeros, so the graphs flatline cleanly between runs. Reconnects on close, mirroring
 * useTailerWebSocket.
 */
export function useMetricsWebSocket(): MetricsStream {
  const [latest, setLatest] = useState<MetricsSnapshot | null>(null)
  const [history, setHistory] = useState<MetricsPoint[]>([])
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    function connect() {
      if (cancelled) return
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const ws = new WebSocket(`${protocol}//${window.location.host}/api/metrics`)
      wsRef.current = ws
      ws.onopen = () => setConnected(true)
      ws.onmessage = (e) => {
        try {
          const snap: MetricsSnapshot = JSON.parse(e.data)
          setLatest(snap)
          setHistory((prev) => {
            const next = [...prev, { tps: snap.observed_tps, latencyMs: snap.avg_latency_ms }]
            if (next.length > MAX_POINTS) next.splice(0, next.length - MAX_POINTS)
            return next
          })
        } catch {
          /* ignore malformed frames */
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (!cancelled) reconnectRef.current = window.setTimeout(connect, 2000)
      }
      ws.onerror = () => setConnected(false)
    }
    connect()
    return () => {
      cancelled = true
      if (reconnectRef.current) window.clearTimeout(reconnectRef.current)
      wsRef.current?.close()
    }
  }, [])

  return { latest, history, connected }
}
