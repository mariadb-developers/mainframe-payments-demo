import { useEffect, useRef, useState } from 'react'
import { generatorApi } from '@/api/client'

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
  // Separate string state for the text input so partial entries ("", "2" on the way to "20") don't
  // re-render the controlled value out from under the user. The numeric `pods` is what the effect
  // and the rest of the UI read; `inputValue` is what's typed.
  const [inputValue, setInputValue] = useState('0')
  const [running, setRunning] = useState(false)
  // Last pod count actually sent to the backend — guards the debounce effect from firing on
  // hydration and from re-sending no-ops.
  const committed = useRef(0)
  const hydrated = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Keep the text input in sync when pods changes from a non-typing source (hydrate, Off button,
  // reset). Skipped when the input matches numerically already so the user's mid-typing string
  // ("02" with a leading zero on the way to "020") isn't snapped to its canonical form.
  useEffect(() => {
    if (Number.parseInt(inputValue, 10) !== pods) setInputValue(String(pods))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pods])

  // Hydrate on mount AND whenever a reset bumps resetSignal, so the displayed pod count / running
  // state always reflect the backend (post-reset: 0 pods, stopped). committed stays in sync so the
  // debounce effect sees no change and doesn't re-launch.
  useEffect(() => {
    generatorApi
      .state()
      .then((s) => {
        setPods(s.replicas)
        setInputValue(String(s.replicas))
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
      const p = Math.max(0, Math.round(pods))
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

      <div className="flex items-center gap-2">
        <span className="text-[11px] uppercase tracking-wider text-surface-500">Threads</span>
        {/* Text input with numeric inputMode — controlled <input type="number"> with a numeric
            value prop has subtle React/browser sync quirks (typed value can desync from the
            controlled value in fast typing), so we drive a string state and parse on every change.
            The 700ms debounce lets a typed value settle into a single relaunch; an empty / invalid
            input doesn't commit, but blur snaps the display back to the last valid value. */}
        <input
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          value={inputValue}
          onChange={(e) => {
            const raw = e.target.value.replace(/[^0-9]/g, '')
            setInputValue(raw)
            if (raw === '') return
            const n = Number.parseInt(raw, 10)
            if (Number.isNaN(n)) return
            setPods(Math.max(0, n))
          }}
          onBlur={() => setInputValue(String(pods))}
          onKeyDown={(e) => {
            if (e.key === 'Enter') (e.currentTarget as HTMLInputElement).blur()
          }}
          className="w-14 text-center text-xs font-mono bg-surface-800 text-surface-100 rounded border border-surface-700 px-1 py-0.5 tabular-nums focus:outline-none focus:border-gg-500"
          aria-label="Thread count"
        />
      </div>

      {/* Explicit, colored status so it's unambiguously visible — the prior subtle ●/… distinction
          was easy to miss. Stopped (grey) when pods=0; STARTING (amber) during the 700ms debounce
          + scale; RUNNING (green) once the backend confirms the launch. */}
      <div
        className={[
          'text-[10px] font-mono uppercase tracking-wider w-40',
          pods === 0 ? 'text-surface-500' : running ? 'text-emerald-400' : 'text-amber-400',
        ].join(' ')}
        title={pods === 0 ? 'generator stopped' : `${pods} threads × ~${APPROX_OPS_PER_POD}/sec ≈ ${approxOps} ops/sec`}
      >
        {pods === 0 ? 'Stopped' : `${running ? 'Running' : 'Starting…'} · ${pods} threads ≈ ${approxOps}/s`}
      </div>
    </div>
  )
}
