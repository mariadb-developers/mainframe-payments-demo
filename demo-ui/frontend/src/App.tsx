import { useCallback, useEffect, useState } from 'react'
import { cdcApi, gridGainApi, phaseApi } from '@/api/client'
import { BringOnlineControls } from '@/components/BringOnlineControls'
import { ConnectorTailers, useLatestCorrelationId } from '@/components/ConnectorTailers'
import type { Lookups } from '@/components/ConnectorTailers'
import { GridGainPanel } from '@/components/GridGainPanel'
import { LoadSlider } from '@/components/LoadSlider'
import { MainframePanel } from '@/components/MainframePanel'
import { MariaDbPanel } from '@/components/MariaDbPanel'
import { PhaseControl } from '@/components/PhaseControl'
import { ResetButton } from '@/components/ResetButton'
import { useTailerWebSocket } from '@/hooks/useTailerWebSocket'
import type { TransactionResult } from '@/types/api'

/**
 * Per CLAUDE.md §3 visibility table — what's revealed at each phase.
 * Phase 0 hides everything but the header; the demo opens at phase 1 in v1.
 */
function visibility(phase: number) {
  return {
    mainframePanel: phase >= 1,
    ggPanel: phase >= 2,
    cdcTailer: phase >= 2,
    ggToPostgresTailer: phase >= 3,
    ggToMariaTailer: phase >= 6,
    mariaPanel: phase >= 6,
    loadSlider: phase >= 5,
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
  const [ggLoaded, setGgLoaded] = useState(false)
  const [bringOnlineBusy, setBringOnlineBusy] = useState(false)
  const [bringOnlineError, setBringOnlineError] = useState<string | null>(null)
  const [ggRefreshTick, setGgRefreshTick] = useState(0)

  // id→name maps so the connector tailers can render "Raghu · PURCHASE $1349.99"
  // instead of "transaction key=...". Built from the GG customer/product/balance
  // lists; names are stable across the demo, so we (re)fetch on mount and after a
  // Bulk Load (GG is empty until then). Empty maps just fall back to ids.
  const [lookups, setLookups] = useState<Lookups>({ customerByAccount: {}, customerById: {}, productById: {} })
  const refreshLookups = useCallback(async () => {
    try {
      const [balances, products] = await Promise.all([gridGainApi.balances(), gridGainApi.products()])
      const customerByAccount: Record<string, string> = {}
      const customerById: Record<string, string> = {}
      for (const b of balances) {
        customerByAccount[b.account_id] = b.customer_name
        customerById[b.customer_id] = b.customer_name
      }
      const productById: Record<string, string> = {}
      for (const p of products) productById[p.product_id] = p.name
      setLookups({ customerByAccount, customerById, productById })
    } catch {
      /* GG empty/unreachable — keep prior maps; tailers fall back to ids */
    }
  }, [])

  // Subscribe to the three tailers once at the App level. Events drive both
  // the ConnectorTailers visualization AND the panel-refresh logic below —
  // when the tailer that signals "your backing store was just written" ticks,
  // the corresponding panel re-fetches. No polling, no fixed delay; the UI
  // refreshes exactly when the data actually lands in each system.
  const cdcStream = useTailerWebSocket('cdc')
  const ggToPostgresStream = useTailerWebSocket('gg-to-postgres')
  const ggToMariadbStream = useTailerWebSocket('gg-to-mariadb')

  useEffect(() => {
    phaseApi
      .current()
      .then((p) => setPhase(p.phase))
      .catch(() => {})
    cdcApi
      .state()
      .then((s) => setFeedLive(s.state === 'LIVE'))
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase])

  const v = visibility(phase)
  const beatActive = phase === 2
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
    setGgLoaded(false)
    setBringOnlineError(null)
    // Start the beat with a clean Mainframe→GG window — drop the reseed burst
    // that fills Kafka so only the presenter's in-flight transaction shows.
    cdcStream.clear()
    ggToPostgresStream.clear()
    ggToMariadbStream.clear()
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

  // MainframePanel reads Postgres; refresh when GG→Postgres CDC writes land there.
  // GridGainPanel reads GG; refresh when the mainframe→GG CDC writes land there,
  // plus an explicit tick after Bulk Load / Unpause (see ggRefreshTick above).
  // MariaDbPanel runs queries on user click — refresh on every gg-to-mariadb event
  // so the row counts re-run themselves while a load run is active.
  const mainframeReloadKey = userExecuteCount + ggToPostgresStream.events.length
  const ggReloadKey = userExecuteCount + cdcStream.events.length + ggRefreshTick
  const mariaReloadKey = userExecuteCount + ggToMariadbStream.events.length

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
      <main className="grid grid-rows-[1fr_auto_1fr] gap-3 p-3 min-h-0">
        {/* GridGain — centered, same width as the (narrowed) MariaDB panel. */}
        <div className="flex justify-center min-h-0">
          <div className={['relative min-h-0 h-full basis-[40%] shrink-0', v.ggPanel ? '' : 'invisible'].join(' ')}>
            <GridGainPanel onExecuted={onExecuted} reloadKey={ggReloadKey} />
          </div>
        </div>

        <ConnectorTailers
          highlightedCorrelationId={highlightedCorrelationId}
          feedLive={feedLive}
          beatActive={beatActive}
          lookups={lookups}
          sources={[
            {
              id: 'cdc',
              label: 'Mainframe → GG',
              visible: v.cdcTailer,
              appliedStyling: true,
              topControls: beatActive ? (
                <BringOnlineControls
                  ggLoaded={ggLoaded}
                  feedLive={feedLive}
                  busy={bringOnlineBusy}
                  error={bringOnlineError}
                  onBulkLoad={onBulkLoad}
                  onUnpause={onUnpause}
                />
              ) : undefined,
            },
            { id: 'gg-to-postgres', label: 'GG → Mainframe', visible: v.ggToPostgresTailer },
            { id: 'gg-to-mariadb', label: 'GG → MariaDB', visible: v.ggToMariaTailer },
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
