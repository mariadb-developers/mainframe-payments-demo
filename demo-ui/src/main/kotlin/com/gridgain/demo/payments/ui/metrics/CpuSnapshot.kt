package com.gridgain.demo.payments.ui.metrics

/**
 * GG cluster CPU readout for the `/api/cpu` WebSocket. [cpuPercent] is 0..100 — the average
 * `sys_CpuLoad` (Ignite's per-node CPU gauge, 0..1) across the GG nodes, scaled to a percent.
 * The WebSocket route re-serializes this to snake_case, matching the rest of the API.
 */
data class CpuSnapshot(
    val updatedAtMs: Long,
    val cpuPercent: Double,
    val active: Boolean,
)
