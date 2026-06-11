import { useEffect, useState } from 'react'
import { mariaDbApi } from '@/api/client'
import type { AnalyticQueryDefinition, AnalyticQueryResult } from '@/types/api'

interface Props {
  reloadKey: number
}

export function MariaDbPanel({ reloadKey }: Props) {
  const [queries, setQueries] = useState<AnalyticQueryDefinition[]>([])
  const [selected, setSelected] = useState<string>('')
  const [result, setResult] = useState<AnalyticQueryResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    mariaDbApi
      .queries()
      .then((qs) => {
        setQueries(qs)
        if (qs.length > 0) setSelected((cur) => cur || qs[0].id)
        setError(null)
      })
      .catch((e) => setError(String(e.message ?? e)))
  }, [])

  // Re-run whichever query is selected when new gg-to-mariadb events land —
  // keeps the row counts / recent-window query live during a load run without
  // the user having to click Run.
  useEffect(() => {
    if (!selected) return
    run(selected)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadKey])

  const run = async (id?: string) => {
    const target = id ?? selected
    if (!target) return
    setBusy(true)
    setError(null)
    try {
      setResult(await mariaDbApi.run(target))
    } catch (e) {
      setError((e as Error).message)
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  const activeQuery = queries.find((q) => q.id === selected)

  return (
    <div className="relative h-full flex flex-col overflow-hidden rounded-3xl bg-gradient-to-b from-white to-teal-50/50 p-6 shadow-2xl shadow-black/40 ring-1 ring-black/5 animate-fade-in">
      {/* soft atmospheric glow */}
      <div className="pointer-events-none absolute -bottom-20 -left-16 h-52 w-52 rounded-full bg-teal-200/50 blur-3xl" />

      {/* header */}
      <div className="relative mb-4 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="grid h-10 w-10 place-items-center rounded-2xl bg-gradient-to-br from-teal-500 to-emerald-600 text-white font-display text-lg font-bold shadow-lg shadow-teal-500/30">
            M
          </span>
          <div>
            <div className="font-display text-xl font-semibold leading-none text-slate-800">MariaDB</div>
            <div className="mt-1 text-[11px] uppercase tracking-[0.18em] text-slate-400">Analytics</div>
          </div>
        </div>
        <span className="rounded-full bg-teal-100/80 px-3 py-1 text-[11px] font-medium text-teal-700">SQL</span>
      </div>

      {error && (
        <div className="relative mb-3 rounded-2xl bg-amber-50 px-3.5 py-2 text-sm text-amber-700 ring-1 ring-amber-200">
          {error}
        </div>
      )}

      {/* query pills */}
      <div className="relative mb-3 flex flex-wrap gap-2">
        {queries.map((q) => {
          const active = q.id === selected
          return (
            <button
              key={q.id}
              onClick={() => {
                setSelected(q.id)
                run(q.id)
              }}
              className={[
                'rounded-full px-3.5 py-1.5 text-xs font-medium transition',
                active
                  ? 'bg-gradient-to-r from-teal-500 to-emerald-600 text-white shadow-md shadow-teal-500/30'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200',
              ].join(' ')}
            >
              {q.label}
            </button>
          )
        })}
      </div>

      {activeQuery && <div className="relative mb-2 text-xs italic text-slate-400">{activeQuery.description}</div>}

      {/* results */}
      <div className="relative flex-1 min-h-0 overflow-auto rounded-2xl bg-slate-50/70 p-1 ring-1 ring-slate-200/70">
        {!result && !busy && (
          <div className="px-3 py-2 text-sm text-slate-400">Pick a query to run.</div>
        )}
        {busy && <div className="px-3 py-2 text-sm font-medium text-teal-600">running…</div>}
        {result && (
          <table className="w-full text-xs">
            <thead>
              <tr>
                {result.columns.map((c) => (
                  <th
                    key={c}
                    className="border-b border-slate-200 px-3 py-2 text-left font-semibold uppercase tracking-wider text-teal-700"
                  >
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.rows.length === 0 && (
                <tr>
                  <td colSpan={result.columns.length} className="px-3 py-2 italic text-slate-400">
                    (empty result set)
                  </td>
                </tr>
              )}
              {result.rows.map((row, i) => (
                <tr key={i} className="odd:bg-white/70 even:bg-transparent">
                  {row.map((cell, j) => (
                    <td key={j} className="px-3 py-1.5 font-mono text-slate-700 tabular-nums">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
