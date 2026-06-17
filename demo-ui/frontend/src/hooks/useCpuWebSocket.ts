import { useEffect, useRef, useState } from 'react'

const MAX_POINTS = 60 // ~60s of history at the backend's ~2s push cadence

interface CpuSnapshot {
  cpu_percent: number
  active: boolean
  updated_at_ms: number
}

export interface CpuStream {
  cpuPercent: number
  history: number[]
  connected: boolean
}

/**
 * Subscribes to /api/cpu (avg sys_CpuLoad across GG nodes, as a percent, pushed ~2s) and keeps a
 * rolling window for the sparkline. The backend emits an inactive 0 reading when Prometheus is
 * unreachable, so the gauge flatlines cleanly. Reconnects on close, mirroring useMetricsWebSocket.
 */
export function useCpuWebSocket(): CpuStream {
  const [cpuPercent, setCpuPercent] = useState(0)
  const [history, setHistory] = useState<number[]>([])
  const [connected, setConnected] = useState(false)
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectRef = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    function connect() {
      if (cancelled) return
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const ws = new WebSocket(`${protocol}//${window.location.host}/api/cpu`)
      wsRef.current = ws
      ws.onopen = () => setConnected(true)
      ws.onmessage = (e) => {
        try {
          const snap: CpuSnapshot = JSON.parse(e.data)
          setCpuPercent(snap.cpu_percent)
          setHistory((prev) => {
            const next = [...prev, snap.cpu_percent]
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

  return { cpuPercent, history, connected }
}
