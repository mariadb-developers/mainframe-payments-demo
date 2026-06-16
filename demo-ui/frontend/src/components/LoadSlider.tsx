import { useEffect, useRef, useState } from 'react'
import { generatorApi } from '@/api/client'

// Manual load control (CLAUDE.md §3/§10). The old off/slow/medium/fast presets were a
// no-op (they passed a gradle property the plugin ignored, so every press ran one pod at
// the rate pinned in ops.yaml). This control sets the TOTAL target ops/sec directly and a
// pod count — the backend splits the total across pods and (re)launches a distributed run.
// Adding pods is the real lever for saturating GG: a single pod is capped by GG round-trip
// latency, so cranking the rate alone plateaus.
const MAX_OPS = 10000
const OPS_STEP = 100
const MAX_PODS = 8
// Each change tears down and relaunches the generator, so commit only after the presenter
// settles — not on every slider tick.
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
  // Bumped by a demo reset. The slider is always mounted, so without this it would keep the
  // prior run's settings; re-hydrating from the (already-reset) backend shows 0 ops / 1 pod.
  resetSignal?: number
}) {
  const [target, setTarget] = useState(0)
  const [pods, setPods] = useState(1)
  const [running, setRunning] = useState(false)
  // Last values actually sent to the backend — guards the debounce effect from firing on
  // hydration and from re-sending no-ops.
  const committed = useRef({ target: 0, pods: 1 })
  const hydrated = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Hydrate on mount AND whenever a reset bumps resetSignal, so the displayed target/pods/running
  // always reflect the backend (post-reset: 0 ops, 1 pod, stopped). committed is kept in sync so
  // the debounce effect below sees no change and doesn't re-send a no-op setLoad.
  useEffect(() => {
    generatorApi
      .state()
      .then((s) => {
        const p = Math.max(1, s.replicas)
        setTarget(s.target_ops_per_second)
        setPods(p)
        setRunning(s.running)
        committed.current = { target: s.target_ops_per_second, pods: p }
      })
      .finally(() => {
        hydrated.current = true
      })
  }, [resetSignal])

  useEffect(() => {
    if (!hydrated.current) return
    if (target === committed.current.target && pods === committed.current.pods) return
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(async () => {
      const t = Math.max(0, Math.round(target))
      const p = Math.min(MAX_PODS, Math.max(1, Math.round(pods)))
      try {
        const s = await generatorApi.setLoad(t, p)
        committed.current = { target: t, pods: p }
        setRunning(s.running)
      } catch (e) {
        console.warn('Generator load change failed', e)
      }
    }, COMMIT_DEBOUNCE_MS)
    return () => {
      if (timer.current) clearTimeout(timer.current)
    }
  }, [target, pods])

  useEffect(() => {
    onRunningChange?.(running)
  }, [running, onRunningChange])

  const perPod = pods > 0 ? Math.ceil(target / pods) : 0

  return (
    <div className={['flex items-center gap-3', enabled ? '' : 'opacity-40 pointer-events-none'].join(' ')}>
      <div className="text-sm uppercase tracking-wider text-surface-400">Load</div>

      <button
        onClick={() => setTarget(0)}
        className={[
          'px-2 py-1 text-xs font-mono rounded transition-colors',
          target === 0 ? 'bg-gg-500 text-white' : 'bg-surface-800 text-surface-300 hover:bg-surface-700',
        ].join(' ')}
      >
        Off
      </button>

      <input
        type="range"
        min={0}
        max={MAX_OPS}
        step={OPS_STEP}
        value={Math.min(target, MAX_OPS)}
        onChange={(e) => setTarget(Number(e.target.value))}
        className="w-32 accent-gg-500"
        aria-label="Target ops per second"
      />

      <div className="flex items-center gap-1">
        <input
          type="number"
          min={0}
          value={target}
          onChange={(e) => setTarget(Math.max(0, Math.round(Number(e.target.value) || 0)))}
          className="w-20 bg-surface-800 text-surface-100 text-xs font-mono px-2 py-1 rounded border border-surface-700 focus:outline-none focus:border-gg-500"
          aria-label="Target ops per second (exact)"
        />
        <span className="text-[11px] text-surface-500 font-mono">ops/s</span>
      </div>

      <div className="flex items-center gap-1">
        <span className="text-[11px] uppercase tracking-wider text-surface-500">Pods</span>
        <button
          onClick={() => setPods((n) => Math.max(1, n - 1))}
          disabled={pods <= 1}
          className="px-1.5 py-0.5 text-xs font-mono bg-surface-800 text-surface-300 rounded hover:bg-surface-700 disabled:opacity-40"
          aria-label="Fewer pods"
        >
          −
        </button>
        <span className="w-4 text-center text-xs font-mono text-surface-100">{pods}</span>
        <button
          onClick={() => setPods((n) => Math.min(MAX_PODS, n + 1))}
          disabled={pods >= MAX_PODS}
          className="px-1.5 py-0.5 text-xs font-mono bg-surface-800 text-surface-300 rounded hover:bg-surface-700 disabled:opacity-40"
          aria-label="More pods"
        >
          +
        </button>
      </div>

      <div
        className="text-[10px] font-mono text-surface-500 w-24"
        title={target === 0 ? 'generator stopped' : `${target} ops/sec total ≈ ${pods} × ${perPod}/sec`}
      >
        {target === 0 ? 'stopped' : `${running ? '●' : '…'} ${pods}×${perPod}/s`}
      </div>
    </div>
  )
}
