package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.model.SetPodsRequest
import com.gridgain.demo.payments.ui.services.GeneratorControlService
import com.gridgain.demo.payments.ui.services.GeneratorPoolService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.generatorRoutes(service: GeneratorControlService, poolService: GeneratorPoolService) {
    route("/generator") {
        get {
            call.respond(service.state())
        }
        // Pods-only load control (CLAUDE.md §3/§10): pod count (0 = off). Each pod runs at its
        // unthrottled latency ceiling, so total throughput ≈ pods × ~500 ops/sec; adding pods is
        // the lever for saturating GG. The service (re)launches the distributed run, tearing down
        // any prior run first.
        post("/pods") {
            val body = call.receive<SetPodsRequest>()
            call.respond(service.setPods(body.pods))
        }
        // Pre-warm the generator pool before a demo. status() polls the current count so
        // the UI can animate the pool scaling up; warmup() kicks off an async gcloud resize
        // so the request returns immediately.
        route("/pool") {
            get("/status") { call.respond(poolService.status()) }
            post("/warmup") { call.respond(poolService.warmup()) }
        }
    }
}
