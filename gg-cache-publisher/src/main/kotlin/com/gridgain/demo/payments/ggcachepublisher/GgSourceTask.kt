package com.gridgain.demo.payments.ggcachepublisher

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.apache.ignite.Ignition
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.cache.query.ContinuousQuery
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.header.ConnectHeaders
import org.apache.kafka.connect.source.SourceRecord
import org.apache.kafka.connect.source.SourceTask
import org.slf4j.LoggerFactory

/**
 * Source task: subscribes to GG via thin-client ContinuousQuery and forwards
 * every cache event onto a Kafka topic per cache. The "value" payload follows
 * a Debezium-shaped envelope ({op, before, after, source.table, ts_ms}) so the
 * downstream debezium-connector-jdbc sinks can consume gg.public.* topics with
 * the same connector class as our mainframe-to-gg.public.* topics.
 *
 * Cache values are read back via JDBC after each CQ notification — the thin
 * client returns SQL-table cache values as `BinaryObject`, and decoding that
 * generically inside a connector classloader is more friction than just
 * issuing a small `SELECT * WHERE <pk_col> = ?`. The CQ provides the
 * notification + key; the SQL roundtrip provides the column values. Race
 * window: if the row is mutated/deleted between the notification and the
 * SELECT, we get a more recent (or zero) state — that's the correct
 * eventually-consistent semantic.
 *
 * The `_correlation_id` field — written into the cache value Map by the demo
 * backend at GG-write time — is lifted onto the Kafka record's
 * `correlation-id` HEADER and excluded from the published payload so the JDBC
 * sinks don't try to write it as a column. CLAUDE.md §5 uses these headers
 * to co-highlight the tailer events that fan out from a single GG write.
 */
class GgSourceTask : SourceTask() {

    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var topicPrefixGg: String
    private lateinit var topicPrefixMf: String
    private lateinit var caches: List<String>
    private lateinit var jdbcUrl: String

    @Volatile private var client: IgniteClient? = null
    @Volatile private var jdbc: Connection? = null
    private val pending = LinkedBlockingQueue<PendingEvent>()
    private val pkColsByTable = mutableMapOf<String, List<String>>()

    override fun version(): String = "0.0.1-SNAPSHOT"

    override fun start(props: Map<String, String>) {
        val addresses = requireNotNull(props[GgSourceConfig.GG_CLIENT_ADDRESSES]) {
            "Missing ${GgSourceConfig.GG_CLIENT_ADDRESSES}"
        }
        topicPrefixGg = props[GgSourceConfig.TOPIC_PREFIX_GG] ?: GgSourceConfig.TOPIC_PREFIX_GG_DEFAULT
        topicPrefixMf = props[GgSourceConfig.TOPIC_PREFIX_MF] ?: GgSourceConfig.TOPIC_PREFIX_MF_DEFAULT
        caches = requireNotNull(props[GgSourceConfig.GG_CACHES]) {
            "Missing ${GgSourceConfig.GG_CACHES}"
        }.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // Use the FIRST address for the JDBC URL — thin JDBC doesn't support
        // multi-address load balancing in 8.9.18.
        val first = addresses.split(",").first().trim()
        jdbcUrl = "jdbc:ignite:thin://$first"

        Class.forName("org.apache.ignite.IgniteJdbcThinDriver")

        val cfg = ClientConfiguration()
            .setAddresses(*addresses.split(",").map { it.trim() }.toTypedArray())
        client = Ignition.startClient(cfg)
        jdbc = DriverManager.getConnection(jdbcUrl).apply {
            schema = "PUBLIC"
            autoCommit = true
        }

        for (cacheName in caches) {
            registerContinuousQuery(cacheName)
        }
        log.info(
            "GgSourceTask started; watching caches={} topicPrefix.gg={} topicPrefix.mf={}",
            caches, topicPrefixGg, topicPrefixMf,
        )
    }

