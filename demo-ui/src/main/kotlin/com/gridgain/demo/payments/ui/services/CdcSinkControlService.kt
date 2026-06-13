package com.gridgain.demo.payments.ui.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridgain.demo.payments.ui.config.UiConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Whether the inbound mainframe -> GG event feed (the `cdc-sink` Kafka Connect
 * connector) is currently applying events to GG.
 *
 *  - [PAUSED]  the connector is paused; Debezium keeps publishing mainframe
 *              changes to Kafka but nothing is applied to GG — events buffer.
 *  - [LIVE]    the connector is running; the Kafka backlog drains into GG.
 *  - [UNKNOWN] Kafka Connect was unreachable or returned something unexpected.
 */
enum class CdcFeedState { PAUSED, LIVE, UNKNOWN }

/**
 * Pauses / resumes the inbound CDC sink so the demo can show the "bring GridGain
 * online without losing events" beat (CLAUDE.md §2 phase 2): the presenter
 * bulk-loads a Postgres snapshot into GG while the sink is paused (Kafka
 * durably buffers the change events), then resumes the sink so the buffered
 * events — including any mainframe transaction fired during the load window —
 * drain into GG. No events are lost because Kafka holds them until the sink
 * resumes from its committed offset.
 *
 * This drives the *deployed* connector via the Kafka Connect REST API rather
 * than re-implementing CDC in the demo backend, so the visualization reflects
 * the real pipeline. Pausing is a runtime operation — it requires no change to
 * the toolkit's `cdc_connectors` element.
 */
class CdcSinkControlService(
    private val connectBaseUrl: String,
    private val connectorName: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
) {
    constructor(config: UiConfig) : this(config.kafkaConnectUrl, config.cdcSinkConnectorName)

    private val log = LoggerFactory.getLogger(javaClass)

    /** Current feed state, or [CdcFeedState.UNKNOWN] if Connect can't be reached. */
    fun state(): CdcFeedState =
        try {
            val resp = http.send(get("status"), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                log.warn("Kafka Connect status for '{}' returned {}", connectorName, resp.statusCode())
                CdcFeedState.UNKNOWN
            } else {
                parseFeedState(resp.body())
            }
        } catch (e: Exception) {
            log.warn("Could not read cdc-sink state from Kafka Connect at {}: {}", connectBaseUrl, e.message)
            CdcFeedState.UNKNOWN
        }

    /** Pauses the sink so mainframe events buffer in Kafka instead of landing in GG. */
    fun pause(): CdcFeedState = putAction("pause")

    /** Resumes the sink; the buffered Kafka backlog drains into GG from the committed offset. */
    fun resume(): CdcFeedState = putAction("resume")

    private fun putAction(action: String): CdcFeedState {
        try {
            val resp = http.send(put(action), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                throw IllegalStateException("Kafka Connect '$action' returned ${resp.statusCode()}: ${resp.body()}")
            }
            log.info("cdc-sink connector '{}' {} requested", connectorName, action)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not $action the cdc-sink connector '$connectorName' via the Kafka Connect REST API at " +
                    "$connectBaseUrl. Ensure Connect is reachable (in dev, port-forward the Connect service to " +
                    "$connectBaseUrl). Cause: ${e.message}",
                e,
            )
        }
        return state()
    }

    private fun get(path: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$connectBaseUrl/connectors/$connectorName/$path"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()

    private fun put(path: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$connectBaseUrl/connectors/$connectorName/$path"))
            .timeout(REQUEST_TIMEOUT)
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val mapper = ObjectMapper()

        /**
         * Maps a Kafka Connect `/connectors/{name}/status` body to a [CdcFeedState].
         * The connector-level `state` is authoritative: Connect flips the connector
         * to PAUSED/RUNNING before its tasks settle, and that connector-level signal
         * is what the UI trusts.
         */
        fun parseFeedState(statusJson: String): CdcFeedState {
            val node = runCatching { mapper.readTree(statusJson) }.getOrNull() ?: return CdcFeedState.UNKNOWN
            return when (node.path("connector").path("state").asText("")) {
                "PAUSED" -> CdcFeedState.PAUSED
                "RUNNING" -> CdcFeedState.LIVE
                else -> CdcFeedState.UNKNOWN
            }
        }
    }
}
