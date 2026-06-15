import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { TailerEvent } from '@/types/api'

export interface TailerStream {
  events: TailerEvent[]
  connected: boolean
  // Events/sec on this path (client-measured, decaying). Drives the high-rate summary switch.
  rate: number
}

// Above this per-path event rate the window stops listing individual rows and shows a flow
// animation instead — at phase-6 load the lines are an illegible blur and the graphs (top-right)
// own the quantitative story (CLAUDE.md §3/§5). Below it, the per-line feed is the whole point.
const SUMMARY_RATE_THRESHOLD = 12

/** Row styling for a window during a bring-online beat: `queued` = buffered in
 *  Kafka (bright white), `applied` = drained to the target (dimmed + struck
 *  through), `normal` = steady-state colored tokens outside any beat. */
export type AppliedState = 'normal' | 'queued' | 'applied'

/** One tailer column. `appliedState` (computed by the App from the relevant
 *  feed's pause/live state during its beat) drives the queued/applied styling;
 *  `topControls` renders the bring-online buttons above that column's window. */
export interface TailerSource {
  id: string
  label: string
  visible: boolean
  appliedState?: AppliedState
  topControls?: ReactNode
  // When true, the window is replaced with a "Not displayed under high load" placeholder
  // (CLAUDE.md §3/§5) — used for the GG→Postgres / GG→MariaDB feeds while the generator runs,
  // since those stores aren't scaled for the load run and their streams are disabled.
  suppressed?: boolean
}

/** id→name maps so the tailer can show audience-friendly text (customer/product
 *  names) instead of raw foreign keys. Sourced from the GG customer/product/
 *  balance lists (see App). Empty maps simply fall back to ids. */
export interface Lookups {
  customerByAccount: Record<string, string>
  customerById: Record<string, string>
  productById: Record<string, string>
}

const EMPTY_LOOKUPS: Lookups = { customerByAccount: {}, customerById: {}, productById: {} }

/** Money columns (balance, amount, price) are integer cents. */
function money(cents: unknown): string {
  const n = Number(cents)
  if (!Number.isFinite(n)) return '?'
  return `${n < 0 ? '-' : ''}$${(Math.abs(n) / 100).toFixed(2)}`
}

/**
 * An audience-facing one-liner for an event, built from its row payload + the
 * id→name lookups. Falls back to ids where the data model carries no user-visible
 * field (e.g. an account row has customer_id, not the name — resolved via lookups
 * when GG data is loaded, else shown as the account id).
 */
function eventSummary(event: TailerEvent, lk: Lookups): string {
  const p = event.payload as Record<string, unknown>
  const s = (k: string): string | undefined => (p[k] != null ? String(p[k]) : undefined)
  switch (event.table) {
    case 'customer':
      return s('first_name') ?? `customer ${s('customer_id') ?? '?'}`
    case 'product': {
      const name = s('name')
      return name ? `${name} · ${money(p.price)}` : `product ${s('product_id') ?? '?'}`
    }
    case 'account': {
      const cid = s('customer_id')
      const who = (cid && lk.customerById[cid]) || `account ${s('account_id') ?? '?'}`
      return `${who} · balance ${money(p.balance)}`
    }
    case 'transaction': {
      const aid = s('account_id')
      const pid = s('product_id')
      const who = (aid && lk.customerByAccount[aid]) || `account ${aid ?? '?'}`
      const product = pid && lk.productById[pid] ? ` · ${lk.productById[pid]}` : ''
      const type = s('type') ?? ''
      return `${who} · ${type} ${money(p.amount)}${product}`.replace(/\s+/g, ' ').trim()
    }
    default:
      return `key=${event.key}`
  }
}

interface Props {
  sources: TailerSource[]
  streams: Record<string, TailerStream>
  highlightedCorrelationId: string | null
  // id→name maps for audience-friendly event text; defaults to empty (ids only).
  lookups?: Lookups
}

export function ConnectorTailers({
  sources,
  streams,
  highlightedCorrelationId,
  lookups = EMPTY_LOOKUPS,
}: Props) {
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
            suppressed={s.suppressed ?? false}
            stream={streams[s.id]}
            highlightedCorrelationId={highlightedCorrelationId}
            appliedState={s.appliedState ?? 'normal'}
            lookups={lookups}
          />
        ))}
      </div>
    </div>
  )
}

