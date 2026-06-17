package com.gridgain.demo.payments.ui.services

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.GeneratorState
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Manual load control for the phase-6 data generator (CLAUDE.md §3/§10).
 *
 * The old stepped off/slow/medium/fast slider was a no-op: it passed a
 * `-PgeneratorRateOverride` gradle property the plugin never reads, so every press
 * ran the same single pod at the rate pinned in `ops.yaml`. The generator never got
 * past ~one pod's worth of load, which is why "the system isn't very taxed."
 *
 * This service instead drives the load through levers the plugin actually honours:
 *
 *  1. **Manual rate** — the plugin reads the rate only from the ops file, but
 *     `dataGenerate` accepts `--ops=<path>`. So each load change rewrites a runtime
 *     ops.yaml (the canonical file with `rate.ops_per_second` overridden) and points
 *     `--ops` at it. No plugin change required.
 *  2. **Multi-pod** — the same runtime ops.yaml injects a `distribution:` block
 *     ({replicas, partition_count}); the plugin then dispatches a Deployment of N
 *     worker pods (each single-threaded). Adding pods is the real lever for
 *     saturating GG — a single pod is capped by GG round-trip latency (~500 ops/sec). The
 *     plugin does NOT divide the rate across pods (each pod runs the full ops.yaml rate), so
 *     this service pins the per-pod rate to an unthrottled ceiling and varies only the pod
 *     count: total throughput ≈ pods × the single-pod ceiling.
 *  3. **Clean stop** — distributed runs are long-lived Deployments; killing the
 *     gradle subprocess does NOT stop them. Each load change first
 *     `kubectl delete`s every prior run by the `gridgain.com/scenario` label, so runs
 *     never pile up (the old best-effort stop left orphaned Jobs accumulating).
 *
 * Distributed mode also makes `dataGenerate` fire-and-forget (it returns once the
 * Deployment is Ready rather than blocking for completion), so the launch subprocess
 * is short-lived and the UI stays responsive.
 */
