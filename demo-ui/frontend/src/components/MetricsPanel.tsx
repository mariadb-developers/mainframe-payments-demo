import type { MetricsStream } from '@/hooks/useMetricsWebSocket'
import type { CpuStream } from '@/hooks/useCpuWebSocket'
import { Sparkline } from './Sparkline'

/**
 * Compact live sparklines (top-right) for the phase-6 load test: measured transactions/sec and
 * average GridGain execution latency (from the data generator's own metrics, /api/metrics ~1s), plus
 * the GG cluster's CPU (avg sys_CpuLoad, /api/cpu ~2s). The CPU readout is the punchline — tps climbs
 * while GG stays bored. Hand-rolled SVG — no charting dependency, per CLAUDE.md §5.
 */
export function MetricsPanel({ stream, cpu, visible }: { stream: MetricsStream; cpu: CpuStream; visible: boolean }) {
  if (!visible) return null
  const { history, latest } = stream
  const tps = latest?.observed_tps ?? 0
  const latency = latest?.avg_latency_ms ?? 0
  return (
    <div className="w-[30rem] bg-surface-900/90 border border-surface-700 rounded-md shadow-lg backdrop-blur px-3 py-2 space-y-2">
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
      <Metric
        label="GG cluster CPU"
        value={`${cpu.cpuPercent.toFixed(0)}%`}
        values={cpu.history}
        stroke="#4ade80"
        max={100}
      />
    </div>
  )
}

function Metric({
  label,
  value,
  values,
  stroke,
  max,
}: {
  label: string
  value: string
  values: number[]
  stroke: string
  max?: number
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between">
        <span className="text-[10px] uppercase tracking-wider text-surface-400 font-mono">{label}</span>
        <span className="text-sm font-mono font-semibold text-surface-100 tabular-nums">{value}</span>
      </div>
      <Sparkline values={values} stroke={stroke} max={max} />
    </div>
  )
}

