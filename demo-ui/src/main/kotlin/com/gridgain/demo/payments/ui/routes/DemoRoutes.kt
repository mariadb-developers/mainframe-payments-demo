package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.services.DemoResetService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.demoRoutes(service: DemoResetService) {
    route("/demo") {
        post("/reset") {
            call.respond(service.reset())
        }
    }
}