function SingleTailer({
  label,
  visible,
  suppressed,
  stream,
  highlightedCorrelationId,
  appliedState,
  lookups,
}: {
  label: string
  visible: boolean
  suppressed: boolean
  stream: TailerStream | undefined
  highlightedCorrelationId: string | null
  appliedState: AppliedState
  lookups: Lookups
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  const events = stream?.events ?? []
  const connected = stream?.connected ?? false
  const rate = stream?.rate ?? 0
  // Never summarize during a bring-online beat — that's a deliberate low-volume moment whose
  // per-line detail (queued→applied) is the point.
  const summaryMode = appliedState === 'normal' && rate > SUMMARY_RATE_THRESHOLD

  // Keep the newest line in view so the window reads as a live, scrolling feed.
  useEffect(() => {
    const el = scrollRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [events.length])

  if (!visible) {
    return <div className="bg-surface-900/50 border border-dashed border-surface-700/40 rounded h-24"></div>
  }

  // Under high load the GG→Postgres / GG→MariaDB windows are intentionally hidden: those
  // stores aren't scaled for the load run and their streams are disabled (CLAUDE.md §3/§5).
  if (suppressed) {
    return (
      <div className="bg-surface-900/50 border border-dashed border-surface-700/40 rounded h-24 flex items-center justify-center px-3">
        <div className="text-[11px] font-mono uppercase tracking-wider text-surface-500 text-center">
          Not displayed under high load
        </div>
      </div>
    )
  }

  return (
    <div className="bg-surface-900 border border-surface-700 rounded h-24 flex flex-col overflow-hidden">
      <div className="flex items-center justify-between bg-surface-800 px-2 py-1">
        <div className="text-xs font-mono uppercase tracking-wider text-surface-300">{label}</div>
        <FeedBadge appliedState={appliedState} connected={connected} summaryMode={summaryMode} />
      </div>
      {summaryMode ? (
        <FlowSummary />
      ) : (
        <div ref={scrollRef} className="flex-1 overflow-auto font-mono text-[11px] leading-tight p-1">
          {events.length === 0 && <div className="text-surface-600 px-1">(no events)</div>}
          {events.slice(-40).map((e, i) => (
            <TailerRow
              key={`${e.timestamp}-${e.key}-${i}`}
              event={e}
              highlighted={!!e.correlation_id && e.correlation_id === highlightedCorrelationId}
              appliedState={appliedState}
              lookups={lookups}
            />
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * High-rate stand-in for the per-line feed: a continuously flowing dashed line that signals
 * "this path is actively streaming" without the browser rendering a row per event. The
 * quantitative rate lives in the top-right graphs, so this deliberately shows no number.
 */
function FlowSummary() {
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-1.5 px-3">
      <svg width="100%" height="16" viewBox="0 0 240 16" preserveAspectRatio="none" aria-hidden="true">
        <line
          x1="0"
          y1="8"
          x2="240"
          y2="8"
          stroke="#36adf8"
          strokeWidth="2"
          strokeDasharray="6 6"
          className="animate-flow-dash"
        />
      </svg>
      <div className="text-[10px] font-mono uppercase tracking-wider text-surface-500">streaming</div>
    </div>
  )
}

/** During the beat the Mainframe→GG window shows whether events are buffering
 *  (paused) or being applied; otherwise the normal live/idle tap indicator. */
function FeedBadge({
  appliedState,
  connected,
  summaryMode,
}: {
  appliedState: AppliedState
  connected: boolean
  summaryMode: boolean
}) {
  if (appliedState === 'queued') {
    return <div className="text-[10px] font-mono text-amber-300">⏸ buffering</div>
  }
  if (appliedState === 'applied') {
    return <div className="text-[10px] font-mono text-emerald-400">● applying</div>
  }
  if (summaryMode) {
    return <div className="text-[10px] font-mono text-gg-400">≈ high volume</div>
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
  lookups,
}: {
  event: TailerEvent
  highlighted: boolean
  appliedState: AppliedState
  lookups: Lookups
}) {
  const ts = event.timestamp.slice(11, 23) // HH:mm:ss.SSS substring of ISO 8601
  // Audience-friendly text: customer/product names + dollar amounts pulled from
  // the row payload (+ id→name lookups), instead of raw "table key=<id>".
  const summary = eventSummary(event, lookups)
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
      <span className={colored ? 'text-surface-100' : ''}>{summary}</span>
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
