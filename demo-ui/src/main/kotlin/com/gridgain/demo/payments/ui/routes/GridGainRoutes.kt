package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.model.ExecuteGridGainPurchaseRequest
import com.gridgain.demo.payments.ui.services.GridGainService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.gridGainRoutes(service: GridGainService) {
    route("/gridgain") {
        get("/customers") {
            call.respond(service.listCustomers())
        }
        get("/products") {
            call.respond(service.listProducts())
        }
        get("/balances") {
            call.respond(service.listAccountBalances())
        }
        get("/status") {
            call.respond(mapOf("connected" to service.connected()))
        }
        post("/execute") {
            val req = call.receive<ExecuteGridGainPurchaseRequest>()
            call.respond(service.executePurchase(req.customerId, req.accountId, req.productId))
        }
    }
}
