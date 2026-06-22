/**
 * A baseline-zero sparkline: y is scaled from 0 to [max] (or the window max when [max] is omitted),
 * so a fixed-scale series like CPU% reads against its full range instead of auto-zooming noise.
 * Renders an area fill under a stroked polyline. Uses preserveAspectRatio="none" so callers can
 * stretch it to any width via the SVG's container without re-parameterising the geometry.
 */
export function Sparkline({ values, stroke, max }: { values: number[]; stroke: string; max?: number }) {
  const W = 448
  const H = 68
  const pad = 2
  const scaleMax = Math.max(1, max ?? Math.max(1, ...values))
  const n = values.length
  const x = (i: number) => (n <= 1 ? 0 : (i / (n - 1)) * (W - pad * 2) + pad)
  const y = (v: number) => H - pad - (Math.min(v, scaleMax) / scaleMax) * (H - pad * 2)

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
