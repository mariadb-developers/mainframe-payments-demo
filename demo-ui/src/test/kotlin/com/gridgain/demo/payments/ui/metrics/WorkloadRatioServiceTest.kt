package com.gridgain.demo.payments.ui.metrics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkloadRatioServiceTest {

    private fun fixture(name: String): Path =
        Path.of("src/test/resources/workload-ratio").resolve(name)

    @Test
    fun `valid ops returns formatted reads-to-writes percent`() {
        val s = WorkloadRatioService(fixture("valid-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertEquals("20:80", s.readWriteRatio())
    }

    @Test
    fun `missing file returns null`(@TempDir tmp: Path) {
        val s = WorkloadRatioService(tmp.resolve("does-not-exist.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `unparseable yaml returns null`(@TempDir tmp: Path) {
        val bad = tmp.resolve("bad.yaml").apply { writeText(": : : not yaml") }
        val s = WorkloadRatioService(bad, scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `missing scenario returns null`() {
        val s = WorkloadRatioService(fixture("missing-scenario-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `missing read_ratio returns null`() {
        val s = WorkloadRatioService(fixture("missing-ratio-ops.yaml"), scenarioName = "mainframe-payments-load")
        assertNull(s.readWriteRatio())
    }

    @Test
    fun `read_ratio of 0_75 formats as 75 to 25`() {
        val s = WorkloadRatioService(fixture("valid-ops.yaml"), scenarioName = "some-other-scenario")
        assertEquals("75:25", s.readWriteRatio())
    }
}
