package com.gridgain.demo.payments.ui.routes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.gridgain.demo.payments.ui.metrics.PrometheusCpuService
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.launch

/**
 * `/api/cpu` — a single WebSocket streaming the GG cluster's average CPU (`avg(sys_CpuLoad)` from
 * the deployed Prometheus) ~once every few seconds. Snake_case on the wire to match the rest of the
 * API (and the frontend types). Mirrors [metricsRoutes]'s subscribe/forward pattern.
 */
fun Route.cpuRoutes(service: PrometheusCpuService) {
    val mapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }
    webSocket("/cpu") {
        val flow = service.subscribe()
        val job = launch {
            flow.collect { snapshot ->
                send(Frame.Text(mapper.writeValueAsString(snapshot)))
            }
        }
        try {
            for (frame in incoming) { /* client may ping/close; ignore inbound data frames */ }
        } finally {
            job.cancel()
        }
    }
}
