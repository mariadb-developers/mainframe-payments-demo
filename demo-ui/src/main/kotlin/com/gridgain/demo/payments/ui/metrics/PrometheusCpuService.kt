package com.gridgain.demo.payments.ui.metrics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory

/**
 * Polls the deployed Prometheus for the GG cluster's average CPU load and republishes each reading
 * on a hot flow for the `/api/cpu` WebSocket — the "GG is bored at high load" readout (CLAUDE.md §3).
 *
 * Source metric: `sys_CpuLoad` (Ignite's per-node CPU gauge, 0..1), aggregated by [cpuQuery]
 * (default `avg(sys_CpuLoad)`) and scaled to a percent. The query is injectable so the exact metric
 * / labels can be tuned without a rebuild.
 *
 * Mirrors [GeneratorMetricsService]'s consumer pattern: own daemon thread, retry-with-backoff,
 * `replay = 1` so a newly-connected WebSocket gets the latest reading immediately. When Prometheus
 * is unreachable for [stalePolls] consecutive polls, an [idle] snapshot is emitted once so the gauge
 * zeros rather than freezing on the last live value. [clockMs] is injectable for testing.
 */
class PrometheusCpuService(
    private val prometheusUrl: String,
    private val cpuQuery: String = "avg(sys_CpuLoad)",
    private val pollIntervalMs: Long = 2_000L,
    private val stalePolls: Int = 2,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val flow = MutableSharedFlow<CpuSnapshot>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val thread = Thread(::run, "prometheus-cpu-poller").apply { isDaemon = true }

    @Volatile private var stopped = false

    fun subscribe(): SharedFlow<CpuSnapshot> = flow.asSharedFlow()

    fun start() {
        thread.start()
    }

    private fun run() {
        val url = "$prometheusUrl/api/v1/query?query=" + URLEncoder.encode(cpuQuery, StandardCharsets.UTF_8)
        var consecutiveFailures = 0
        var idleEmitted = false
        while (!stopped) {
            val snapshot = runCatching { fetch(url) }.getOrNull()
            if (snapshot != null) {
                consecutiveFailures = 0
                idleEmitted = false
                flow.tryEmit(snapshot.copy(updatedAtMs = clockMs()))
            } else {
                consecutiveFailures++
                // Prometheus may not be reachable yet (dev: backend up before the payments-proxy port-forward).
                // Emit idle once after a couple of misses so the gauge zeros instead of freezing.
                if (consecutiveFailures >= stalePolls && !idleEmitted) {
                    flow.tryEmit(idle())
                    idleEmitted = true
                }
            }
            if (!stopped) Thread.sleep(pollIntervalMs)
        }
    }

    /** GET the query and parse it; null on any HTTP/parse failure (caller treats as a miss). */
    private fun fetch(url: String): CpuSnapshot? {
        val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        return parse(resp.body())
    }

    /**
     * Parses a Prometheus `/api/v1/query` vector response into a snapshot, or null if there's no
     * usable sample (empty result, non-success status, malformed JSON). The returned snapshot is
     * left **unstamped** (`updatedAtMs = 0L`); [run] stamps it via [clockMs].
     */
    internal fun parse(json: String?): CpuSnapshot? {
        if (json.isNullOrBlank()) return null
        val root: JsonNode = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (root.path("status").asText() != "success") return null
        val first = root.path("data").path("result").firstOrNull() ?: return null
        val load = first.path("value").path(1).asText(null)?.toDoubleOrNull() ?: return null
        return CpuSnapshot(updatedAtMs = 0L, cpuPercent = load * 100.0, active = true)
    }

    /** Inactive reading emitted when Prometheus is unreachable, so the gauge zeros rather than freezing. */
    internal fun idle() = CpuSnapshot(updatedAtMs = clockMs(), cpuPercent = 0.0, active = false)

    override fun close() {
        stopped = true
        thread.interrupt()
    }
}
