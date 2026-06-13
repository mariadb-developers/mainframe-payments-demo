package com.gridgain.demo.payments.ui.services

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * Whether a Kafka Connect sink connector is currently applying events.
 *
 *  - [PAUSED]  the connector is paused; upstream keeps publishing to Kafka but
 *              nothing is applied to the sink target — events buffer durably.
 *  - [LIVE]    the connector is running; the Kafka backlog drains to the target.
 *  - [UNKNOWN] Kafka Connect was unreachable or returned something unexpected
 *              (e.g. the connector isn't deployed yet).
 */
enum class FeedState { PAUSED, LIVE, UNKNOWN }

/**
 * Pauses / resumes one Kafka Connect connector via the Connect REST API, so the
 * demo can show the "bring X online without losing events" beats (CLAUDE.md §2):
 * pause the sink, bulk-load a snapshot into the target, then resume so the
 * buffered Kafka events drain into the target. No events are lost because Kafka
 * holds them until the connector resumes from its committed offset.
 *
 * One instance per controlled connector (the inbound `cdc-sink` for MF→GG, and
 * the outbound GG→MariaDB sink). Drives the *deployed* connector rather than
 * re-implementing CDC in the demo backend, so the visualization reflects the
 * real pipeline; pausing is a runtime operation needing no toolkit change.
 */
class ConnectorControlService(
    private val connectBaseUrl: String,
    private val connectorName: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Current feed state, or [FeedState.UNKNOWN] if Connect can't be reached / the connector is absent. */
    fun state(): FeedState =
        try {
            val resp = http.send(get("status"), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                // 404 here is expected while a not-yet-deployed connector (e.g. the
                // GG→MariaDB sink) is pending — treat as UNKNOWN, not an error.
                log.debug("Kafka Connect status for '{}' returned {}", connectorName, resp.statusCode())
                FeedState.UNKNOWN
            } else {
                parseFeedState(resp.body())
            }
        } catch (e: Exception) {
            log.warn("Could not read state for connector '{}' from Kafka Connect at {}: {}", connectorName, connectBaseUrl, e.message)
            FeedState.UNKNOWN
        }

    /** Pauses the connector so events buffer in Kafka instead of landing in the target. */
    fun pause(): FeedState = putAction("pause")

    /** Resumes the connector; the buffered Kafka backlog drains to the target from the committed offset. */
    fun resume(): FeedState = putAction("resume")

    private fun putAction(action: String): FeedState {
        try {
            val resp = http.send(put(action), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                throw IllegalStateException("Kafka Connect '$action' on '$connectorName' returned ${resp.statusCode()}: ${resp.body()}")
            }
            log.info("connector '{}' {} requested", connectorName, action)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Could not $action the connector '$connectorName' via the Kafka Connect REST API at " +
                    "$connectBaseUrl. Ensure Connect is reachable and the connector is deployed (in dev, " +
                    "port-forward the Connect service to $connectBaseUrl). Cause: ${e.message}",
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
         * Maps a Kafka Connect `/connectors/{name}/status` body to a [FeedState].
         * The connector-level `state` is authoritative: Connect flips the connector
         * to PAUSED/RUNNING before its tasks settle, and that connector-level signal
         * is what the UI trusts.
         */
        fun parseFeedState(statusJson: String): FeedState {
            val node = runCatching { mapper.readTree(statusJson) }.getOrNull() ?: return FeedState.UNKNOWN
            return when (node.path("connector").path("state").asText("")) {
                "PAUSED" -> FeedState.PAUSED
                "RUNNING" -> FeedState.LIVE
                else -> FeedState.UNKNOWN
            }
        }
    }
}
