import { useState } from 'react'
import { demoApi } from '@/api/client'
import type { ResetSummary } from '@/types/api'

interface Props {
  onReset: () => void
}

/**
 * Header-mounted "reset the demo" control. Two-stage: a small button opens a
 * confirmation modal so an accidental click can't blow away demo state mid-
 * presentation. On confirm, POSTs /api/demo/reset and surfaces the per-step
 * result before closing.
 */
export function ResetButton({ onReset }: Props) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [summary, setSummary] = useState<ResetSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async () => {
    setBusy(true)
    setError(null)
    setSummary(null)
    try {
      const result = await demoApi.reset()
      setSummary(result)
      onReset()
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const close = () => {
    setOpen(false)
    setSummary(null)
    setError(null)
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="text-xs uppercase tracking-wider px-3 py-1.5 rounded border border-amber-600/50 text-amber-300 hover:bg-amber-600/15 transition-colors"
        title="Truncate + reseed all three stores, stop generator, return to phase 0"
      >
        Reset demo
      </button>

      {open && (
        <div
          className="fixed inset-0 bg-black/60 flex items-center justify-center z-50"
          onClick={busy ? undefined : close}
        >
          <div
            className="bg-surface-800 border border-surface-600 rounded-lg p-5 max-w-md w-full mx-4 shadow-card-hover"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 className="font-display text-lg font-semibold text-surface-100 mb-2">Reset the demo?</h2>
            <p className="text-sm text-surface-300 mb-4">
              This will <strong>truncate Mainframe, MariaDB, and GridGain</strong>, re-seed the curated demo
              data, stop the load generator, and return the phase control to 0. Any GG-side transactions
              the presenter created will be lost; any mainframe-side history beyond the original 7 curated
              transactions will also be cleared.
            </p>

            {summary && (
              <div className="bg-surface-900 border border-surface-700 rounded p-2 mb-4 font-mono text-xs">
                {summary.steps.map((s) => (
                  <div key={s.name} className="flex justify-between gap-3 py-0.5">
                    <span className="text-surface-300">{s.name}</span>
                    <span className={s.result === 'ok' ? 'text-emerald-400' : 'text-amber-300'}>{s.result}</span>
                  </div>
                ))}
              </div>
            )}

            {error && <div className="text-amber-300 text-sm font-mono mb-3">! {error}</div>}

            <div className="flex justify-end gap-2">
              <button
                onClick={close}
                disabled={busy}
                className="px-3 py-1.5 text-sm rounded text-surface-300 hover:bg-surface-700 transition-colors disabled:opacity-40"
              >
                {summary ? 'Close' : 'Cancel'}
              </button>
              {!summary && (
                <button
                  onClick={run}
                  disabled={busy}
                  className="px-3 py-1.5 text-sm rounded bg-amber-600 hover:bg-amber-500 text-white font-medium transition-colors disabled:opacity-40"
                >
                  {busy ? 'Resetting…' : 'Yes, reset'}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
