package com.gridgain.demo.payments.ui.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridgain.demo.payments.ui.model.FailedTaskRef
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

    /**
     * Resumes the connector; the buffered Kafka backlog drains to the target from the committed
     * offset. Also restarts any FAILED task: un-pausing does NOT clear a FAILED task, and Kafka
     * Connect leaves a task that hit an unrecoverable error (e.g. a stale idle JDBC connection the
     * DB dropped while the cluster sat between demos) in FAILED indefinitely while the connector
     * still shows RUNNING — applying nothing, silently breaking the "no events lost" reconciliation
     * (CLAUDE.md §2). Restarting on resume makes the bring-online beat actually drain.
     */
    fun resume(): FeedState {
        putAction("resume")
        restartFailedTasks()
        return state()
    }

    /**
     * Restart any FAILED tasks of this connector. Best-effort: logs and returns the count
     * restarted; never throws (a heal attempt must not break its caller).
     */
    fun restartFailedTasks(): Int =
        try {
            val resp = http.send(get("status"), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                0
            } else {
                val ids = failedTaskIds(resp.body())
                ids.forEach { id ->
                    val r = http.send(restartTask(id), HttpResponse.BodyHandlers.ofString())
                    if (r.statusCode() in 200..299) {
                        log.info("restarted FAILED task {} of connector '{}'", id, connectorName)
                    } else {
                        log.warn("restart of task {} of '{}' returned {}", id, connectorName, r.statusCode())
                    }
                }
                ids.size
            }
        } catch (e: Exception) {
            log.warn("Could not restart failed tasks for connector '{}': {}", connectorName, e.message)
            0
        }

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
                    "run scripts/dev-port-forwards.sh to expose the payments-proxy). Cause: ${e.message}",
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

    private fun restartTask(taskId: Int): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$connectBaseUrl/connectors/$connectorName/tasks/$taskId/restart"))
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val mapper = ObjectMapper()
        private val healLog = LoggerFactory.getLogger(ConnectorControlService::class.java)

        /** Task ids in FAILED state from a `/connectors/{name}/status` body. */
        fun failedTaskIds(statusJson: String): List<Int> {
            val node = runCatching { mapper.readTree(statusJson) }.getOrNull() ?: return emptyList()
            return node.path("tasks").mapNotNull { t ->
                if (t.path("state").asText("") == "FAILED") t.path("id").asInt() else null
            }
        }

        /**
         * List every FAILED task across ALL deployed connectors (connector RUNNING but a task dead).
         * Best-effort; returns empty on any error so a health poll never throws.
         */
        fun listFailedTasks(
            connectBaseUrl: String,
            http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        ): List<FailedTaskRef> =
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create("$connectBaseUrl/connectors?expand=status"))
                    .timeout(REQUEST_TIMEOUT).GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) {
                    emptyList()
                } else {
                    buildList {
                        mapper.readTree(resp.body()).fields().forEach { (name, info) ->
                            info.path("status").path("tasks").forEach { t ->
                                if (t.path("state").asText("") == "FAILED") add(FailedTaskRef(name, t.path("id").asInt()))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                healLog.warn("Could not list failed connector tasks via {}: {}", connectBaseUrl, e.message)
                emptyList()
            }

        /**
         * Restart every FAILED task across ALL deployed connectors. Used at demo reset so a sink
         * that died while the cluster idled between demos (a stale JDBC connection the DB dropped)
         * is healed before the run starts — covers connectors the demo never pauses/resumes (e.g.
         * the GG→Postgres sink). Best-effort; returns the number of tasks restarted, never throws.
         */
        fun restartAllFailedTasks(
            connectBaseUrl: String,
            http: HttpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        ): Int {
            var count = 0
            listFailedTasks(connectBaseUrl, http).forEach { ft ->
                try {
                    val rr = http.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create("$connectBaseUrl/connectors/${ft.connector}/tasks/${ft.task}/restart"))
                            .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    if (rr.statusCode() in 200..299) {
                        count++
                        healLog.info("reset heal: restarted FAILED task {} of connector '{}'", ft.task, ft.connector)
                    }
                } catch (e: Exception) {
                    healLog.warn("restart of {}:{} failed: {}", ft.connector, ft.task, e.message)
                }
            }
            return count
        }

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
