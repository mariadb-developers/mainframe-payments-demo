import { useEffect, useState } from 'react'
import { phaseApi } from '@/api/client'
import { ConnectorTailers, useLatestCorrelationId } from '@/components/ConnectorTailers'
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
  }, [])

  const v = visibility(phase)
  const onExecuted = (result: TransactionResult) => {
    setHighlightedCorrelationId(result.correlation_id)
    setUserExecuteCount((n) => n + 1)
  }
  const onReset = () => {
    // Backend bounces the data planes back to seed AND moves the phase to 0;
    // mirror that here. Bumping userExecuteCount kicks every panel's
    // reloadKey so the curated fresh state shows immediately rather than
    // waiting on the next CDC event.
    setPhase(0)
    setUserExecuteCount((n) => n + 1)
  }

  // MainframePanel reads Postgres; refresh when GG→Postgres CDC writes land there.
  // GridGainPanel reads GG; refresh when the mainframe→GG CDC writes land there.
  // MariaDbPanel runs queries on user click — refresh on every gg-to-mariadb event
  // so the row counts re-run themselves while a load run is active.
  const mainframeReloadKey = userExecuteCount + ggToPostgresStream.events.length
  const ggReloadKey = userExecuteCount + cdcStream.events.length
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
          sources={[
            { id: 'cdc', label: 'Mainframe → GG', visible: v.cdcTailer },
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
