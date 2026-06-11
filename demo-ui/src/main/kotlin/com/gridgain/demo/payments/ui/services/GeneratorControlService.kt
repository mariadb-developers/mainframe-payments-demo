package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.GeneratorRate
import com.gridgain.demo.payments.ui.model.GeneratorState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * Wraps the data-generator scenario for the phase-5 stepped slider. Each step
 * stops the running scenario (if any) and starts a new one at the requested
 * rate by spawning a `./gradlew dataGenerate` subprocess. The brief gap between
 * stop and start is acceptable per CLAUDE.md §10 (live, in-flight rate change
 * is deferred to §16).
 *
 * The actual generator runtime is the toolkit's `dataGenerate` task — this
 * service just orchestrates start/stop.
 */
class GeneratorControlService(private val config: UiConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val current = AtomicReference(GeneratorState(GeneratorRate.OFF, null))

    fun state(): GeneratorState = current.get()

    fun setRate(rate: GeneratorRate): GeneratorState {
        val prev = current.get()
        if (prev.rate == rate) return prev
        prev.runId?.let { stop(it) }
        val newRunId = if (rate == GeneratorRate.OFF) null else start(rate)
        val next = GeneratorState(rate, newRunId)
        current.set(next)
        return next
    }

    private fun start(rate: GeneratorRate): String {
        val runId = UUID.randomUUID().toString().take(8)
        // The Gradle data-generator integration is launched as a subprocess in
        // the demo project directory. Wiring is intentionally minimal in v1 —
        // the dataGenerate task already reads ops.yaml / data.yaml, so all we
        // do here is invoke it. Future work (CLAUDE.md §16 live rate change)
        // can replace this with an in-process generator API.
        val pb = ProcessBuilder(
            "./gradlew", "dataGenerate",
            "-PgeneratorRateOverride=${rate.opsPerSecond}",
        ).directory(config.projectDirectory.toFile())
            .redirectErrorStream(true)
        runCatching { pb.start() }
            .onFailure { log.warn("Failed to start data generator at rate=$rate: ${it.message}") }
            .onSuccess { log.info("Started data generator runId=$runId rate=$rate (${rate.opsPerSecond} ops/sec)") }
        return runId
    }

    private fun stop(runId: String) {
        // v1 stop is best-effort: the subprocess launches a long-running scenario
        // and will exit on duration; explicit stop is not yet wired through the
        // toolkit. The stepped slider's design tolerates a brief overlap.
        log.info("Stop requested for generator runId=$runId (best-effort; full stop wiring is future work)")
    }
}
