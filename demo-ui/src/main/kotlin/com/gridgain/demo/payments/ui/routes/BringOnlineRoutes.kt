package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.services.BulkLoadService
import com.gridgain.demo.payments.ui.services.CdcSinkControlService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Control plane for the phase-2 "bring GridGain online" beat (CLAUDE.md §2):
 *  - GET  /cdc/state      current feed state: PAUSED | LIVE | UNKNOWN
 *  - POST /cdc/pause      pause the cdc-sink — mainframe events buffer in Kafka
 *  - POST /cdc/resume     resume the cdc-sink — buffered backlog drains into GG
 *  - POST /cdc/bulk-load  snapshot Postgres directly into GG (the "Bulk Load" button)
 *
 * pause/resume/state all answer with the resulting feed state so the UI can
 * keep its paused/live toggle in sync.
 */
fun Route.bringOnlineRoutes(cdcSink: CdcSinkControlService, bulkLoad: BulkLoadService) {
    route("/cdc") {
        get("/state") {
            call.respond(mapOf("state" to cdcSink.state().name))
        }
        post("/pause") {
            call.respond(mapOf("state" to cdcSink.pause().name))
        }
        post("/resume") {
            call.respond(mapOf("state" to cdcSink.resume().name))
        }
        post("/bulk-load") {
            call.respond(bulkLoad.bulkLoad())
        }
    }
}
