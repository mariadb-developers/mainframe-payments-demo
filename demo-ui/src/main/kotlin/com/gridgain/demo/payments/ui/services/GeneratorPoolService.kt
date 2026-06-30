package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.PoolStatus
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * UI-side companion to the plugin's `warmupDataGeneratorPool` task.
 *
 * The actual resize lives in the toolkit (see
 * `gridgain-demo-gradle-plugin/.../WarmupDataGeneratorPoolAction.kt`) — the
 * plugin knows the pool naming convention, the cluster/project/region, and
 * how to shell gcloud. This service is purely the demo's UX glue:
 *
 * - `status()` counts the pool's current node count via `kubectl get nodes
 *   -l cloud.google.com/gke-nodepool=<pool> -o name`. Pure observation, fast.
 *   Uses [UiConfig.generatorPoolName] (a display threshold, not a deploy
 *   parameter) and [UiConfig.generatorPoolMaxNodes] (so the UI knows when to
 *   flip from "scaling" to "warm").
 * - `warmup()` shells `./gradlew warmupDataGeneratorPool
 *   -PdataGeneratorName=<name>` from the demo project's working directory.
 *   The plugin task handles all the gcloud invocation and returns once the
 *   async resize is submitted. Subsequent `status()` polls let the UI watch
 *   the node count climb.
 *
 * After the demo, the autoscaler scales the pool back to `min_nodes` when
 * no pods need the capacity (default idle scale-down ~10 min) — no "cool
 * down" button needed.
 */
class GeneratorPoolService(private val config: UiConfig) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun status(): PoolStatus {
        val (out, ok) = runProcess(
            args = kubectlCountCommand(config.generatorPoolName),
            workingDir = null,
            timeoutS = KUBECTL_TIMEOUT_S,
        )
        val current = if (ok) parseNodeCount(out) else 0
        if (!ok) {
            log.warn("Failed to count generator pool nodes; reporting 0. kubectl output: {}", out.trim())
        }
        return PoolStatus(
            poolName = config.generatorPoolName,
            currentNodes = current,
            maxNodes = config.generatorPoolMaxNodes,
            state = classify(current, config.generatorPoolMaxNodes),
        )
    }

    /**
     * Kicks off the plugin's async resize. Returns the *current* status (which
     * still shows the pre-warmup count) — the UI polls `status()` to watch
     * the count climb. Throws on Gradle/plugin failure with remediation.
     */
    fun warmup(): PoolStatus {
        val cmd = gradleWarmupCommand(config.warmupDataGeneratorName)
        val cwd = config.projectDirectory.toFile()
        log.info(
            "Warming up generator pool for '{}' via: {} (cwd={})",
            config.warmupDataGeneratorName, cmd.joinToString(" "), cwd,
        )
        val (out, ok) = runProcess(args = cmd, workingDir = cwd, timeoutS = GRADLE_TIMEOUT_S)
        if (!ok) {
            throw IllegalStateException(
                "Failed to invoke warmupDataGeneratorPool for '${config.warmupDataGeneratorName}': ${out.trim()}. " +
                    "Ensure ./gradlew is on the PATH for the demo project, gcloud is authenticated, " +
                    "and the data generator is defined in demo-config.yaml.",
            )
        }
        log.info("warmupDataGeneratorPool task completed: {}", out.lineSequence().last { it.isNotBlank() }.trim())
        return status()
    }

    private fun runProcess(args: List<String>, workingDir: java.io.File?, timeoutS: Long): Pair<String, Boolean> {
        val output = StringBuilder()
        val ok = runCatching {
            val proc = ProcessBuilder(args).redirectErrorStream(true).also {
                if (workingDir != null) it.directory(workingDir)
            }.start()
            output.append(proc.inputStream.bufferedReader().readText())
            if (!proc.waitFor(timeoutS, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        }.getOrElse { e ->
            output.append(e.message ?: e.javaClass.simpleName)
            false
        }
        return output.toString() to ok
    }

    internal companion object {
        const val KUBECTL_TIMEOUT_S = 15L
        // Gradle's startup adds ~10s; the plugin's warmup task itself is fast
        // (async gcloud submit). 60s is generous but covers a cold daemon.
        const val GRADLE_TIMEOUT_S = 90L
        const val GKE_POOL_LABEL = "cloud.google.com/gke-nodepool"

        internal fun kubectlCountCommand(pool: String): List<String> = listOf(
            "kubectl", "get", "nodes",
            "-l", "$GKE_POOL_LABEL=$pool",
            "-o", "name",
        )

        /**
         * Builds the `./gradlew warmupDataGeneratorPool -PdataGeneratorName=<name>`
         * invocation. Pure for unit-testing the contract; the plugin's own
         * tests cover the gcloud command shape.
         */
        internal fun gradleWarmupCommand(dataGeneratorName: String): List<String> = listOf(
            "./gradlew", "warmupDataGeneratorPool",
            "-PdataGeneratorName=$dataGeneratorName",
            "--console=plain",
        )

        /** Count `node/<name>` lines in `kubectl get ... -o name` output. */
        internal fun parseNodeCount(stdout: String): Int =
            stdout.lineSequence().count { it.trim().startsWith("node/") }

        /**
         * cold (≤1 nodes — pool is idle), warm (≥max nodes — ready for demo),
         * scaling (in between — the resize is in flight, watching this poll
         * lets the UI animate the warmup).
         */
        internal fun classify(current: Int, max: Int): String = when {
            current <= 1 -> "cold"
            current >= max -> "warm"
            else -> "scaling"
        }
    }
}