    private fun registerContinuousQuery(cacheName: String) {
        // withKeepBinary() — for SQL-backed caches, GG auto-generates a Java
        // class name per cache (e.g. SQL_PUBLIC_TRANSACTION_<hash>). The thin
        // client's BinaryContext tries to Class.forName(...) those before
        // delivering the event to our listener, and our plugin classloader
        // doesn't have them. KeepBinary keeps values as BinaryObject so no
        // class lookup happens. The listener only reads the key anyway —
        // payload fields are re-read via SQL in poll().
        val cache = client!!.cache<Any, Any>(cacheName).withKeepBinary<Any, Any>()
        val cq = ContinuousQuery<Any, Any>()
        cq.localListener = javax.cache.event.CacheEntryUpdatedListener { events ->
            val now = System.currentTimeMillis()
            for (e in events) {
                val opType = when (e.eventType) {
                    javax.cache.event.EventType.CREATED -> "c"
                    javax.cache.event.EventType.UPDATED -> "u"
                    javax.cache.event.EventType.REMOVED -> "d"
                    javax.cache.event.EventType.EXPIRED -> "d"
                }
                pending.offer(PendingEvent(cacheName, opType, e.key, now))
            }
        }
        cache.query<Any>(cq, null)
        log.info("Continuous query registered on cache '{}'", cacheName)
    }

    override fun poll(): List<SourceRecord> {
        val first = pending.poll(500, TimeUnit.MILLISECONDS) ?: return emptyList()
        val batch = mutableListOf<PendingEvent>().apply {
            add(first)
            pending.drainTo(this, 99) // up to 100 per poll
        }
        val records = mutableListOf<SourceRecord>()
        for (e in batch) {
            try {
                records += toSourceRecord(e) ?: continue
            } catch (ex: Exception) {
                log.warn("Failed to build SourceRecord for {} key={}: {}", e.cacheName, e.key, ex.message)
            }
        }
        return records
    }

    private fun toSourceRecord(e: PendingEvent): SourceRecord? {
        // Cache names for SQL-backed GG tables are "SQL_PUBLIC_<TABLE>" (the GG
        // SQL engine derives them from "<schema>.<table>"). Strip the prefix so
        // downstream topic names + table names are just the table identifier.
        val tableName = e.cacheName.removePrefix("SQL_PUBLIC_").removePrefix("sql_public_").lowercase()
        // The CQ key is a single value for a single-column PK, or a composite
        // BinaryObject when the table is colocated by a composite PK (e.g. account
        // keyed by (account_id, customer_id), CLAUDE.md §6). Resolve it to a
        // column->value map so the SQL re-read and the emitted key work for both.
        val keyMap = keyValues(e.key, pkColumnsFor(tableName))

        val after = if (e.opType == "d") null else readRow(tableName, keyMap)
        val before = if (e.opType == "d") keyMap else null

        // Route by source so each downstream sink can subscribe to exactly the
        // origin it cares about without needing a value-aware Filter SMT.
        val rowSource = (after ?: before)?.get("source")?.toString()
        val topicPrefix = when (rowSource) {
            "gg" -> topicPrefixGg
            "mf" -> topicPrefixMf
            else -> {
                log.debug(
                    "Skipping cache event on {} key={} with unknown source='{}' " +
                        "(no topic-prefix mapping; row was probably written without a source value)",
                    e.cacheName, e.key, rowSource,
                )
                return null
            }
        }
        val topic = "$topicPrefix.public.$tableName"

        // Strip correlation_id from the payload — it travels in the Kafka
        // record header so the JDBC sinks don't try to write it as a column.
        val correlationId = after?.get(CORRELATION_FIELD)?.toString()
        val cleanAfter = after?.filterKeys { it != CORRELATION_FIELD }

        val envelope = linkedMapOf<String, Any?>(
            "op" to e.opType,
            "before" to before,
            "after" to cleanAfter,
            "ts_ms" to e.tsMs,
        )

        val headers = ConnectHeaders()
        if (correlationId != null) {
            headers.add("correlation-id", correlationId, Schema.STRING_SCHEMA)
        }

        return SourceRecord(
            /* sourcePartition  = */ mapOf("cache" to e.cacheName),
            /* sourceOffset     = */ mapOf("ts" to e.tsMs),
            /* topic            = */ topic,
            /* partition        = */ null,
            /* keySchema        = */ null,
            /* key              = */ keyMap,
            /* valueSchema      = */ null,
            /* value            = */ envelope,
            /* timestamp        = */ e.tsMs,
            /* headers          = */ headers,
        )
    }

