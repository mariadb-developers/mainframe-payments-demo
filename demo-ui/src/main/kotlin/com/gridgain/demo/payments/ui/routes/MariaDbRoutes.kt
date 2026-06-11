package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.services.MariaDbService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.mariaDbRoutes(service: MariaDbService) {
    route("/mariadb") {
        get("/queries") {
            call.respond(service.listQueries())
        }
        post("/queries/{id}/run") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Query id is required")
            call.respond(service.runQuery(id))
        }
    }
}
