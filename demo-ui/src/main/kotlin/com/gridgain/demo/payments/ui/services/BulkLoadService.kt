package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.BulkLoadResult
import org.slf4j.LoggerFactory

/**
 * The phase-2 bring-online beat (CLAUDE.md §2), split into two deliberate steps so the snapshot
 * is genuinely point-in-time: [bulkDump] captures the mainframe-proxy ([MainframeProxyService])
 * snapshot and HOLDS it; [bulkLoad] writes that held snapshot into GG ([GridGainService]). The
 * gap between them is the demo's whole point — the presenter fires a mainframe transaction after
 * the dump, so the loaded snapshot misses it; the paused cdc-sink has buffered it, and Unpause
 * drains it into GG, reconciling (zero loss). The cdc-sink stays paused across both steps so the
 * load doesn't race the live feed.
 */
class BulkLoadService(
    private val mainframeService: MainframeProxyService,
    private val gridGainService: GridGainService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Snapshot captured by bulkDump, applied by bulkLoad. Null until a dump runs; @Volatile
    // because dump and load arrive on different Ktor request threads.
    @Volatile private var dumped: PaymentsSnapshot? = null

    /** Capture the mainframe snapshot and hold it. GG is untouched — the load is a separate step. */
    fun bulkDump(): BulkLoadResult {
        val snapshot = mainframeService.readSnapshot()
        dumped = snapshot
        log.info("Bulk-dumped mainframe snapshot (held for load): {}", snapshot.counts())
        return BulkLoadResult(tablesLoaded = snapshot.counts())
    }

    /** Write the previously dumped snapshot into GG. Errors if no dump was taken. */
    fun bulkLoad(): BulkLoadResult {
        val snapshot = dumped ?: throw IllegalStateException(
            "No mainframe snapshot has been dumped yet. Click 'Bulk Dump' to capture the snapshot first, " +
            "then 'Bulk Load' to apply it into GridGain.",
        )
        val loaded = gridGainService.bulkLoad(snapshot)
        log.info("Bulk-loaded held mainframe snapshot into GG: {}", loaded)
        return BulkLoadResult(tablesLoaded = loaded)
    }

    /** Discard any held snapshot — called on demo reset so a fresh beat starts clean. */
    fun clear() {
        dumped = null
    }
}
