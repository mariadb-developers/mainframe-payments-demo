package com.gridgain.demo.payments.ui.routes

import com.gridgain.demo.payments.ui.model.ExecuteCuratedRequest
import com.gridgain.demo.payments.ui.services.MainframeProxyService
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.mainframeRoutes(service: MainframeProxyService) {
    route("/mainframe") {
        get("/transactions") {
            call.respond(service.listCuratedTransactions())
        }
        get("/balances") {
            call.respond(service.listAccountBalances())
        }
        post("/execute") {
            val req = call.receive<ExecuteCuratedRequest>()
            call.respond(service.executeCuratedTransaction(req.curatedTransactionId))
        }
    }
}
