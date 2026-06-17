package com.gridgain.demo.payments.ui.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The launch/stop side of GeneratorControlService shells out to gradle/kubectl, so the
 * unit-testable seam is planPods: the pure decision of pods + per-pod rate from a requested
 * pod count (pods-only control — the rate is pinned to the unthrottled ceiling, not divided).
 */
class GeneratorControlServiceTest {

    @Test
    fun `planPods 0 stops with zero pods and no rate`() {
        val plan = GeneratorControlService.planPods(0)
        assertFalse(plan.running)
        assertEquals(0, plan.replicas)
        assertEquals(0, plan.perPodOps)
    }

    @Test
    fun `planPods runs each pod at the unthrottled ceiling`() {
        val plan = GeneratorControlService.planPods(5)
        assertTrue(plan.running)
        assertEquals(5, plan.replicas)
        assertEquals(GeneratorControlService.PER_POD_CEILING, plan.perPodOps)
    }

    @Test
    fun `planPods clamps a request above MAX_PODS`() {
        val plan = GeneratorControlService.planPods(1000)
        assertTrue(plan.running)
        assertEquals(GeneratorControlService.MAX_PODS, plan.replicas)
    }

    @Test
    fun `planPods treats a negative request as stopped`() {
        val plan = GeneratorControlService.planPods(-3)
        assertFalse(plan.running)
        assertEquals(0, plan.replicas)
    }
}