    /**
     * Fetches the current row identified by [keyMap] (column -> value for every
     * primary-key column). Returns null if the row has already been removed (race
     * between the CQ notification and this SELECT); the caller skips the event.
     */
    private fun readRow(table: String, keyMap: Map<String, Any?>): Map<String, Any?>? {
        if (keyMap.isEmpty()) return null
        val where = keyMap.keys.joinToString(" AND ") { "$it = ?" }
        val sql = "SELECT * FROM PUBLIC.$table WHERE $where"
        val c = jdbc ?: return null
        return c.prepareStatement(sql).use { ps ->
            keyMap.values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeQuery().use { rs -> if (rs.next()) rowToMap(rs) else null }
        }
    }

    /**
     * Primary-key column names (in key order, lowercased) for [table], introspected
     * once via JDBC metadata and cached. Falls back to the `<table>_id` convention if
     * metadata is unavailable.
     */
    private fun pkColumnsFor(table: String): List<String> = pkColsByTable.getOrPut(table) {
        val found = mutableListOf<Pair<Int, String>>()
        try {
            jdbc?.metaData?.getPrimaryKeys(null, "PUBLIC", table.uppercase())?.use { rs ->
                while (rs.next()) found += rs.getInt("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        } catch (e: Exception) {
            log.warn("Could not introspect primary key for table '{}': {}", table, e.message)
        }
        if (found.isEmpty()) listOf("${table}_id") else found.sortedBy { it.first }.map { it.second.lowercase() }
    }

    /**
     * Resolves a ContinuousQuery key to a {column -> value} map. A single-column PK
     * arrives as the raw value; a composite PK arrives as a [BinaryObject] whose
     * fields are the PK columns (matched case-insensitively against [pkCols]).
     */
    private fun keyValues(key: Any, pkCols: List<String>): Map<String, Any?> {
        if (key !is BinaryObject) return mapOf(pkCols.first() to key)
        val fieldNames = key.type().fieldNames()
        return pkCols.associateWith { col ->
            val actual = fieldNames.firstOrNull { it.equals(col, ignoreCase = true) } ?: col
            key.field<Any?>(actual)
        }
    }

    private fun rowToMap(rs: ResultSet): Map<String, Any?> {
        val meta = rs.metaData
        val row = LinkedHashMap<String, Any?>(meta.columnCount)
        for (i in 1..meta.columnCount) {
            val raw = rs.getObject(i)
            // Kafka Connect's JsonConverter can't serialize java.sql.Timestamp
            // (or Date / Time) directly. Coerce to epoch milliseconds — matches
            // Debezium's default temporal handling and lets the downstream
            // debezium-connector-jdbc sinks parse it back into a DB TIMESTAMP.
            val value: Any? = when (raw) {
                is Timestamp -> raw.time
                is java.sql.Date -> raw.time
                is java.sql.Time -> raw.time
                else -> raw
            }
            row[meta.getColumnLabel(i).lowercase()] = value
        }
        return row
    }

    override fun stop() {
        try { jdbc?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        jdbc = null
        client = null
    }

    private data class PendingEvent(
        val cacheName: String,
        val opType: String,
        val key: Any,
        val tsMs: Long,
    )

    companion object {
        // GG-only column. The publisher lifts the value onto the Kafka record
        // header and strips it from the payload, so downstream JDBC sinks
        // never see it as a row column. See payments-schema.yaml for the
        // GG-side declaration; Postgres / MariaDB schemas don't carry it.
        const val CORRELATION_FIELD = "correlation_id"
    }
}
