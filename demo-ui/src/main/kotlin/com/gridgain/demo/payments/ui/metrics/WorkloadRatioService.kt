package com.gridgain.demo.payments.ui.metrics

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses the data generator's scenario file once to surface the read/write split as a workload
 * descriptor for the phase-6 dashboard. Returns "<reads>:<writes>" percentages (e.g. "20:80")
 * or null on any failure — the UI's latency-panel subtitle falls back to "mixed workload" when null.
 *
 * The ratio is a property of the scenario config, not measured telemetry, so this is read once
 * at startup and never refreshed.
 */
class WorkloadRatioService(
    private val opsFile: Path,
    private val scenarioName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun readWriteRatio(): String? {
        if (!Files.isRegularFile(opsFile)) {
            log.warn("Workload ratio: ops file not found at {} — subtitle will fall back", opsFile)
            return null
        }
        val parsed: Any? = try {
            Files.newBufferedReader(opsFile).use { Yaml().load<Any?>(it) }
        } catch (e: Exception) {
            log.warn("Workload ratio: failed to parse {} — {}", opsFile, e.message)
            return null
        }
        val scenarios = (parsed as? Map<*, *>)?.get("scenarios") as? List<*> ?: run {
            log.warn("Workload ratio: no 'scenarios' list in {}", opsFile)
            return null
        }
        val scenario = scenarios
            .filterIsInstance<Map<*, *>>()
            .firstOrNull { it["name"] == scenarioName } ?: run {
                log.warn("Workload ratio: scenario '{}' not in {}", scenarioName, opsFile)
                return null
            }
        val readRatio = (scenario["read_ratio"] as? Number)?.toDouble() ?: run {
            log.warn("Workload ratio: scenario '{}' has no read_ratio", scenarioName)
            return null
        }
        val readPct = (readRatio * 100).toInt().coerceIn(0, 100)
        return "$readPct:${100 - readPct}"
    }
}
