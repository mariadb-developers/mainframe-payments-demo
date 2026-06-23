package com.gridgain.demo.payments.ui.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * setPods shells out to `kubectl scale`, so the unit-testable seam is planPods: the pure decision
 * of pod count + running flag from a requested count (pods-only control).
 */
class GeneratorControlServiceTest {

    @Test
    fun `planPods 0 stops with zero pods`() {
        val plan = GeneratorControlService.planPods(0)
        assertFalse(plan.running)
        assertEquals(0, plan.replicas)
    }

    @Test
    fun `planPods runs the requested pod count`() {
        val plan = GeneratorControlService.planPods(5)
        assertTrue(plan.running)
        assertEquals(5, plan.replicas)
    }

    @Test
    fun `planPods honors a large requested count without clamping`() {
        // No upper clamp at the service layer — k8s scheduling + the generator pool's autoscale max
        // are the real ceiling; pods that exceed the pool's fit sit Pending until a node joins.
        val plan = GeneratorControlService.planPods(1000)
        assertTrue(plan.running)
        assertEquals(1000, plan.replicas)
    }

    @Test
    fun `planPods treats a negative request as stopped`() {
        val plan = GeneratorControlService.planPods(-3)
        assertFalse(plan.running)
        assertEquals(0, plan.replicas)
    }
}
