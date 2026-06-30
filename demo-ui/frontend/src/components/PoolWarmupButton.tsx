import { useEffect, useState } from 'react'
import { generatorApi } from '@/api/client'
import type { PoolStatus } from '@/types/api'

/**
 * Header-mounted control for pre-warming the data-generator GKE node pool just
 * before a demo. The pool defaults to min_nodes=1 (so the cluster isn't
 * burning N idle nodes between demos); this button calls
 * /api/generator/pool/warmup which kicks off an async gcloud resize up to
 * max_nodes. While the pool scales, the pill polls /api/generator/pool/status
 * every POLL_INTERVAL_MS so the operator can watch the count climb.
 *
 * State:
 * - cold:    current ≤ 1 node, idle → "Warm up" enabled
 * - scaling: 1 < current < max → "Warming…" disabled
 * - warm:    current ≥ max → "Warm" disabled
 */
const POLL_INTERVAL_MS = 10_000

export function PoolWarmupButton() {
  const [status, setStatus] = useState<PoolStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const fetchStatus = async () => {
      try {
        const s = await generatorApi.poolStatus()
        if (!cancelled) setStatus(s)
      } catch (e) {
        if (!cancelled) {
          // Pool status calls can fail when the cluster isn't deployed yet — surface
          // briefly but don't block the rest of the header. The polling continues
          // and will recover once the cluster is up.
          console.warn('pool status fetch failed', e)
        }
      }
    }
    fetchStatus()
    const interval = setInterval(fetchStatus, POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [])

  const onClick = async () => {
    setBusy(true)
    setError(null)
    try {
      const fresh = await generatorApi.warmupPool()
      setStatus(fresh)
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setError(msg)
    } finally {
      setBusy(false)
    }
  }

  if (!status) {
    return (
      <div className="text-[10px] font-mono uppercase tracking-wider text-surface-500">
        Pool …
      </div>
    )
  }

  const isWarm = status.state === 'warm'
  const isScaling = status.state === 'scaling' || busy
  const stateColor =
    status.state === 'warm'
      ? 'text-emerald-400'
      : status.state === 'scaling'
        ? 'text-amber-400'
        : 'text-surface-500'

  return (
    <div className="flex items-center gap-2" title={error ?? `Generator pool ${status.pool_name}`}>
      <div className="text-[10px] font-mono uppercase tracking-wider text-surface-400">
        Pool:{' '}
        <span className={stateColor}>{status.state}</span>
        <span className="ml-1 text-surface-300 tabular-nums">
          {status.current_nodes}/{status.max_nodes}
        </span>
      </div>
      <button
        onClick={onClick}
        disabled={isWarm || isScaling || busy}
        className={[
          'px-2 py-1 text-xs font-mono rounded transition-colors',
          isWarm || isScaling || busy
            ? 'bg-surface-800 text-surface-500 cursor-not-allowed'
            : 'bg-gg-500 text-white hover:bg-gg-400',
        ].join(' ')}
      >
        {busy ? 'Warming…' : isWarm ? 'Warm' : isScaling ? 'Scaling…' : 'Warm up'}
      </button>
    </div>
  )
}
