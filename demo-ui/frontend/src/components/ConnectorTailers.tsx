import { useEffect, useState } from 'react'
import type { TailerEvent } from '@/types/api'

export interface TailerStream {
  events: TailerEvent[]
  connected: boolean
}

interface Props {
  sources: { id: string; label: string; visible: boolean }[]
  streams: Record<string, TailerStream>
  highlightedCorrelationId: string | null
}

export function ConnectorTailers({ sources, streams, highlightedCorrelationId }: Props) {
  return (
    <div className="grid gap-3" style={{ gridTemplateColumns: `repeat(${sources.length}, minmax(0, 1fr))` }}>
      {sources.map((s) => (
        <SingleTailer
          key={s.id}
          label={s.label}
          visible={s.visible}
          stream={streams[s.id]}
          highlightedCorrelationId={highlightedCorrelationId}
        />
      ))}
    </div>
  )
}

function SingleTailer({
  label,
  visible,
  stream,
  highlightedCorrelationId,
}: {
  label: string
  visible: boolean
  stream: TailerStream | undefined
  highlightedCorrelationId: string | null
}) {
  if (!visible) {
    return <div className="bg-surface-900/50 border border-dashed border-surface-700/40 rounded h-24"></div>
  }
  const events = stream?.events ?? []
  const connected = stream?.connected ?? false
  return (
    <div className="bg-surface-900 border border-surface-700 rounded h-24 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between bg-surface-800 px-2 py-1">
        <div className="text-xs font-mono uppercase tracking-wider text-surface-300">{label}</div>
        <div className={['text-[10px] font-mono', connected ? 'text-emerald-400' : 'text-surface-500'].join(' ')}>
          {connected ? '● live' : '○ idle'}
        </div>
      </div>
      <div className="flex-1 overflow-auto font-mono text-[11px] leading-tight p-1">
        {events.length === 0 && <div className="text-surface-600 px-1">(no events)</div>}
        {events.slice().reverse().slice(0, 12).map((e, i) => (
          <TailerRow key={`${e.timestamp}-${e.key}-${i}`} event={e} highlighted={!!e.correlation_id && e.correlation_id === highlightedCorrelationId} />
        ))}
      </div>
    </div>
  )
}

function TailerRow({ event, highlighted }: { event: TailerEvent; highlighted: boolean }) {
  const ts = event.timestamp.slice(11, 23) // HH:mm:ss.SSS substring of ISO 8601
  return (
    <div
      className={[
        'px-1 py-0.5 truncate',
        highlighted ? 'animate-pulse-corr text-maria-accent' : 'text-surface-300',
      ].join(' ')}
      title={JSON.stringify(event.payload)}
    >
      <span className="text-surface-500">{ts}</span>{' '}
      <span className="text-gg-400">{event.operation}</span>{' '}
      <span className="text-surface-400">{event.table}</span>{' '}
      <span className="text-surface-200">key={event.key}</span>
      {event.correlation_id && (
        <span className="text-surface-500"> [corr={event.correlation_id.slice(0, 8)}]</span>
      )}
    </div>
  )
}

/**
 * Track the most recent correlation id seen across any tailer. The App passes
 * this down to highlight cross-tailer matches per CLAUDE.md §5.
 */
export function useLatestCorrelationId(): [string | null, (id: string | null) => void] {
  const [id, setId] = useState<string | null>(null)
  useEffect(() => {
    if (!id) return
    const t = setTimeout(() => setId(null), 1500)
    return () => clearTimeout(t)
  }, [id])
  return [id, setId]
}
