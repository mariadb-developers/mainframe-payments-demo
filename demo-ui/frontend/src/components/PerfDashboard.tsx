import type { MetricsStream } from '@/hooks/useMetricsWebSocket'
import type { CpuStream } from '@/hooks/useCpuWebSocket'
import { Sparkline } from './Sparkline'

/**
 * Phase-6 performance dashboard. Three big-number panels — Throughput, GG Latency, GG CPU —
 * in a horizontal row, centered at ~75% body width with a sparkline under each. Replaces the
 * data panels / tailers / animations the audience can't read at high ops/sec (CLAUDE.md §3, §5).
 */
export function PerfDashboard({
  stream,
  cpu,
  visible,
}: {
  stream: MetricsStream
  cpu: CpuStream
  visible: boolean
}) {
  if (!visible) return null
  const { history, latest } = stream
  const tps = latest?.observed_tps ?? 0
  const latency = latest?.avg_latency_ms ?? 0
  const rwRatio = latest?.r_w_ratio
  const rwSubtitle =
    rwRatio && /^\d+:\d+$/.test(rwRatio)
      ? `≈ ${rwRatio.replace(':', ' : ')} reads / writes`
      : 'mixed workload'

  return (
    <div className="flex justify-center items-center w-full py-6">
      <div className="grid grid-cols-3 gap-5 w-[75%] max-w-6xl">
        <Stat
          label="Throughput"
          value={tps < 10 ? tps.toFixed(1) : Math.round(tps).toLocaleString()}
          unit="ops / sec"
          values={history.map((p) => p.tps)}
          stroke="#10b981"
        />
        <Stat
          label="GG Latency"
          value={latency.toFixed(1)}
          unit="ms"
          subtitle={rwSubtitle}
          values={history.map((p) => p.latencyMs)}
          stroke="#3b82f6"
        />
        <Stat
          label="GG CPU"
          value={cpu.cpuPercent.toFixed(0)}
          unit="%"
          subtitle="avg both nodes"
          values={cpu.history}
          stroke="#f59e0b"
          max={100}
        />
      </div>
    </div>
  )
}

function Stat({
  label,
  value,
  unit,
  subtitle,
  values,
  stroke,
  max,
}: {
  label: string
  value: string
  unit: string
  subtitle?: string
  values: number[]
  stroke: string
  max?: number
}) {
  const inlineUnit = unit === 'ms' || unit === '%'
  return (
    <div className="text-center border border-surface-700 rounded-lg bg-surface-900/90 px-5 py-6">
      <div className="text-[11px] uppercase tracking-[.1em] text-surface-400 font-mono">{label}</div>
      <div className="my-2 leading-none">
        <span className="text-5xl font-semibold tabular-nums text-surface-100">{value}</span>
        {inlineUnit && <span className="ml-1 text-2xl text-surface-300">{unit}</span>}
      </div>
      {!inlineUnit && <div className="text-xs text-surface-400 mt-1">{unit}</div>}
      {subtitle && <div className="text-xs text-surface-400 mt-1">{subtitle}</div>}
      <div className="mt-4">
        <Sparkline values={values} stroke={stroke} max={max} />
      </div>
    </div>
  )
}
