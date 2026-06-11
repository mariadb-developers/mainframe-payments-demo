package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.GeneratorRate
import org.slf4j.LoggerFactory

/**
 * Orchestrates "reset the whole demo" — takes the data planes back to the
 * curated opening state and the presenter UI back to phase 0.
 *
 * Side-effects in order:
 * 1. Generator OFF.
 * 2. Phase → 0.
 * 3. GridGain: DELETE all rows. Reseeding GG is deferred to step 5 — CDC
 *    from the Postgres reseed will refill it.
 * 4. MariaDB analytics: TRUNCATE all rows. Same deal: refilled via CDC.
 * 5. Postgres mainframe-proxy: TRUNCATE + reseed the curated opening state —
 *    5 customers + 5 zero-balance accounts + 10 products, and NO transactions
 *    (the menu lives in curated-transactions.yaml, not the table; see CLAUDE.md §10).
 *    Triggers a burst of mainframe-to-gg.public.* events that cdc-sink
 *    MERGEs into GG, and from-mf.public.* events that the gg-to-mariadb
 *    sink applies. End state: all three stores hold the same 20 curated
 *    rows, every balance is $0, and the transaction tables are empty; the
 *    test rows the presenter created are gone.
 *
 * Order matters: clearing GG/MariaDB AFTER reseeding Postgres would race the
 * inflight CDC events and leave the modern-stack stores empty (we'd wipe
 * rows that CDC had just placed).
 */
class DemoResetService(
    private val mainframeService: MainframeProxyService,
    private val mariaDbService: MariaDbService,
    private val gridGainService: GridGainService,
    private val phaseService: PhaseService,
    private val generatorService: GeneratorControlService,
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
        step("gridgain clear")    { gridGainService.reset() }
        step("mariadb truncate")  { mariaDbService.reset() }
        step("postgres reseed")   { mainframeService.reset() }

        return ResetSummary(steps = steps.map { (name, result) -> ResetStep(name, result) })
    }

    data class ResetStep(val name: String, val result: String)
    data class ResetSummary(val steps: List<ResetStep>)
}
