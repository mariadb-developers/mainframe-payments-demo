import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { TailerEvent } from '@/types/api'

export interface TailerStream {
  events: TailerEvent[]
  connected: boolean
}

/** One tailer column. `appliedStyling` marks the Mainframe→GG window, whose rows
 *  switch between queued (white) and applied (struck-through) during the phase-2
 *  bring-online beat; `topControls` renders above that column's window. */
export interface TailerSource {
  id: string
  label: string
  visible: boolean
  appliedStyling?: boolean
  topControls?: ReactNode
}

type AppliedState = 'normal' | 'queued' | 'applied'

interface Props {
  sources: TailerSource[]
  streams: Record<string, TailerStream>
  highlightedCorrelationId: string | null
  // Phase-2 beat (CLAUDE.md §2): whether the cdc-sink is currently applying
  // events, and whether the beat's two-tone styling is active at all.
  feedLive: boolean
  beatActive: boolean
}

export function ConnectorTailers({ sources, streams, highlightedCorrelationId, feedLive, beatActive }: Props) {
  const gridCols = { gridTemplateColumns: `repeat(${sources.length}, minmax(0, 1fr))` }
  const hasControls = sources.some((s) => s.topControls)
  return (
    <div>
      {hasControls && (
        <div className="grid gap-3 mb-1.5" style={gridCols}>
          {sources.map((s) => (
            <div key={s.id} className="flex items-center min-h-[1.75rem]">
              {s.topControls ?? null}
            </div>
          ))}
        </div>
      )}
      <div className="grid gap-3" style={gridCols}>
        {sources.map((s) => (
          <SingleTailer
            key={s.id}
            label={s.label}
            visible={s.visible}
            stream={streams[s.id]}
            highlightedCorrelationId={highlightedCorrelationId}
            appliedState={appliedStateFor(s, beatActive, feedLive)}
          />
        ))}
      </div>
    </div>
  )
}

/** The row styling for a source: only the bring-online window (appliedStyling)
 *  is two-tone, and only while the beat is active; everything else is normal. */
function appliedStateFor(source: TailerSource, beatActive: boolean, feedLive: boolean): AppliedState {
  if (!source.appliedStyling || !beatActive) return 'normal'
  return feedLive ? 'applied' : 'queued'
}

function SingleTailer({
  label,
  visible,
  stream,
  highlightedCorrelationId,
  appliedState,
}: {
  label: string
  visible: boolean
  stream: TailerStream | undefined
  highlightedCorrelationId: string | null
  appliedState: AppliedState
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const events = stream?.events ?? []
  const connected = stream?.connected ?? false

  // Keep the newest line in view so the window reads as a live, scrolling feed.
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [events.length])

  if (!visible) {
    return <div className="bg-surface-900/50 border border-dashed border-surface-700/40 rounded h-24"></div>
  }

  return (
    <div className="bg-surface-900 border border-surface-700 rounded h-24 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between bg-surface-800 px-2 py-1">
        <div className="text-xs font-mono uppercase tracking-wider text-surface-300">{label}</div>
        <FeedBadge appliedState={appliedState} connected={connected} />
      </div>
      <div ref={scrollRef} className="flex-1 overflow-auto font-mono text-[11px] leading-tight p-1">
        {events.length === 0 && <div className="text-surface-600 px-1">(no events)</div>}
        {events.slice(-40).map((e, i) => (
          <TailerRow
            key={`${e.timestamp}-${e.key}-${i}`}
            event={e}
            highlighted={!!e.correlation_id && e.correlation_id === highlightedCorrelationId}
            appliedState={appliedState}
          />
        ))}
      </div>
    </div>
  )
}

/** During the beat the Mainframe→GG window shows whether events are buffering
 *  (paused) or being applied; otherwise the normal live/idle tap indicator. */
function FeedBadge({ appliedState, connected }: { appliedState: AppliedState; connected: boolean }) {
  if (appliedState === 'queued') {
    return <div className="text-[10px] font-mono text-amber-300">⏸ buffering</div>
  }
  if (appliedState === 'applied') {
    return <div className="text-[10px] font-mono text-emerald-400">● applying</div>
  }
  return (
    <div className={['text-[10px] font-mono', connected ? 'text-emerald-400' : 'text-surface-500'].join(' ')}>
      {connected ? '● live' : '○ idle'}
    </div>
  )
}

function TailerRow({
  event,
  highlighted,
  appliedState,
}: {
  event: TailerEvent
  highlighted: boolean
  appliedState: AppliedState
}) {
  const ts = event.timestamp.slice(11, 23) // HH:mm:ss.SSS substring of ISO 8601
  // queued = not yet applied to GG (bright white); applied = drained into GG
  // (dimmed + struck through). normal = the steady-state colored tokens used
  // outside the beat. During the beat the per-token colors are dropped so the
  // single white/grey tone reads clearly (otherwise the token colors win).
  const colored = appliedState === 'normal'
  const toneClass =
    appliedState === 'queued'
      ? 'text-white'
      : appliedState === 'applied'
        ? 'text-surface-500 line-through opacity-70'
        : 'text-surface-300'
  return (
    <div
      className={[
        'px-1 py-0.5 truncate transition-colors',
        highlighted ? 'animate-pulse-corr text-maria-accent' : toneClass,
      ].join(' ')}
      title={JSON.stringify(event.payload)}
    >
      <span className={colored ? 'text-surface-500' : ''}>{ts}</span>{' '}
      <span className={colored ? 'text-gg-400' : ''}>{event.operation}</span>{' '}
      <span className={colored ? 'text-surface-400' : ''}>{event.table}</span>{' '}
      <span className={colored ? 'text-surface-200' : ''}>key={event.key}</span>
      {event.correlation_id && (
        <span className={colored ? 'text-surface-500' : ''}> [corr={event.correlation_id.slice(0, 8)}]</span>
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
