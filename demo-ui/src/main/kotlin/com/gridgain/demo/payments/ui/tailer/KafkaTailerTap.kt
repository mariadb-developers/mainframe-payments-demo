package com.gridgain.demo.payments.ui.tailer

import com.fasterxml.jackson.databind.ObjectMapper
import com.gridgain.demo.payments.ui.model.TailerEvent
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Properties
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

/**
 * Kafka consumer that mirrors row-change events from one or more topics onto a
 * named tailer flow. One instance per UI tailer panel:
 *  - `cdc`            → mainframe-to-gg.public.*           (filter source='mf')
 *  - `gg-to-postgres` → from-gg.public.*                   (no filter; only GG-originated events flow here)
 *  - `gg-to-mariadb`  → from-(gg|mf).public.*              (no filter; this is everything MariaDB writes)
 *
 * The optional [sourceFilter] is a string match against the `after.source`
 * (or `before.source`) field of the Debezium envelope — used by the `cdc`
 * tailer so users see only the events the GG sink actually applied to GG, not
 * the GG-originated rows that return to GG-bound topics via the round-trip
 * through Postgres + Debezium.
 *
 * The `correlation-id` Kafka record HEADER is lifted onto [TailerEvent.correlationId]
 * so the React UI can co-highlight matching events that fanned out from a
 * single GG write.
 */
class KafkaTailerTap(
    private val kafkaBootstrapServers: String,
    private val topicPatterns: List<String>,
    private val tailerSource: String,
    private val sourceFilter: String?,
    private val tailerService: TailerService,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val thread = Thread(::run, "kafka-tailer-tap-$tailerSource").apply { isDaemon = true }

    @Volatile private var consumer: KafkaConsumer<String, String>? = null
    @Volatile private var stopped = false

    fun start() {
        thread.start()
    }

    private fun run() {
        // Kafka may not be reachable yet (e.g. dev workflow: backend starts
        // before the payments-proxy port-forward is up). Retry-with-backoff until shutdown.
        while (!stopped) {
            try {
                pollLoop()
            } catch (_: WakeupException) {
                return
            } catch (e: Exception) {
                log.warn("Kafka tailer '{}' error (retrying in 5s): {}", tailerSource, e.message)
                Thread.sleep(5_000)
            }
        }
    }

    private fun pollLoop() {
        val props = Properties().apply {
            put("bootstrap.servers", kafkaBootstrapServers)
            put("group.id", "mainframe-payments-ui-tailer-$tailerSource-${java.util.UUID.randomUUID()}")
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("auto.offset.reset", "latest")
            put("enable.auto.commit", "true")
            put("reconnect.backoff.ms", "1000")
            put("reconnect.backoff.max.ms", "10000")
        }
        val combined = topicPatterns.joinToString("|") { "($it)" }
        val pattern = java.util.regex.Pattern.compile(combined)
        val c = KafkaConsumer<String, String>(props)
        consumer = c
        c.use { kc ->
            kc.subscribe(pattern)
            while (!stopped) {
                val records = kc.poll(Duration.ofMillis(500))
                for (rec in records) {
                    val event = toTailerEvent(rec) ?: continue
                    tailerService.publish(tailerSource, event)
                }
            }
        }
    }

    private fun toTailerEvent(rec: org.apache.kafka.clients.consumer.ConsumerRecord<String, String>): TailerEvent? {
        val value = rec.value()
        if (value.isNullOrBlank()) return null
        val root = runCatching { mapper.readTree(value) }.getOrNull() ?: return null
        // Debezium source topics are unwrapped; gg-cache-publisher topics come
        // through Connect's JsonConverter (schemas.enable=true) wrapped as
        // {"schema":..,"payload":..}. Unwrap so both parse the same.
        val node = unwrapConnectEnvelope(root)
        val after = node.path("after")
        val before = node.path("before")
        val row = if (!after.isMissingNode && !after.isNull) after else before

        @Suppress("UNCHECKED_CAST")
        val payload = mapper.convertValue(row, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        val rowSource = payload["source"]?.toString()

        if (sourceFilter != null && rowSource != sourceFilter) {
            return null
        }

        val op = node.path("op").asText("?")
        val operation = when (op) {
            "c" -> "INSERT"
            "u" -> "UPDATE"
            "d" -> "DELETE"
            "r" -> "SNAPSHOT"
            else -> op
        }
        val tableName = rec.topic().substringAfterLast('.')
        val key = payload["${tableName}_id"]?.toString()
            ?: payload.values.firstOrNull()?.toString()
            ?: "?"
        val tsMs = node.path("ts_ms").asLong(System.currentTimeMillis())
        val correlationId = rec.headers().lastHeader("correlation-id")
            ?.value()?.let { String(it, StandardCharsets.UTF_8) }
        return TailerEvent(
            timestamp = Instant.ofEpochMilli(tsMs),
            source = tailerSource,
            operation = operation,
            table = tableName,
            key = key,
            correlationId = correlationId,
            payload = payload,
        )
    }

    override fun close() {
        stopped = true
        consumer?.wakeup()
    }

    companion object {
        /**
         * Returns the change-event envelope, unwrapping Kafka Connect's
         * JsonConverter `{"schema":..,"payload":..}` wrapper when present (the
         * gg-cache-publisher topics carry it; the Debezium source topics don't).
         * Requires BOTH `schema` and `payload` so a row that merely has a column
         * named `payload` isn't mistaken for the wrapper.
         */
        fun unwrapConnectEnvelope(root: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode =
            if (root.has("schema") && root.has("payload")) root.path("payload") else root
    }
}
