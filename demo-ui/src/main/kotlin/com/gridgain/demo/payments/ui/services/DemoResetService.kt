package com.gridgain.demo.payments.ui.services

import org.slf4j.LoggerFactory

/**
 * Orchestrates "reset the whole demo" — takes the data planes back to the
 * curated opening state and the presenter UI back to phase 0, ready for the
 * phase-2 "bring GridGain online" beat (CLAUDE.md §2).
 *
 * Side-effects in order:
 * 1. Generator OFF.
 * 2. Phase → 0.
 * 3. CDC feed PAUSE — pause the cdc-sink connector so the Postgres reseed buffers
 *    in Kafka instead of refilling GG; GG starts the phase-2 beat EMPTY and the
 *    presenter brings it online (Bulk Load → Unpause). Fails-soft if Connect is
 *    unreachable (CDC then refills GG straight away — fine for a quick dev loop).
 * 4. MariaDB feed PAUSE — pause the GG→MariaDB sink so MariaDB stays EMPTY for the
 *    phase-5 beat (same pattern). Fails-soft / UNKNOWN until the toolkit deploys
 *    that sink (BLOCKER); the phase-5 bulk-load works regardless.
 * 5. GridGain: DELETE all rows.
 * 6. MariaDB analytics: TRUNCATE all rows.
 * 7. Postgres mainframe-proxy: TRUNCATE + reseed the curated opening state —
 *    5 customers + 5 zero-balance accounts + 10 products, and NO transactions
 *    (the menu lives in curated-transactions.yaml, not the table; see CLAUDE.md §10).
 *    Debezium publishes the reseed to the mainframe-to-gg.public.* topics where it
 *    waits, durably, for the feed to be unpaused.
 *
 * End state: Postgres holds the 20 curated rows ($0 balances, no transactions);
 * GG and MariaDB are empty; both event feeds are paused. GG comes online during
 * phase 2's beat and MariaDB during phase 5's — each proving no events are lost
 * across the cutover.
 *
 * Order matters: pausing the feeds BEFORE the reseed is what keeps GG/MariaDB
 * empty; clearing GG/MariaDB before the reseed avoids wiping rows the pipeline is
 * mid-flight on.
 */
class DemoResetService(
    private val mainframeService: MainframeProxyService,
    private val mariaDbService: MariaDbService,
    private val gridGainService: GridGainService,
    private val phaseService: PhaseService,
    private val generatorService: GeneratorControlService,
    private val cdcSinkControlService: ConnectorControlService,
    private val mariaSinkControlService: ConnectorControlService,
    private val bulkLoadService: BulkLoadService,
    private val mariaBulkLoadService: MariaDbBulkLoadService,
    private val connectBaseUrl: String,
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

        step("generator stop")     { generatorService.setLoad(0, 1) }
        step("phase to 0")         { phaseService.reset() }
        // Drop any snapshot held between a dump and load, so each beat re-dumps fresh.
        step("clear bulk dumps")   { bulkLoadService.clear(); mariaBulkLoadService.clear() }
        step("cdc feed pause")     { cdcSinkControlService.pause() }
        step("mariadb feed pause") { mariaSinkControlService.pause() }
        step("gridgain clear")     { gridGainService.reset() }
        step("mariadb truncate")   { mariaDbService.reset() }
        step("postgres reseed")    { mainframeService.reset() }
        // Heal any connector task that died while the cluster idled between demos (a sink's stale
        // JDBC connection dropped by the DB → task FAILED → connector RUNNING but applying nothing,
        // which silently breaks the phase-2/phase-5 "no events lost" reconciliation). Last, so it
        // doesn't race the wipes above; the two beat sinks stay paused (just no longer FAILED) and
        // are unpaused later, while the uncontrolled GG→Postgres sink is restored outright.
        step("heal connectors")    {
            val n = ConnectorControlService.restartAllFailedTasks(connectBaseUrl)
            if (n > 0) log.info("Reset healed {} failed connector task(s)", n)
        }

        return ResetSummary(steps = steps.map { (name, result) -> ResetStep(name, result) })
    }

    data class ResetStep(val name: String, val result: String)
    data class ResetSummary(val steps: List<ResetStep>)
}
