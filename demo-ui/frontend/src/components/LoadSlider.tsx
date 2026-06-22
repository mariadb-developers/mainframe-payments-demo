import { useEffect, useRef, useState } from 'react'
import { generatorApi } from '@/api/client'

const MAX_PODS = 30
const APPROX_OPS_PER_POD = 500
// Debounce so rapid +/- clicks settle before we (re)launch — each change tears down and recreates
// the distributed run, so we only want to do it once the pod count settles.
const COMMIT_DEBOUNCE_MS = 700

export function LoadSlider({
  enabled,
  onRunningChange,
  resetSignal = 0,
}: {
  enabled: boolean
  // Notifies the App when the generator starts/stops, so it can hide + unsubscribe the
  // GG→Postgres / GG→MariaDB tailers under load (those stores aren't scaled for the run).
  onRunningChange?: (running: boolean) => void
  // Bumped by a demo reset. The slider is always mounted, so without this it would keep the prior
  // run's pod count; re-hydrating from the (already-reset) backend shows 0 pods (stopped).
  resetSignal?: number
}) {
  const [pods, setPods] = useState(0)
  const [running, setRunning] = useState(false)
  // Last pod count actually sent to the backend — guards the debounce effect from firing on
  // hydration and from re-sending no-ops.
  const committed = useRef(0)
  const hydrated = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Hydrate on mount AND whenever a reset bumps resetSignal, so the displayed pod count / running
  // state always reflect the backend (post-reset: 0 pods, stopped). committed stays in sync so the
  // debounce effect sees no change and doesn't re-launch.
  useEffect(() => {
    generatorApi
      .state()
      .then((s) => {
        setPods(s.replicas)
        setRunning(s.running)
        committed.current = s.replicas
      })
      .finally(() => {
        hydrated.current = true
      })
  }, [resetSignal])

  useEffect(() => {
    if (!hydrated.current) return
    if (pods === committed.current) return
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(async () => {
      const p = Math.min(MAX_PODS, Math.max(0, Math.round(pods)))
      try {
        const s = await generatorApi.setPods(p)
        committed.current = p
        setRunning(s.running)
      } catch (e) {
        console.warn('Generator pod change failed', e)
      }
    }, COMMIT_DEBOUNCE_MS)
    return () => {
      if (timer.current) clearTimeout(timer.current)
    }
  }, [pods])

  useEffect(() => {
    onRunningChange?.(running)
  }, [running, onRunningChange])

  const approxOps = (pods * APPROX_OPS_PER_POD).toLocaleString()

  return (
    <div className={['flex items-center gap-3', enabled ? '' : 'opacity-40 pointer-events-none'].join(' ')}>
      <div className="text-sm uppercase tracking-wider text-surface-400">Load</div>

      <button
        onClick={() => setPods(0)}
        className={[
          'px-2 py-1 text-xs font-mono rounded transition-colors',
          pods === 0 ? 'bg-gg-500 text-white' : 'bg-surface-800 text-surface-300 hover:bg-surface-700',
        ].join(' ')}
      >
        Off
      </button>

      <div className="flex items-center gap-1">
        <span className="text-[11px] uppercase tracking-wider text-surface-500">Threads</span>
        <button
          onClick={() => setPods((n) => Math.max(0, n - 1))}
          disabled={pods <= 0}
          className="px-1.5 py-0.5 text-xs font-mono bg-surface-800 text-surface-300 rounded hover:bg-surface-700 disabled:opacity-40"
          aria-label="Fewer threads"
        >
          −
        </button>
        <span className="w-6 text-center text-xs font-mono text-surface-100">{pods}</span>
        <button
          onClick={() => setPods((n) => Math.min(MAX_PODS, n + 1))}
          disabled={pods >= MAX_PODS}
          className="px-1.5 py-0.5 text-xs font-mono bg-surface-800 text-surface-300 rounded hover:bg-surface-700 disabled:opacity-40"
          aria-label="More threads"
        >
          +
        </button>
      </div>

      <div
        className="text-[10px] font-mono text-surface-500 w-36"
        title={pods === 0 ? 'generator stopped' : `${pods} threads × ~${APPROX_OPS_PER_POD}/sec ≈ ${approxOps} ops/sec`}
      >
        {pods === 0 ? 'stopped' : `${running ? '●' : '…'} ${pods} threads ≈ ${approxOps}/s`}
      </div>
    </div>
  )
}