class GeneratorControlService(private val config: UiConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()

    @Volatile
    private var state = GeneratorState(targetOpsPerSecond = 0, replicas = 0, running = false)

    // The most recent `./gradlew dataGenerate` subprocess. Held only so a rapid
    // re-issue can kill an in-flight apply before launching the next one — the
    // Deployment it creates outlives the process and is torn down by label on stop.
    @Volatile
    private var launchProcess: Process? = null

    fun state(): GeneratorState = state

    /**
     * Set the number of generator pods (0 = off). Pods are the only lever: a single pod is capped
     * by GG round-trip latency (~500 ops/sec), so total throughput ≈ pods × that ceiling, and the
     * way to push GG is to add pods. Each call first tears down any running generator (clean stop),
     * then — if pods > 0 — launches a fresh distributed run with the per-pod rate pinned to an
     * unthrottled ceiling so each pod runs flat-out.
     */
    fun setPods(requestedPods: Int): GeneratorState = synchronized(lock) {
        val plan = planPods(requestedPods)

        // Always stop first: clears the previous Deployment (and any orphaned earlier
        // runs) by label so runs never accumulate, then a clean slate to start from.
        launchProcess?.takeIf { it.isAlive }?.destroy()
        launchProcess = null
        stopAllRuns()

        if (!plan.running) {
            state = GeneratorState(targetOpsPerSecond = 0, replicas = 0, running = false)
            log.info("Generator stopped (pods=0).")
            return@synchronized state
        }

        val partitionCount = maxOf(DEFAULT_PARTITION_COUNT, plan.replicas)
        val runtimeOps = writeRuntimeOps(plan.perPodOps, plan.replicas, partitionCount)
        startDistributed(runtimeOps, plan.perPodOps, plan.replicas)

        state = GeneratorState(
            targetOpsPerSecond = plan.replicas * APPROX_OPS_PER_POD, // display estimate; pods are the real knob
            replicas = plan.replicas,
            running = true,
        )
        log.info(
            "Generator load set: {} pod(s), each unthrottled (~{} ops/sec/pod ceiling), partition_count={}.",
            plan.replicas, APPROX_OPS_PER_POD, partitionCount,
        )
        return@synchronized state
    }

    /**
     * Rewrite the canonical ops.yaml into a runtime copy with [perPodOps] as the
     * scenario's `rate.ops_per_second` and an injected `distribution:` block. All
     * other blocks (targets, metrics, duration, transaction_scope, read_ratio) are
     * preserved verbatim — the canonical file stays the single source of truth.
     */
    private fun writeRuntimeOps(perPodOps: Int, replicas: Int, partitionCount: Int): Path {
        val canonical = config.projectDirectory.resolve("src/main/resources/generator/ops.yaml")
        require(canonical.toFile().exists()) {
            "Canonical ops.yaml not found at $canonical. The demo's generator scenario must ship there."
        }
        val mapper = YAMLMapper()
        val root = mapper.readTree(canonical.toFile()) as? ObjectNode
            ?: throw IllegalStateException("ops.yaml at $canonical is not a YAML mapping.")
        val scenarios = root.get("scenarios") as? ArrayNode
            ?: throw IllegalStateException("ops.yaml at $canonical has no 'scenarios' array.")
        val scenario = scenarios
            .firstOrNull { it.get("name")?.asText() == config.generatorScenario } as? ObjectNode
            ?: throw IllegalStateException(
                "scenario '${config.generatorScenario}' not found in $canonical. " +
                    "Available: ${scenarios.mapNotNull { it.get("name")?.asText() }}.",
            )

        val rate = scenario.get("rate") as? ObjectNode
            ?: scenario.putObject("rate").also { it.put("kind", "constant") }
        rate.put("ops_per_second", perPodOps)

        // Run CONTINUOUSLY. The canonical scenario caps at PT10M, but a distributed Deployment pod
        // that finishes its scenario EXITS and is restarted by k8s — producing ~10-min throughput
        // pulses with dead gaps (restart + reconnect + ramp), which also drags the sustained rate
        // down. Override to a long duration so each pod runs until the presenter stops the load
        // (which tears the Deployment down by label). The pod is single-threaded, so a load run is
        // bounded by pod count, not duration.
        val duration = scenario.get("duration") as? ObjectNode
            ?: scenario.putObject("duration").also { it.put("kind", "time") }
        duration.put("kind", "time")
        duration.put("value", LOAD_DURATION)

        val distribution = scenario.putObject("distribution")
        distribution.put("replicas", replicas)
        distribution.put("partition_count", partitionCount)

        val runtimeOps = config.projectDirectory
            .resolve("build/gridgain/output/data-generator/ops-runtime.yaml")
        runtimeOps.toFile().parentFile?.mkdirs()
        mapper.writeValue(runtimeOps.toFile(), root)
        return runtimeOps
    }

    /**
     * Launch `./gradlew dataGenerate --scenario <s> --ops <runtimeOps>` as a
     * fire-and-forget subprocess. In distributed mode the task applies the Deployment
     * and returns once it is Ready, so we don't `waitFor()` — the UI stays responsive
     * and the load runs independently until the next stop.
     */
    private fun startDistributed(runtimeOps: Path, perPodOps: Int, replicas: Int) {
        val logFile = config.projectDirectory
            .resolve("build/gridgain/output/data-generator/datagen-launch.log").toFile()
        logFile.parentFile?.mkdirs()
        val pb = ProcessBuilder(
            "./gradlew", "dataGenerate",
            "--scenario", config.generatorScenario,
            "--ops", runtimeOps.toString(),
        ).directory(config.projectDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile)
        launchProcess = runCatching { pb.start() }
            .onFailure {
                log.warn("Failed to launch data generator (per-pod={}, replicas={}): {}", perPodOps, replicas, it.message)
            }
            .onSuccess {
                log.info(
                    "Launched distributed data generator: scenario={}, ops={}, per-pod={} ops/sec, replicas={}, output -> {}",
                    config.generatorScenario, runtimeOps, perPodOps, replicas, logFile,
                )
            }
            .getOrNull()
    }

    /**
     * Tear down every generator run for this scenario by label. Distributed runs carry
     * `gridgain.com/scenario=<scenario>` on their Deployment, ConfigMap, ServiceAccount,
     * Role, and RoleBinding (verified against the plugin's manifest templates), so one
     * label-scoped delete cleans up the current run AND any orphans from prior runs —
     * the pile-up the old best-effort stop allowed. Best-effort: kubectl failures are
     * logged, not fatal (a stop must never wedge the control plane).
     */
    private fun stopAllRuns() {
        val selector = "gridgain.com/scenario=${config.generatorScenario}"
        val pb = ProcessBuilder(
            "kubectl", "delete",
            "deployment,configmap,serviceaccount,role,rolebinding",
            "-l", selector,
            "-n", config.generatorNamespace,
            "--ignore-not-found",
        ).redirectErrorStream(true)
        runCatching {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(STOP_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                log.warn("kubectl delete for generator stop timed out after {}s.", STOP_TIMEOUT_SECONDS)
            } else if (output.isNotBlank()) {
                log.info("Generator stop (selector={} ns={}): {}", selector, config.generatorNamespace, output.trim())
            }
        }.onFailure {
            log.warn(
                "Best-effort generator stop failed (kubectl delete -l {} -n {}): {}",
                selector, config.generatorNamespace, it.message,
            )
        }
    }

    internal companion object {
        // The pods stepper's ceiling. Pods beyond the generator pool's autoscale budget just sit
        // Pending until a node joins; 30 matches the demo's ~15k-ops headroom target.
        const val MAX_PODS = 30

        // Per-pod rate written to the runtime ops.yaml. Deliberately far above any single pod's
        // achievable rate so the generator's RateLimiter never throttles — each pod runs at its
        // GG-round-trip latency ceiling. Pods, not rate, are the lever (see setPods).
        const val PER_POD_CEILING = 100_000

        // Rough single-pod throughput (latency-capped), used only to derive a display estimate for
        // GeneratorState.targetOpsPerSecond. The real measured rate comes from the metrics panel.
        const val APPROX_OPS_PER_POD = 500

        const val DEFAULT_PARTITION_COUNT = 64
        const val STOP_TIMEOUT_SECONDS = 30L

        // ISO-8601 duration for a load run: long enough to be "continuous" for any demo or load test
        // (the presenter stops it explicitly via 0 pods, which tears the Deployment down).
        const val LOAD_DURATION = "PT24H"

        /** Pure decision: pods + per-pod rate from a requested pod count. 0 or negative → stopped. */
        internal fun planPods(requestedPods: Int): LoadPlan =
            if (requestedPods <= 0) {
                LoadPlan(perPodOps = 0, replicas = 0, running = false)
            } else {
                LoadPlan(perPodOps = PER_POD_CEILING, replicas = requestedPods.coerceAtMost(MAX_PODS), running = true)
            }
    }
}

/** The launch decision for [GeneratorControlService.setPods] — extracted so it can be unit-tested. */
internal data class LoadPlan(val perPodOps: Int, val replicas: Int, val running: Boolean)
