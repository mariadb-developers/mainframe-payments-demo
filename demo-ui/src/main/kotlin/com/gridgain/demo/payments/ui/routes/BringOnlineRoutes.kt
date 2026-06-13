package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.services.BulkLoadService
import com.gridgain.demo.payments.ui.services.ConnectorControlService
import com.gridgain.demo.payments.ui.services.MariaDbBulkLoadService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Control plane for the two "bring X online" beats (CLAUDE.md §2). Both groups
 * are symmetric: state/pause/resume drive the relevant Kafka Connect sink, and
 * bulk-load does a direct source→target snapshot copy. pause/resume/state answer
 * with the resulting feed state so the UI keeps its paused/live toggle in sync.
 *
 *  Phase 2 — MF→GG:    /cdc/{state,pause,resume,bulk-load}      (cdc-sink; Postgres→GG copy)
 *  Phase 5 — GG→MariaDB: /mariadb/{state,pause,resume,bulk-load} (GG→MariaDB sink; GG→MariaDB copy)
 *
 * The GG→MariaDB sink isn't deployed yet (toolkit BLOCKER); until it is, its
 * /mariadb pause/resume/state report UNKNOWN / surface a clear error, while the
 * /mariadb/bulk-load (demo-backend direct copy) already works.
 */
fun Route.bringOnlineRoutes(
    cdcSink: ConnectorControlService,
    bulkLoad: BulkLoadService,
    mariaSink: ConnectorControlService,
    mariaBulkLoad: MariaDbBulkLoadService,
) {
    route("/cdc") {
        get("/state") { call.respond(mapOf("state" to cdcSink.state().name)) }
        post("/pause") { call.respond(mapOf("state" to cdcSink.pause().name)) }
        post("/resume") { call.respond(mapOf("state" to cdcSink.resume().name)) }
        post("/bulk-load") { call.respond(bulkLoad.bulkLoad()) }
    }
    route("/mariadb-feed") {
        get("/state") { call.respond(mapOf("state" to mariaSink.state().name)) }
        post("/pause") { call.respond(mapOf("state" to mariaSink.pause().name)) }
        post("/resume") { call.respond(mapOf("state" to mariaSink.resume().name)) }
        post("/bulk-load") { call.respond(mariaBulkLoad.bulkLoad()) }
    }
}
