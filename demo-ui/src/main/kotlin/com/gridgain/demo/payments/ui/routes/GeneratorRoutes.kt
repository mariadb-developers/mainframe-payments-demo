package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.model.GeneratorRate
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
        post("/rate") {
            val body = call.receive<Map<String, String>>()
            val rate = body["rate"]?.uppercase()
                ?: throw IllegalArgumentException("Body must include 'rate' (OFF|SLOW|MEDIUM|FAST)")
            val parsed = runCatching { GeneratorRate.valueOf(rate) }.getOrElse {
                throw IllegalArgumentException(
                    "Unknown rate '$rate'. Expected one of: ${GeneratorRate.values().joinToString { it.name }}",
                )
            }
            call.respond(service.setRate(parsed))
        }
    }
}
