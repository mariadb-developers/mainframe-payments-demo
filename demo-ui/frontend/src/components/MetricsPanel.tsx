import type { MetricsStream } from '@/hooks/useMetricsWebSocket'

/**
 * Two compact live sparklines (top-right) for the phase-6 load test: measured transactions/sec
 * and average GridGain execution latency, both sourced from the data generator's own metrics
 * (streamed over /api/metrics ~1s). Hand-rolled SVG — no charting dependency, per CLAUDE.md §5.
 */
export function MetricsPanel({ stream, visible }: { stream: MetricsStream; visible: boolean }) {
  if (!visible) return null
  const { history, latest } = stream
  const tps = latest?.observed_tps ?? 0
  const latency = latest?.avg_latency_ms ?? 0
  return (
    <div className="w-60 bg-surface-900/90 border border-surface-700 rounded-md shadow-lg backdrop-blur px-3 py-2 space-y-2">
      <Metric
        label="Transactions / sec"
        value={tps < 10 ? tps.toFixed(1) : Math.round(tps).toString()}
        values={history.map((p) => p.tps)}
        stroke="#36adf8"
      />
      <Metric
        label="Avg GridGain latency"
        value={`${latency.toFixed(1)} ms`}
        values={history.map((p) => p.latencyMs)}
        stroke="#7dd3fc"
      />
    </div>
  )
}

function Metric({
  label,
  value,
  values,
  stroke,
}: {
  label: string
  value: string
  values: number[]
  stroke: string
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-[10px] uppercase tracking-wider text-surface-400 font-mono">{label}</span>
        <span className="text-sm font-mono font-semibold text-surface-100 tabular-nums">{value}</span>
      </div>
      <Sparkline values={values} stroke={stroke} />
    </div>
  )
}

/**
 * A baseline-zero sparkline: y is scaled from 0 to the window max, so an empty/idle stream
 * draws a flat line on the floor rather than auto-zooming noise. Renders an area fill under a
 * stroked polyline.
 */
function Sparkline({ values, stroke }: { values: number[]; stroke: string }) {
  const W = 224
  const H = 34
  const pad = 2
  const max = Math.max(1, ...values)
  const n = values.length
  const x = (i: number) => (n <= 1 ? 0 : (i / (n - 1)) * (W - pad * 2) + pad)
  const y = (v: number) => H - pad - (v / max) * (H - pad * 2)

  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const area = n > 0 ? `${line} L${x(n - 1).toFixed(1)},${H} L${x(0).toFixed(1)},${H} Z` : ''

  return (
    <svg width="100%" height={H} viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="mt-0.5 block">
      <line x1={0} y1={H - pad} x2={W} y2={H - pad} stroke="#334155" strokeWidth={1} />
      {n > 0 && <path d={area} fill={stroke} opacity={0.12} />}
      {n > 0 && <path d={line} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="round" />}
    </svg>
  )
}
