package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.GeneratorState
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Pods-only load control for the data generator, which is deployed as a first-class element
 * (`data_generators.payments-load`) on its own dedicated, autoscaling node pool. The element is
 * deployed **staged at 0 pods** (`deployDataGenerator`); this service just scales its Deployment
 * up and down with `kubectl scale`. The per-pod write rate is the element's own `per_pod_rate`
 * (0 = unbounded), so the backend no longer templates a runtime ops.yaml.
 *
 * Pods are the only lever: a single pod is capped by GG round-trip latency (~500 ops/sec), so total
 * throughput ≈ pods × that ceiling, and the way to push GG is to add pods. Each pod lands on the
 * element's dedicated `wp-<name>` pool (placement is baked into the Deployment by the toolkit), off
 * the GG/DB/CDC nodes, so the load tier scales independently of the grid.
 */
class GeneratorControlService(private val config: UiConfig) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = Any()

    @Volatile
    private var state = GeneratorState(targetOpsPerSecond = 0, replicas = 0, running = false)

    fun state(): GeneratorState = state

    /**
     * Scale the generator element's Deployment to [requestedPods] (0 = off). Requires the element
     * to be deployed already (`deployDataGenerator`); this only adjusts the replica count, which is
     * fast (k8s spins the pods up asynchronously). No upper clamp — k8s scheduling + the generator
     * pool's autoscale max are the real ceiling; pods that exceed the pool's fit sit Pending until
     * a node joins.
     */
    fun setPods(requestedPods: Int): GeneratorState = synchronized(lock) {
        val plan = planPods(requestedPods)
        scaleDeployment(plan.replicas)
        state = GeneratorState(
            targetOpsPerSecond = plan.replicas * APPROX_OPS_PER_POD, // display estimate; pods are the real knob
            replicas = plan.replicas,
            running = plan.running,
        )
        log.info("Generator scaled to {} pod(s) (running={}).", plan.replicas, plan.running)
        return@synchronized state
    }

    /** `kubectl scale` the element Deployment. Throws with remediation if the element isn't deployed. */
    private fun scaleDeployment(replicas: Int) {
        val deployment = config.generatorDeploymentName
        val ns = config.generatorNamespace
        val output = StringBuilder()
        val ok = runCatching {
            val proc = ProcessBuilder(
                "kubectl", "scale", "deployment/$deployment",
                "-n", ns, "--replicas=$replicas",
            ).redirectErrorStream(true).start()
            output.append(proc.inputStream.bufferedReader().readText())
            if (!proc.waitFor(SCALE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                proc.destroyForcibly(); false
            } else {
                proc.exitValue() == 0
            }
        }.getOrElse { e -> output.append(e.message); false }

        if (!ok) {
            throw IllegalStateException(
                "Failed to scale generator deployment '$deployment' in namespace '$ns' to $replicas pod(s). " +
                    "Ensure the data-generator element is deployed first " +
                    "(./gradlew deployDataGenerator -PdataGeneratorName=payments-load). " +
                    "kubectl output: ${output.toString().trim()}",
            )
        }
        log.info("kubectl scale deployment/{} -n {} --replicas={} ok: {}", deployment, ns, replicas, output.toString().trim())
    }

    internal companion object {
        // Rough single-pod throughput (latency-capped), used only to derive a display estimate for
        // GeneratorState.targetOpsPerSecond. The real measured rate comes from the metrics panel.
        const val APPROX_OPS_PER_POD = 500

        const val SCALE_TIMEOUT_SECONDS = 30L

        /** Pure decision: pod count + running flag from a requested count. 0 or negative → stopped. */
        internal fun planPods(requestedPods: Int): LoadPlan =
            if (requestedPods <= 0) {
                LoadPlan(replicas = 0, running = false)
            } else {
                LoadPlan(replicas = requestedPods, running = true)
            }
    }
}

/** The scale decision for [GeneratorControlService.setPods] — extracted so it can be unit-tested. */
internal data class LoadPlan(val replicas: Int, val running: Boolean)
