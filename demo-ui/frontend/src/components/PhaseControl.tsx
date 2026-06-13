import { phaseApi } from '@/api/client'

interface Props {
  phase: number
  onChange: (phase: number) => void
}

const PHASE_LABELS = [
  '0 · Pre-show',
  '1 · Mainframe only',
  '2 · Reveal GridGain',
  '3 · GG-side transaction',
  '4 · Mainframe-side transaction',
  '5 · Reveal MariaDB',
  '6 · Load generator',
]

export function PhaseControl({ phase, onChange }: Props) {
  const advance = async (target: number) => {
    if (target < phase) return
    try {
      const next = await phaseApi.advance(target)
      onChange(next.phase)
    } catch (e) {
      console.warn('Phase advance failed', e)
    }
  }

  return (
    <div className="flex items-center gap-3 text-surface-200">
      <div className="text-sm uppercase tracking-wider text-surface-400">Phase</div>
      <div className="flex items-center gap-1">
        {PHASE_LABELS.map((label, idx) => (
          <button
            key={idx}
            onClick={() => advance(idx)}
            disabled={idx < phase}
            className={[
              'px-3 py-1.5 text-xs font-mono rounded transition-colors',
              idx === phase
                ? 'bg-gg-500 text-white shadow'
                : idx < phase
                  ? 'bg-surface-800 text-surface-500 cursor-default'
                  : 'bg-surface-700 text-surface-200 hover:bg-surface-600',
            ].join(' ')}
            title={label}
          >
            {idx}
          </button>
        ))}
      </div>
      <div className="text-sm font-mono text-surface-300 ml-2">{PHASE_LABELS[phase]}</div>
    </div>
  )
}
