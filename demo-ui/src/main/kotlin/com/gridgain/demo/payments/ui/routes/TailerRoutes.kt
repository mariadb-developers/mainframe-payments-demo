package com.gridgain.demo.payments.ui.routes

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.gridgain.demo.payments.ui.tailer.TailerService
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

fun Route.tailerRoutes(service: TailerService) {
    val mapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
    route("/tailers") {
        listOf("gg-to-postgres", "gg-to-mariadb", "cdc").forEach { source ->
            webSocket("/$source") {
                val flow = service.subscribe(source)
                val job = launch {
                    flow.collect { event ->
                        send(Frame.Text(mapper.writeValueAsString(event)))
                    }
                }
                try {
                    for (frame in incoming) { /* client may ping/close; ignore data frames */ }
                } finally {
                    job.cancel()
                }
            }
        }
    }
}
