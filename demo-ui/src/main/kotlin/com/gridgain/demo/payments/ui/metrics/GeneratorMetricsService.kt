package com.gridgain.demo.payments.ui.metrics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Duration
import java.util.Properties
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

/**
 * Consumes the data generator's live-metrics topic (throughput + GridGain execution latency,
 * published ~1s by its `LiveMetricsReporter`/`KafkaMetricsSink`) and republishes each snapshot on
 * a hot flow for the `/api/metrics` WebSocket. Kafka transport means it works regardless of where
 * the generator runs (in-cluster Job or local fork) — no shared filesystem.
 *
 * The generator emits a final `active=false` snapshot on clean stop, so the graphs zero out
 * between runs. The staleness watchdog is a backstop: if the generator dies WITHOUT that final
 * message, an idle snapshot is emitted once so the UI doesn't freeze on the last live value.
 * `replay = 1` gives a newly-connected WebSocket the latest snapshot immediately.
 *
 * Mirrors [com.gridgain.demo.payments.ui.tailer.KafkaTailerTap]'s consumer pattern (own thread,
 * retry-with-backoff, latest offset, unique group so every backend restart reads only live data).
 */
class GeneratorMetricsService(
    private val kafkaBootstrapServers: String,
    private val topic: String,
    private val stalenessMs: Long = 5_000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val rwRatio: String? = null,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().registerKotlinModule() // reads the generator's camelCase JSON
    private val flow = MutableSharedFlow<MetricsSnapshot>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val thread = Thread(::run, "generator-metrics-consumer").apply { isDaemon = true }

    @Volatile private var consumer: KafkaConsumer<String, String>? = null
    @Volatile private var stopped = false

    // Latest snapshot per generator pod (keyed by the pod's runId) plus the wall-clock time it
    // arrived, so a pod that dies without a final snapshot can be evicted. The UI sees the
    // aggregate of these — total throughput across pods, not whichever pod reported last. Touched
    // only by the single consumer thread, so no synchronization is needed.
    private data class TimedSnapshot(val snapshot: MetricsSnapshot, val receivedAtMs: Long)
    private val liveByRun = LinkedHashMap<String, TimedSnapshot>()

    fun subscribe(): SharedFlow<MetricsSnapshot> = flow.asSharedFlow()

    fun start() {
        thread.start()
    }

    private fun run() {
        // Kafka may not be reachable yet (dev: backend starts before the port-forward). Retry.
        while (!stopped) {
            try {
                pollLoop()
            } catch (_: WakeupException) {
                return
            } catch (e: Exception) {
                log.warn("Generator metrics consumer error (retrying in 5s): {}", e.message)
                Thread.sleep(5_000)
            }
        }
    }

    private fun pollLoop() {
        val props = Properties().apply {
            put("bootstrap.servers", kafkaBootstrapServers)
            put("group.id", "mainframe-payments-ui-metrics-${java.util.UUID.randomUUID()}")
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("auto.offset.reset", "latest")
            put("enable.auto.commit", "true")
            put("reconnect.backoff.ms", "1000")
            put("reconnect.backoff.max.ms", "10000")
        }
        val c = KafkaConsumer<String, String>(props)
        consumer = c
        c.use { kc ->
            kc.subscribe(listOf(topic))
            while (!stopped) {
                val records = kc.poll(Duration.ofMillis(500))
                val now = clockMs()
                var changed = false
                for (rec in records) {
                    val snap = parse(rec.value()) ?: continue
                    // Each generator pod publishes its own snapshot under a distinct runId. Track the
                    // latest per pod; a pod's clean-stop snapshot (active=false) retires it from the
                    // aggregate so the graphs shed that pod's contribution.
                    if (snap.active) liveByRun[snap.runId] = TimedSnapshot(snap, now)
                    else liveByRun.remove(snap.runId)
                    changed = true
                }
                // Backstop: evict pods that died WITHOUT a final inactive snapshot (no fresh message
                // within stalenessMs) so a crashed pod stops inflating the aggregate.
                if (liveByRun.values.removeIf { now - it.receivedAtMs > stalenessMs }) changed = true

                // Emit the combined view whenever the live set changes. When the last pod goes away
                // (clean stop or eviction) this emits idle() once, then stays quiet until live again.
                if (changed) {
                    flow.tryEmit(
                        if (liveByRun.isEmpty()) idle()
                        else aggregate(liveByRun.values.map { it.snapshot }, now),
                    )
                }
            }
        }
    }

    /**
     * Combines the latest snapshot from each live generator pod into one. Throughput, total ops,
     * errors and target are summed — so adding pods raises the displayed rate toward the slider's
     * total target instead of showing a single pod's share. Latency is throughput-weighted (a busier
     * pod dominates the blended figure), falling back to a simple mean before any throughput exists.
     * A single pod passes through unchanged (its real runId is kept). Callers pass a non-empty list;
     * an empty live set emits [idle] instead.
     */
    internal fun aggregate(snapshots: List<MetricsSnapshot>, nowMs: Long): MetricsSnapshot {
        if (snapshots.size == 1) return snapshots[0].copy(updatedAtMs = nowMs, rwRatio = rwRatio)

        val observedTps = snapshots.sumOf { it.observedTps }
        val avgLatencyMs = if (observedTps > 0.0) {
            snapshots.sumOf { it.avgLatencyMs * it.observedTps } / observedTps
        } else {
            snapshots.map { it.avgLatencyMs }.average()
        }
        return MetricsSnapshot(
            updatedAtMs = nowMs,
            observedTps = observedTps,
            avgLatencyMs = avgLatencyMs,
            totalOps = snapshots.sumOf { it.totalOps },
            errorCount = snapshots.sumOf { it.errorCount },
            targetTps = snapshots.sumOf { it.targetTps },
            runId = "${snapshots.size} pods",
            active = snapshots.any { it.active },
            rwRatio = rwRatio,
        )
    }

    /** Parses one Kafka value into a snapshot; null on blank/malformed payloads. */
    internal fun parse(json: String?): MetricsSnapshot? {
        if (json.isNullOrBlank()) return null
        return runCatching { mapper.readValue<MetricsSnapshot>(json) }.getOrNull()
    }

    private fun idle() = MetricsSnapshot(
        updatedAtMs = clockMs(),
        observedTps = 0.0,
        avgLatencyMs = 0.0,
        totalOps = 0L,
        errorCount = 0L,
        targetTps = 0.0,
        runId = "",
        active = false,
        rwRatio = rwRatio,
    )

    override fun close() {
        stopped = true
        consumer?.wakeup()
    }
}
