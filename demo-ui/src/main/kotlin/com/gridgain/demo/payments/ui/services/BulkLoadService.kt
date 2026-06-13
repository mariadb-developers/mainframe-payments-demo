package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.BulkLoadResult
import org.slf4j.LoggerFactory

/**
 * Loads the current mainframe-proxy snapshot into GG in one shot — the "Bulk
 * Load" action of the phase-2 bring-online beat (CLAUDE.md §2). Reads from
 * Postgres ([MainframeProxyService]) and writes to GG ([GridGainService]); the
 * cdc-sink is expected to be paused for the duration so the load doesn't race
 * the live event feed.
 */
class BulkLoadService(
    private val mainframeService: MainframeProxyService,
    private val gridGainService: GridGainService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun bulkLoad(): BulkLoadResult {
        val snapshot = mainframeService.readSnapshot()
        val loaded = gridGainService.bulkLoad(snapshot)
        log.info("Bulk-loaded mainframe snapshot into GG: {}", loaded)
        return BulkLoadResult(tablesLoaded = loaded)
    }
}
