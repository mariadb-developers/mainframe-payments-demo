package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.BulkLoadResult
import org.slf4j.LoggerFactory

/**
 * The phase-5 "bring MariaDB online" beat (CLAUDE.md §2), split into [bulkDump] (capture + hold
 * the GG snapshot) and [bulkLoad] (write the held snapshot into MariaDB). Mirrors [BulkLoadService]
 * (Postgres→GG): the presenter fires transactions in the gap between dump and load, so the loaded
 * snapshot is point-in-time and the GG→MariaDB feed reconciles the rest on Unpause. The GG→MariaDB
 * sink stays paused across both steps so the load doesn't race the live feed.
 */
class MariaDbBulkLoadService(
    private val gridGainService: GridGainService,
    private val mariaDbService: MariaDbService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var dumped: PaymentsSnapshot? = null

    /** Capture the GG snapshot and hold it. MariaDB is untouched — the load is a separate step. */
    fun bulkDump(): BulkLoadResult {
        val snapshot = gridGainService.readSnapshot()
        dumped = snapshot
        log.info("Bulk-dumped GG snapshot (held for load): {}", snapshot.counts())
        return BulkLoadResult(tablesLoaded = snapshot.counts())
    }

    /** Write the previously dumped snapshot into MariaDB. Errors if no dump was taken. */
    fun bulkLoad(): BulkLoadResult {
        val snapshot = dumped ?: throw IllegalStateException(
            "No GridGain snapshot has been dumped yet. Click 'Bulk Dump' to capture the snapshot first, " +
            "then 'Bulk Load' to apply it into MariaDB.",
        )
        val loaded = mariaDbService.bulkLoad(snapshot)
        log.info("Bulk-loaded held GG snapshot into MariaDB: {}", loaded)
        return BulkLoadResult(tablesLoaded = loaded)
    }

    /** Discard any held snapshot — called on demo reset so a fresh beat starts clean. */
    fun clear() {
        dumped = null
    }
}
