import { useCallback, useEffect, useState } from 'react'
import { cdcApi, gridGainApi, mainframeApi, mariaDbApi, phaseApi } from '@/api/client'
import { BringOnlineControls } from '@/components/BringOnlineControls'
import { ConnectorTailers, useLatestCorrelationId } from '@/components/ConnectorTailers'
import type { AppliedState, Lookups } from '@/components/ConnectorTailers'
import { GridGainPanel } from '@/components/GridGainPanel'
import { LoadSlider } from '@/components/LoadSlider'
import { MainframePanel } from '@/components/MainframePanel'
import { MariaDbPanel } from '@/components/MariaDbPanel'
import { MetricsPanel } from '@/components/MetricsPanel'
import { PhaseControl } from '@/components/PhaseControl'
import { ResetButton } from '@/components/ResetButton'
import { useMetricsWebSocket } from '@/hooks/useMetricsWebSocket'
import { useTailerWebSocket } from '@/hooks/useTailerWebSocket'
import { useThrottledBump } from '@/hooks/useThrottledBump'
import type { TransactionResult } from '@/types/api'

/**
 * Per CLAUDE.md §3 visibility table — what's revealed at each phase.
 * Phase 0 hides everything but the header; the demo opens at phase 1 in v1.
 * Phases 5 and 6 are swapped vs. the original draft: MariaDB is revealed (and
 * brought online, §2) BEFORE the load generator runs.
 */
function visibility(phase: number) {
  return {
    mainframePanel: phase >= 1,
    ggPanel: phase >= 2,
    cdcTailer: phase >= 2,
    ggToPostgresTailer: phase >= 3,
    ggToMariaTailer: phase >= 5,
    mariaPanel: phase >= 5,
    loadSlider: phase >= 6,
  }
}

