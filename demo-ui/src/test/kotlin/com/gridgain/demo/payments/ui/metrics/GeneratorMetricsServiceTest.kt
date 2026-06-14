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
}
