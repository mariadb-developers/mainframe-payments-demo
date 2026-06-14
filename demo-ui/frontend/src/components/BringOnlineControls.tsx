interface Props {
  dumped: boolean
  loaded: boolean
  feedLive: boolean
  busy: boolean
  error: string | null
  onDump: () => void
  onLoad: () => void
  onUnpause: () => void
}

/**
 * Bring-online controls, mounted above a beat's event window (CLAUDE.md §2). The dump and load
 * are deliberately separate so the snapshot is genuinely point-in-time — the order is enforced by
 * the disabled states:
 *  1. Bulk Dump   — capture the source snapshot and hold it (the target is untouched; the event
 *                   feed is already paused, so Kafka buffers meanwhile).
 *  2. (presenter fires a transaction here — it queues in the window, NOT in the dump)
 *  3. Bulk Load   — apply the HELD snapshot to the target. It's now stale: it misses the
 *                   transaction fired in the gap.
 *  4. Unpause     — resume the feed; the buffered transaction drains in and reconciles. No loss.
 */
export function BringOnlineControls({ dumped, loaded, feedLive, busy, error, onDump, onLoad, onUnpause }: Props) {
  const btn = 'px-2.5 py-1 text-[11px] font-mono rounded text-white transition-colors disabled:opacity-40 disabled:cursor-default'
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <button
          onClick={onDump}
          disabled={busy || dumped}
          className={`${btn} bg-gg-700 hover:bg-gg-600`}
          title="Capture a point-in-time snapshot of the source and hold it (target unchanged)"
        >
          {dumped ? '✓ Dumped' : 'Bulk Dump'}
        </button>
        <button
          onClick={onLoad}
          disabled={busy || !dumped || loaded}
          className={`${btn} bg-gg-600 hover:bg-gg-500`}
          title="Apply the held snapshot to the target — stale by now if a transaction was fired in the gap"
        >
          {loaded ? '✓ Loaded' : 'Bulk Load'}
        </button>
        <button
          onClick={onUnpause}
          disabled={busy || !loaded || feedLive}
          className={`${btn} bg-amber-600 hover:bg-amber-500`}
          title="Resume the event feed so buffered events drain in and reconcile what the snapshot missed"
        >
          {feedLive ? '✓ Feed live' : 'Unpause Event Feed'}
        </button>
      </div>
      {error && (
        <div className="text-[10px] font-mono text-rose-400 max-w-[22rem] break-words" title={error}>
          ⚠ {error}
        </div>
      )}
    </div>
  )
}