export default function App() {
  const [phase, setPhase] = useState(0)
  const [userExecuteCount, setUserExecuteCount] = useState(0)
  const [highlightedCorrelationId, setHighlightedCorrelationId] = useLatestCorrelationId()

  // Phase-2 "bring GridGain online" beat (CLAUDE.md §2). feedLive mirrors the
  // cdc-sink's paused/running state; ggLoaded tracks whether the presenter has
  // bulk-loaded GG yet. ggRefreshTick lets the unpause handler nudge the GG
  // panel to re-fetch once the backlog has drained (the proof transaction
  // arrived before unpause, so no fresh cdc event ticks the panel afterward).
  const [feedLive, setFeedLive] = useState(false)
  const [ggDumped, setGgDumped] = useState(false)
  const [ggLoaded, setGgLoaded] = useState(false)
  const [bringOnlineBusy, setBringOnlineBusy] = useState(false)
  const [bringOnlineError, setBringOnlineError] = useState<string | null>(null)
  const [ggRefreshTick, setGgRefreshTick] = useState(0)

  // Phase-5 "bring MariaDB online" beat (CLAUDE.md §2) — mirrors the MF→GG state
  // above, for the GG→MariaDB sink. The Unpause half is gated on the toolkit
  // deploying that sink; the Bulk Load half (GG→MariaDB direct) works now.
  const [mariaFeedLive, setMariaFeedLive] = useState(false)
  const [mariaDumped, setMariaDumped] = useState(false)
  const [mariaLoaded, setMariaLoaded] = useState(false)
  const [mariaBusy, setMariaBusy] = useState(false)
  const [mariaError, setMariaError] = useState<string | null>(null)
  const [mariaRefreshTick, setMariaRefreshTick] = useState(0)

  // id→name maps so the connector tailers can render "Raghu · PURCHASE $1349.99"
  // instead of "transaction key=...". Customer names come from the mainframe proxy
  // (Postgres, seeded from the start) so the MF→GG trace shows names during the
  // dump→load gap — before GG is loaded. Product names (+ GG-side accounts) come
  // from GG once it's loaded. Names are stable, so we (re)fetch on mount, on each
  // beat, and after a Bulk Load. Empty maps just fall back to ids.
  const [lookups, setLookups] = useState<Lookups>({ customerByAccount: {}, customerById: {}, productById: {} })
  const refreshLookups = useCallback(async () => {
    const customerByAccount: Record<string, string> = {}
    const customerById: Record<string, string> = {}
    const productById: Record<string, string> = {}
    try {
      for (const b of await mainframeApi.balances()) {
        customerByAccount[b.account_id] = b.customer_name
        customerById[b.customer_id] = b.customer_name
      }
    } catch {
      /* mainframe unreachable — fall through to GG (if loaded) */
    }
    try {
      const [balances, products] = await Promise.all([gridGainApi.balances(), gridGainApi.products()])
      for (const b of balances) {
        customerByAccount[b.account_id] = b.customer_name
        customerById[b.customer_id] = b.customer_name
      }
      for (const p of products) productById[p.product_id] = p.name
    } catch {
      /* GG empty/unreachable — keep the mainframe-derived customer names */
    }
    setLookups({ customerByAccount, customerById, productById })
  }, [])

  // Subscribe to the three tailers once at the App level. Events drive both
  // the ConnectorTailers visualization AND the panel-refresh logic below —
  // when the tailer that signals "your backing store was just written" ticks,
  // the corresponding panel re-fetches. No polling, no fixed delay; the UI
  // refreshes exactly when the data actually lands in each system.
  const cdcStream = useTailerWebSocket('cdc')
  const ggToPostgresStream = useTailerWebSocket('gg-to-postgres')
  const ggToMariadbStream = useTailerWebSocket('gg-to-mariadb')

  // Generator throughput + GridGain execution latency for the phase-6 graphs (top-right).
  const metricsStream = useMetricsWebSocket()

  useEffect(() => {
    phaseApi
      .current()
      .then((p) => setPhase(p.phase))
      .catch(() => {})
    cdcApi
      .state()
      .then((s) => setFeedLive(s.state === 'LIVE'))
      .catch(() => {})
    mariaDbApi
      .feedState()
      .then((s) => setMariaFeedLive(s.state === 'LIVE'))
      .catch(() => {})
    void refreshLookups()
  }, [refreshLookups])

  // Empty the Mainframe→GG window the instant the beat reveals it (phase 2). The
  // post-reset Postgres reseed flows through live Debezium into this window as a
  // burst of queued lines — but those rows are exactly what Bulk Load already
  // loads into GG, so on Unpause they drain as idempotent no-ops and only confuse
  // the audience. Clearing here (not on Bulk Load, which is too late) means the
  // only queued event ever shown is the in-flight transaction the presenter fires.
  useEffect(() => {
    if (phase === 2) cdcStream.clear()
    if (phase === 5) ggToMariadbStream.clear()
    // Refresh name lookups when a beat starts so the trace shows customer names during the
    // dump→load gap (mainframe names are available even before GG is loaded).
    if (phase === 2 || phase === 5) void refreshLookups()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase])

  const v = visibility(phase)
  const beatActive = phase === 2
  const mariaBeatActive = phase === 5
  // Two-tone styling for a beat window: queued while paused, applied once live.
  const beatState = (active: boolean, live: boolean): AppliedState =>
    !active ? 'normal' : live ? 'applied' : 'queued'
  const onExecuted = (result: TransactionResult) => {
    setHighlightedCorrelationId(result.correlation_id)
    setUserExecuteCount((n) => n + 1)
  }
  const onReset = () => {
    // Backend bounces the data planes back to seed, re-pauses the event feed,
    // AND moves the phase to 0; mirror that here. Bumping userExecuteCount kicks
    // every panel's reloadKey so the curated fresh state shows immediately.
    setPhase(0)
    setUserExecuteCount((n) => n + 1)
    setFeedLive(false)
    setGgDumped(false)
    setGgLoaded(false)
    setBringOnlineError(null)
    setMariaFeedLive(false)
    setMariaDumped(false)
    setMariaLoaded(false)
    setMariaError(null)
    // Start the beats with clean windows — drop the reseed burst that fills Kafka
    // so only the presenter's in-flight transactions show.
    cdcStream.clear()
    ggToPostgresStream.clear()
    ggToMariadbStream.clear()
  }

  const onBulkDump = async () => {
    setBringOnlineBusy(true)
    setBringOnlineError(null)
    try {
      await cdcApi.bulkDump()
      setGgDumped(true)
    } catch (e) {
      setBringOnlineError(`Bulk Dump failed — ${(e as Error).message}`)
    } finally {
      setBringOnlineBusy(false)
    }
  }

  const onBulkLoad = async () => {
    setBringOnlineBusy(true)
    setBringOnlineError(null)
    try {
      await cdcApi.bulkLoad()
      setGgLoaded(true)
      setGgRefreshTick((n) => n + 1)
      void refreshLookups() // GG now has the customers/products — resolve names in the tailers
    } catch (e) {
      // Surface the failure on-screen, not just the console — a silent failure
      // here reads as "the button does nothing" (CLAUDE.md §2 beat).
      setBringOnlineError(`Bulk Load failed — ${(e as Error).message}`)
      // The held dump may have been cleared out-of-band (a reset elsewhere, or a backend
      // restart). Re-enable Bulk Dump so the presenter can re-capture instead of being stuck.
      setGgDumped(false)
    } finally {
      setBringOnlineBusy(false)
    }
  }

  const onUnpause = async () => {
    setBringOnlineBusy(true)
    setBringOnlineError(null)
    try {
      await cdcApi.resume()
      setFeedLive(true)
      // The sink drains the buffered backlog asynchronously; nudge the GG panel
      // to re-fetch a few times until the applied balances land.
      setGgRefreshTick((n) => n + 1)
      window.setTimeout(() => setGgRefreshTick((n) => n + 1), 1500)
      window.setTimeout(() => setGgRefreshTick((n) => n + 1), 3500)
    } catch (e) {
      setBringOnlineError(`Unpause Event Feed failed — ${(e as Error).message}`)
    } finally {
      setBringOnlineBusy(false)
    }
  }

  // Phase-5 MariaDB beat — mirrors onBulkDump/onBulkLoad/onUnpause for the GG→MariaDB feed.
  const onMariaBulkDump = async () => {
    setMariaBusy(true)
    setMariaError(null)
    try {
      await mariaDbApi.bulkDump()
      setMariaDumped(true)
    } catch (e) {
      setMariaError(`Bulk Dump failed — ${(e as Error).message}`)
    } finally {
      setMariaBusy(false)
    }
  }

  const onMariaBulkLoad = async () => {
    setMariaBusy(true)
    setMariaError(null)
    try {
      await mariaDbApi.bulkLoad()
      setMariaLoaded(true)
      setMariaRefreshTick((n) => n + 1)
    } catch (e) {
      setMariaError(`Bulk Load failed — ${(e as Error).message}`)
      setMariaDumped(false) // re-enable Bulk Dump if the held dump was cleared out-of-band
    } finally {
      setMariaBusy(false)
    }
  }

  const onMariaUnpause = async () => {
    setMariaBusy(true)
    setMariaError(null)
    try {
      await mariaDbApi.resume()
      setMariaFeedLive(true)
      setMariaRefreshTick((n) => n + 1)
      window.setTimeout(() => setMariaRefreshTick((n) => n + 1), 1500)
      window.setTimeout(() => setMariaRefreshTick((n) => n + 1), 3500)
    } catch (e) {
      // Until the toolkit deploys the GG→MariaDB sink this 404s — surfaced on-screen.
      setMariaError(`Unpause Event Feed failed — ${(e as Error).message}`)
    } finally {
      setMariaBusy(false)
    }
  }

  // MainframePanel reads Postgres; refresh when GG→Postgres CDC writes land there.
  // GridGainPanel reads GG; refresh when the mainframe→GG CDC writes land there,
  // plus an explicit tick after Bulk Load / Unpause (see ggRefreshTick above).
  // MariaDbPanel runs queries on user click — refresh on every gg-to-mariadb event,
  // plus an explicit tick after the phase-5 Bulk Load / Unpause (mariaRefreshTick).
  //
  // The event-count signal is throttled (leading+trailing, ~800ms): at low rate each landed
  // event refreshes the panel immediately (phases 3/4 stay snappy); at phase-6 load the flood
  // of events coalesces into ~1 refresh/sec rather than a backend re-query per event.
  const mainframeBump = useThrottledBump(ggToPostgresStream.events.length, 800)
  const ggBump = useThrottledBump(cdcStream.events.length, 800)
  const mariaBump = useThrottledBump(ggToMariadbStream.events.length, 800)
  const mainframeReloadKey = userExecuteCount + mainframeBump
  const ggReloadKey = userExecuteCount + ggBump + ggRefreshTick
  const mariaReloadKey = userExecuteCount + mariaBump + mariaRefreshTick

  return (
    <div className="h-full grid grid-rows-[auto_1fr] bg-surface-950">
      {/* Header */}
      <header className="flex items-center justify-between px-5 py-3 bg-surface-900 border-b border-surface-700">
        <div className="flex items-baseline gap-3">
          <h1 className="font-display text-lg font-semibold text-surface-100">Mainframe Payments Demo</h1>
          <span className="text-xs uppercase tracking-wider text-surface-500">MariaDB · GridGain · Mainframe</span>
        </div>
        <div className="flex items-center gap-6">
          <LoadSlider enabled={v.loadSlider} />
          <PhaseControl phase={phase} onChange={setPhase} />
          <ResetButton onReset={onReset} />
        </div>
      </header>

      {/* Main grid: GG (top, centered) / Tailers row / Mainframe + MariaDB row.
          The three panels are sized and pushed apart so they read as a separated
          triangle — GG top-center, Mainframe bottom-left, MariaDB bottom-right. */}
      <main className="relative grid grid-rows-[1fr_auto_1fr] gap-3 p-3 min-h-0">
        {/* Live load graphs, top-right of the canvas. Phase 6 only (when the generator runs);
            sits in the empty space right of the centered GG panel. */}
        <div className="absolute top-3 right-3 z-10">
          <MetricsPanel stream={metricsStream} visible={v.loadSlider} />
        </div>

        {/* GridGain — centered, same width as the (narrowed) MariaDB panel. */}
        <div className="flex justify-center min-h-0">
          <div className={['relative min-h-0 h-full basis-[40%] shrink-0', v.ggPanel ? '' : 'invisible'].join(' ')}>
            <GridGainPanel onExecuted={onExecuted} reloadKey={ggReloadKey} />
          </div>
        </div>

        <ConnectorTailers
          highlightedCorrelationId={highlightedCorrelationId}
          lookups={lookups}
          sources={[
            {
              id: 'cdc',
              label: 'Mainframe → GG',
              visible: v.cdcTailer,
              appliedState: beatState(beatActive, feedLive),
              topControls: beatActive ? (
                <BringOnlineControls
                  dumped={ggDumped}
                  loaded={ggLoaded}
                  feedLive={feedLive}
                  busy={bringOnlineBusy}
                  error={bringOnlineError}
                  onDump={onBulkDump}
                  onLoad={onBulkLoad}
                  onUnpause={onUnpause}
                />
              ) : undefined,
            },
            { id: 'gg-to-postgres', label: 'GG → Mainframe', visible: v.ggToPostgresTailer },
            {
              id: 'gg-to-mariadb',
              label: 'GG → MariaDB',
              visible: v.ggToMariaTailer,
              appliedState: beatState(mariaBeatActive, mariaFeedLive),
              topControls: mariaBeatActive ? (
                <BringOnlineControls
                  dumped={mariaDumped}
                  loaded={mariaLoaded}
                  feedLive={mariaFeedLive}
                  busy={mariaBusy}
                  error={mariaError}
                  onDump={onMariaBulkDump}
                  onLoad={onMariaBulkLoad}
                  onUnpause={onMariaUnpause}
                />
              ) : undefined,
            },
          ]}
          streams={{
            'gg-to-postgres': ggToPostgresStream,
            'cdc': cdcStream,
            'gg-to-mariadb': ggToMariadbStream,
          }}
        />

        {/* Bottom row — Mainframe pushed left, MariaDB (20% narrower) pushed
            right, with a wide centre gap so the panels read as separated. */}
        <div className="flex justify-between gap-3 min-h-0">
          <div className={['min-h-0 h-full basis-[44%] shrink-0', v.mainframePanel ? '' : 'invisible'].join(' ')}>
            <MainframePanel onExecuted={onExecuted} reloadKey={mainframeReloadKey} enabled={v.mainframePanel} />
          </div>
          <div className={['min-h-0 h-full basis-[40%] shrink-0', v.mariaPanel ? '' : 'invisible'].join(' ')}>
            <MariaDbPanel reloadKey={mariaReloadKey} />
          </div>
        </div>
      </main>
    </div>
  )
}
