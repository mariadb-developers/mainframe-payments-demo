package com.gridgain.demo.payments.ui.metrics

/**
 * Mirror of the data generator's `live-metrics.json` document (its
 * `com.gridgain.demo.datagen.metrics.MetricsSnapshot`). Field names match the generator's
 * camelCase JSON so it deserializes directly; the WebSocket route re-serializes it to
 * snake_case for the frontend, matching the rest of the API surface.
 */
data class MetricsSnapshot(
    val updatedAtMs: Long,
    val observedTps: Double,
    val avgLatencyMs: Double,
    val totalOps: Long,
    val errorCount: Long,
    val targetTps: Double,
    val runId: String,
    val active: Boolean,
)
