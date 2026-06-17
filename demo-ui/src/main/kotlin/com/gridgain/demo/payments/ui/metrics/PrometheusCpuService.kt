package com.gridgain.demo.payments.ui.metrics

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Polls the deployed Prometheus for the GG cluster's average CPU load and republishes each reading
 * on a hot flow for the `/api/cpu` WebSocket — the "GG is bored at high load" readout (CLAUDE.md §3).
 *
 * Source metric: `sys_CpuLoad` (Ignite's per-node CPU gauge, 0..1), aggregated by [cpuQuery]
 * (default `avg(sys_CpuLoad)`) and scaled to a percent. The query is injectable so the exact metric
 * / labels can be tuned without a rebuild.
 *
 * The poll loop is added in A1.3; this class owns the testable [parse] seam + the [idle] snapshot.
 * [clockMs] is an injectable clock (mirrors `GeneratorMetricsService`) so timestamps are testable.
 */
class PrometheusCpuService(
    private val prometheusUrl: String,
    private val cpuQuery: String = "avg(sys_CpuLoad)",
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val mapper = ObjectMapper()

    /**
     * Parses a Prometheus `/api/v1/query` vector response into a snapshot, or null if there's no
     * usable sample (empty result, non-success status, malformed JSON). The returned snapshot is
     * left **unstamped** (`updatedAtMs = 0L`); the poll loop stamps it via [clockMs].
     */
    internal fun parse(json: String?): CpuSnapshot? {
        if (json.isNullOrBlank()) return null
        val root: JsonNode = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (root.path("status").asText() != "success") return null
        val first = root.path("data").path("result").firstOrNull() ?: return null
        val load = first.path("value").path(1).asText(null)?.toDoubleOrNull() ?: return null
        return CpuSnapshot(updatedAtMs = 0L, cpuPercent = load * 100.0, active = true)
    }

    /** Inactive reading emitted when Prometheus is unreachable, so the gauge zeros rather than freezes. */
    internal fun idle() = CpuSnapshot(updatedAtMs = clockMs(), cpuPercent = 0.0, active = false)
}
