import { useEffect, useState } from 'react'
import { connectorsApi } from '@/api/client'
import type { ConnectorHealth } from '@/types/api'

// Poll cadence for connector health. A sink task dies rarely (stale DB connection between runs),
// so a slow poll is plenty — this is a safety net that makes a dead sink visible, not a live feed.
const POLL_MS = 8000

/**
 * Header warning pill: shows "⚠ N sink task(s) down" whenever any Kafka Connect task is FAILED
 * (connector RUNNING but applying nothing). The tailer "● live" badge reflects the browser↔backend
 * socket, not the sink, so a dead sink is otherwise silent (it's why phase-5 reconciliation could
 * miss events). Hidden when everything is healthy.
 */
export function ConnectorHealthPill() {
  const [health, setHealth] = useState<ConnectorHealth | null>(null)

  useEffect(() => {
    let cancelled = false
    const poll = () => {
      connectorsApi
        .health()
        .then((h) => {
          if (!cancelled) setHealth(h)
        })
        .catch(() => {
          /* Connect unreachable — leave the last known state; don't flap a false warning. */
        })
    }
    poll()
    const id = window.setInterval(poll, POLL_MS)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  const failed = health?.failed_tasks ?? []
  if (failed.length === 0) return null

  const detail = failed.map((f) => `${f.connector} (task ${f.task})`).join('\n')
  return (
    <div
      className="px-2.5 py-1 text-xs font-mono rounded bg-rose-900/70 text-rose-200 border border-rose-700"
      title={`Failed Kafka Connect tasks — the connector is running but applying nothing:\n${detail}`}
    >
      ⚠ {failed.length} sink task{failed.length > 1 ? 's' : ''} down
    </div>
  )
}
