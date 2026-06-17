package com.gridgain.demo.payments.ui.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrometheusCpuServiceTest {
    private val service = PrometheusCpuService(prometheusUrl = "http://unused:9090")

    @Test
    fun `parses avg sys_CpuLoad scalar result into a percent`() {
        // Prometheus /api/v1/query with `avg(sys_CpuLoad)` returns a single vector sample.
        val json = """
            {"status":"success","data":{"resultType":"vector","result":[
              {"metric":{},"value":[1700000000.0,"0.214"]}]}}
        """.trimIndent()
        val s = service.parse(json)!!
        assertEquals(21.4, s.cpuPercent, 1e-6) // 0.214 -> 21.4%
    }

    @Test
    fun `null on empty result, error status, or malformed json`() {
        assertNull(service.parse("""{"status":"success","data":{"resultType":"vector","result":[]}}"""))
        assertNull(service.parse("""{"status":"error","error":"boom"}"""))
        assertNull(service.parse("not json"))
        assertNull(service.parse(null))
    }

    @Test
    fun `idle snapshot is inactive, zero, and stamped from the injected clock`() {
        val fixed = PrometheusCpuService(prometheusUrl = "http://unused:9090", clockMs = { 1700000000000L })
        val idle = fixed.idle()
        assertFalse(idle.active)
        assertEquals(0.0, idle.cpuPercent, 1e-9)
        assertEquals(1700000000000L, idle.updatedAtMs)
    }
}
