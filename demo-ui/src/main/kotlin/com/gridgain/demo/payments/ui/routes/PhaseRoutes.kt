package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.services.PhaseService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.phaseRoutes(service: PhaseService) {
    route("/phase") {
        get {
            call.respond(service.current())
        }
        post {
            val body = call.receive<Map<String, Int>>()
            val target = body["phase"]
                ?: throw IllegalArgumentException("Body must include integer 'phase' field")
            call.respond(service.advanceTo(target))
        }
    }
}
