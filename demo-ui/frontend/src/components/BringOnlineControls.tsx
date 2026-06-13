interface Props {
  ggLoaded: boolean
  feedLive: boolean
  busy: boolean
  error: string | null
  onBulkLoad: () => void
  onUnpause: () => void
}

/**
 * Phase-2 "bring GridGain online" controls, mounted above the Mainframe → GG
 * event window (CLAUDE.md §2). The order is enforced by the disabled states:
 *  1. Bulk Load   — snapshot the mainframe data straight into GG (the event
 *                   feed is already paused, so Kafka is buffering meanwhile).
 *  2. Unpause     — resume the cdc-sink; buffered events (incl. any mainframe
 *                   transaction fired during the load) drain into GG. No loss.
 */
export function BringOnlineControls({ ggLoaded, feedLive, busy, error, onBulkLoad, onUnpause }: Props) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <button
          onClick={onBulkLoad}
          disabled={busy || ggLoaded}
          className="px-2.5 py-1 text-[11px] font-mono rounded bg-gg-600 text-white hover:bg-gg-500 transition-colors disabled:opacity-40 disabled:cursor-default"
          title="Snapshot the mainframe data directly into GridGain"
        >
          {ggLoaded ? '✓ Loaded' : 'Bulk Load'}
        </button>
        <button
          onClick={onUnpause}
          disabled={busy || !ggLoaded || feedLive}
          className="px-2.5 py-1 text-[11px] font-mono rounded bg-amber-600 text-white hover:bg-amber-500 transition-colors disabled:opacity-40 disabled:cursor-default"
          title="Resume the event feed so buffered mainframe events drain into GridGain"
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
