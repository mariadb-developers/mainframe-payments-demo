package com.gridgain.demo.payments.ui.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * status() and warmup() both shell out (kubectl + gcloud), so the unit-testable
 * seam is the pure helpers: command-builders and the parsers/classifiers that
 * turn process output into a PoolStatus.
 */
class GeneratorPoolServiceTest {

    // -----------------------------------------------------------------------
    // parseNodeCount

    @Test
    fun `parseNodeCount returns zero for empty output`() {
        assertEquals(0, GeneratorPoolService.parseNodeCount(""))
        assertEquals(0, GeneratorPoolService.parseNodeCount("\n\n"))
    }

    @Test
    fun `parseNodeCount counts node lines from kubectl -o name output`() {
        val out = """
            node/gke-mainframe-paymen-wp-payments-load-abc-1
            node/gke-mainframe-paymen-wp-payments-load-abc-2
            node/gke-mainframe-paymen-wp-payments-load-abc-3
        """.trimIndent()
        assertEquals(3, GeneratorPoolService.parseNodeCount(out))
    }

    @Test
    fun `parseNodeCount ignores blank lines and non-node prefixes`() {
        val out = "node/x\n\nwarning: something\nnode/y\n"
        assertEquals(2, GeneratorPoolService.parseNodeCount(out))
    }

    // -----------------------------------------------------------------------
    // classify

    @Test
    fun `classify cold when zero or one node`() {
        assertEquals("cold", GeneratorPoolService.classify(current = 0, max = 6))
        assertEquals("cold", GeneratorPoolService.classify(current = 1, max = 6))
    }

    @Test
    fun `classify warm when current meets or exceeds max`() {
        assertEquals("warm", GeneratorPoolService.classify(current = 6, max = 6))
        assertEquals("warm", GeneratorPoolService.classify(current = 7, max = 6))
    }

    @Test
    fun `classify scaling between cold and warm`() {
        assertEquals("scaling", GeneratorPoolService.classify(current = 2, max = 6))
        assertEquals("scaling", GeneratorPoolService.classify(current = 5, max = 6))
    }

    // -----------------------------------------------------------------------
    // command builders

    @Test
    fun `kubectlCountCommand uses the GKE-nodepool label selector`() {
        val cmd = GeneratorPoolService.kubectlCountCommand("wp-payments-load")
        assertEquals(
            listOf(
                "kubectl", "get", "nodes",
                "-l", "cloud.google.com/gke-nodepool=wp-payments-load",
                "-o", "name",
            ),
            cmd,
        )
    }

    @Test
    fun `gradleWarmupCommand delegates to the plugin task with the dataGeneratorName flag`() {
        val cmd = GeneratorPoolService.gradleWarmupCommand("payments-load")
        // We shell out to the plugin's task — the toolkit owns the gcloud command shape
        // (covered by WarmupDataGeneratorPoolActionTest in the plugin). All we assert here
        // is that the right task is invoked with the right -P arg.
        assertEquals("./gradlew", cmd[0])
        assertTrue(cmd.contains("warmupDataGeneratorPool"))
        assertTrue(cmd.contains("-PdataGeneratorName=payments-load"))
    }
}
