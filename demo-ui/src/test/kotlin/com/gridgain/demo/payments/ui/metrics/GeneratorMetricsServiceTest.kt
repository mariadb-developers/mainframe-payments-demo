package com.gridgain.demo.payments.ui.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratorMetricsServiceTest {

    // No Kafka needed: the parse step (camelCase JSON the generator publishes -> snapshot) is the
    // unit-testable seam; the consumer loop is exercised end-to-end against a live broker.
    private val service = GeneratorMetricsService(kafkaBootstrapServers = "unused:9092", topic = "generator-metrics")

    @Test
    fun `parses a generator snapshot (camelCase JSON)`() {
        val json = """
            {"updatedAtMs":1700000000000,"observedTps":142.5,"avgLatencyMs":3.2,
             "totalOps":9000,"errorCount":4,"targetTps":200.0,"runId":"abc123","active":true}
        """.trimIndent()

        val s = service.parse(json)!!

        assertEquals(142.5, s.observedTps, 1e-9)
        assertEquals(3.2, s.avgLatencyMs, 1e-9)
        assertEquals(9000L, s.totalOps)
        assertEquals(4L, s.errorCount)
        assertEquals("abc123", s.runId)
        assertTrue(s.active)
    }

    @Test
    fun `returns null for blank or malformed payloads`() {
        assertNull(service.parse(null))
        assertNull(service.parse(""))
        assertNull(service.parse("   "))
        assertNull(service.parse("{not valid json"))
        assertNull(service.parse("[1,2,3]")) // wrong shape
    }

    private fun snap(runId: String, tps: Double, latency: Double, ops: Long, errors: Long, target: Double) =
        MetricsSnapshot(
            updatedAtMs = 1700000000000L, observedTps = tps, avgLatencyMs = latency,
            totalOps = ops, errorCount = errors, targetTps = target, runId = runId, active = true,
        )

    @Test
    fun `aggregates concurrent pods - sums throughput, ops, errors and target`() {
        // Two pods each publish their own snapshot (distinct runId). The panel must show the
        // SUM, not whichever pod reported last — otherwise adding pods never moves the rate.
        val a = snap("pod-a", tps = 448.0, latency = 2.0, ops = 1000, errors = 1, target = 550.0)
        val b = snap("pod-b", tps = 432.0, latency = 4.0, ops = 1200, errors = 3, target = 550.0)

        val agg = service.aggregate(listOf(a, b), nowMs = 1700000000123L)

        assertEquals(880.0, agg.observedTps, 1e-9)
        assertEquals(2200L, agg.totalOps)
        assertEquals(4L, agg.errorCount)
        assertEquals(1100.0, agg.targetTps, 1e-9) // total target across all pods
        assertTrue(agg.active)
        // tps-weighted latency so a faster pod weighs more: (2*448 + 4*432) / 880
        assertEquals((2.0 * 448.0 + 4.0 * 432.0) / 880.0, agg.avgLatencyMs, 1e-9)
        assertEquals(1700000000123L, agg.updatedAtMs)
    }

    @Test
    fun `single pod aggregate preserves its values and runId`() {
        val solo = snap("solo", tps = 500.0, latency = 3.0, ops = 10, errors = 0, target = 1100.0)

        val agg = service.aggregate(listOf(solo), nowMs = 1700000000000L)

        assertEquals(500.0, agg.observedTps, 1e-9)
        assertEquals(3.0, agg.avgLatencyMs, 1e-9)
        assertEquals("solo", agg.runId) // keep the real runId when there's only one pod
    }

    @Test
    fun `latency falls back to simple average when no throughput yet`() {
        val a = snap("pod-a", tps = 0.0, latency = 5.0, ops = 0, errors = 0, target = 550.0)
        val b = snap("pod-b", tps = 0.0, latency = 7.0, ops = 0, errors = 0, target = 550.0)

        val agg = service.aggregate(listOf(a, b), nowMs = 1700000000000L)

        assertEquals(0.0, agg.observedTps, 1e-9)
        assertEquals(6.0, agg.avgLatencyMs, 1e-9) // (5+7)/2, not a divide-by-zero
    }

    @Test
    fun `aggregate stamps rwRatio on the returned snapshot`() {
        val withRatio = GeneratorMetricsService(
            kafkaBootstrapServers = "unused:9092",
            topic = "generator-metrics",
            rwRatio = "20:80",
        )
        val a = snap("pod-a", tps = 1000.0, latency = 1.0, ops = 1000, errors = 0, target = 1100.0)
        val b = snap("pod-b", tps = 500.0, latency = 2.0, ops = 500, errors = 0, target = 1100.0)

        val agg = withRatio.aggregate(listOf(a, b), nowMs = 1700000000000L)

        assertEquals("20:80", agg.rwRatio)
    }

    @Test
    fun `single-pod aggregate also stamps rwRatio`() {
        val withRatio = GeneratorMetricsService(
            kafkaBootstrapServers = "unused:9092",
            topic = "generator-metrics",
            rwRatio = "20:80",
        )
        val solo = snap("solo", tps = 500.0, latency = 3.0, ops = 10, errors = 0, target = 1100.0)

        val agg = withRatio.aggregate(listOf(solo), nowMs = 1700000000000L)

        assertEquals("20:80", agg.rwRatio)
    }
}
