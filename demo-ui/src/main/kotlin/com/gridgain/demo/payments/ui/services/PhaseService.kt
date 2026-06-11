package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.PhaseState
import java.util.concurrent.atomic.AtomicInteger

/**
 * Source of truth for the demo phase (0 = pre-show, 1..6 = visible panels per
 * CLAUDE.md §3). The state machine is forward-only — backward transitions are
 * rejected. The UI is also free to track this state locally; this service
 * exists so multiple browser windows in the same session can agree.
 */
class PhaseService {
    private val phase = AtomicInteger(0)

    fun current(): PhaseState = PhaseState(phase.get())

    fun advanceTo(target: Int): PhaseState {
        require(target in 0..6) { "Phase must be between 0 and 6, was $target" }
        while (true) {
            val cur = phase.get()
            require(target >= cur) { "Phase transitions are forward-only (current=$cur, requested=$target)" }
            if (phase.compareAndSet(cur, target)) return PhaseState(target)
        }
    }

    /**
     * Backward jump used by the demo Reset action only. Bypasses the
     * forward-only invariant on purpose: a reset takes the presenter back to
     * the opening state regardless of where they were.
     */
    fun reset(): PhaseState {
        phase.set(0)
        return PhaseState(0)
    }
}
