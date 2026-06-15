package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.model.SetLoadRequest
import com.gridgain.demo.payments.ui.services.GeneratorControlService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.generatorRoutes(service: GeneratorControlService) {
    route("/generator") {
        get {
            call.respond(service.state())
        }
        // Manual load control (CLAUDE.md §3/§10): total target ops/sec across all pods
        // (0 = off) plus pod count. The service splits the total across pods and
        // (re)launches the distributed run, tearing down any prior run first.
        post("/rate") {
            val body = call.receive<SetLoadRequest>()
            call.respond(service.setLoad(body.targetOpsPerSecond, body.replicas))
        }
    }
}
