package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.BulkLoadResult
import org.slf4j.LoggerFactory

/**
 * Loads the current GG snapshot into MariaDB in one shot — the "Bulk Load"
 * action of the phase-5 "bring MariaDB online" beat (CLAUDE.md §2). Reads from
 * GG ([GridGainService]) and writes to MariaDB ([MariaDbService]); the
 * GG→MariaDB sink is expected to be paused for the duration so the load doesn't
 * race the live feed. Parallels [BulkLoadService] (Postgres→GG).
 */
class MariaDbBulkLoadService(
    private val gridGainService: GridGainService,
    private val mariaDbService: MariaDbService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun bulkLoad(): BulkLoadResult {
        val snapshot = gridGainService.readSnapshot()
        val loaded = mariaDbService.bulkLoad(snapshot)
        log.info("Bulk-loaded GG snapshot into MariaDB: {}", loaded)
        return BulkLoadResult(tablesLoaded = loaded)
    }
}
