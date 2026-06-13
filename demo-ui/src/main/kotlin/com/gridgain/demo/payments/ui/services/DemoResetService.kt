package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.GeneratorRate
import org.slf4j.LoggerFactory

/**
 * Orchestrates "reset the whole demo" — takes the data planes back to the
 * curated opening state and the presenter UI back to phase 0, ready for the
 * phase-2 "bring GridGain online" beat (CLAUDE.md §2).
 *
 * Side-effects in order:
 * 1. Generator OFF.
 * 2. Phase → 0.
 * 3. CDC feed PAUSE — pause the cdc-sink connector. With the sink paused, the
 *    Postgres reseed in step 6 buffers in Kafka instead of refilling GG, so GG
 *    starts the beat EMPTY. The presenter brings it online explicitly in phase 2
 *    (Bulk Load, then Unpause Event Feed). If Kafka Connect is unreachable this
 *    step fails-soft and the demo falls back to the old behaviour (CDC refills
 *    GG straight away), which is fine for a quick dev loop without the beat.
 * 4. GridGain: DELETE all rows.
 * 5. MariaDB analytics: TRUNCATE all rows.
 * 6. Postgres mainframe-proxy: TRUNCATE + reseed the curated opening state —
 *    5 customers + 5 zero-balance accounts + 10 products, and NO transactions
 *    (the menu lives in curated-transactions.yaml, not the table; see CLAUDE.md §10).
 *    Debezium publishes the reseed to the mainframe-to-gg.public.* topics where it
 *    waits, durably, for the feed to be unpaused.
 *
 * End state: Postgres holds the 20 curated rows ($0 balances, no transactions);
 * GG and MariaDB are empty; the inbound event feed is paused. GG (and, via the
 * gg-cache-publisher, MariaDB) come online during phase 2's beat — proving no
 * events are lost across the cutover.
 *
 * Order matters: pausing the feed BEFORE the reseed is what keeps GG empty;
 * clearing GG/MariaDB before the reseed avoids wiping rows the pipeline is
 * mid-flight on.
 */
class DemoResetService(
    private val mainframeService: MainframeProxyService,
    private val mariaDbService: MariaDbService,
    private val gridGainService: GridGainService,
    private val phaseService: PhaseService,
    private val generatorService: GeneratorControlService,
    private val cdcSinkControlService: CdcSinkControlService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns a brief log of which steps ran successfully and which failed.
     * Reset continues past individual failures — a failure in (say) MariaDB
     * shouldn't block resetting Postgres or vice versa, since the presenter
     * might be triggering reset to recover from a partial-failure state.
     */
    fun reset(): ResetSummary {
        val steps = mutableListOf<Pair<String, String>>()
        fun step(name: String, body: () -> Unit) {
            try {
                body()
                steps += name to "ok"
            } catch (e: Exception) {
                log.warn("Reset step '{}' failed: {}", name, e.message)
                steps += name to "failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        step("generator stop")    { generatorService.setRate(GeneratorRate.OFF) }
        step("phase to 0")        { phaseService.reset() }
        step("cdc feed pause")    { cdcSinkControlService.pause() }
        step("gridgain clear")    { gridGainService.reset() }
        step("mariadb truncate")  { mariaDbService.reset() }
        step("postgres reseed")   { mainframeService.reset() }

        return ResetSummary(steps = steps.map { (name, result) -> ResetStep(name, result) })
    }

    data class ResetStep(val name: String, val result: String)
    data class ResetSummary(val steps: List<ResetStep>)
}
