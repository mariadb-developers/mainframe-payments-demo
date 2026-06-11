import { useEffect, useState } from 'react'
import { generatorApi } from '@/api/client'
import type { GeneratorRate } from '@/types/api'

const STEPS: { rate: GeneratorRate; label: string }[] = [
  { rate: 'OFF', label: 'Off' },
  { rate: 'SLOW', label: 'Slow' },
  { rate: 'MEDIUM', label: 'Medium' },
  { rate: 'FAST', label: 'Fast' },
]

export function LoadSlider({ enabled }: { enabled: boolean }) {
  const [rate, setRate] = useState<GeneratorRate>('OFF')

  useEffect(() => {
    generatorApi.state().then((s) => setRate(s.rate)).catch(() => {})
  }, [])

  const choose = async (next: GeneratorRate) => {
    setRate(next)
    try {
      await generatorApi.setRate(next)
    } catch (e) {
      console.warn('Generator rate change failed', e)
    }
  }

  return (
    <div className={['flex items-center gap-2', enabled ? '' : 'opacity-40 pointer-events-none'].join(' ')}>
      <div className="text-sm uppercase tracking-wider text-surface-400">Load</div>
      <div className="flex bg-surface-800 rounded overflow-hidden">
        {STEPS.map(({ rate: r, label }) => (
          <button
            key={r}
            onClick={() => choose(r)}
            className={[
              'px-3 py-1.5 text-xs font-mono transition-colors',
              rate === r ? 'bg-gg-500 text-white' : 'text-surface-300 hover:bg-surface-700',
            ].join(' ')}
          >
            {label}
          </button>
        ))}
      </div>
    </div>
  )
}
