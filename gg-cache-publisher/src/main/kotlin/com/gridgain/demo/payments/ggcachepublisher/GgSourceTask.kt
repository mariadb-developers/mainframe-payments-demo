package com.gridgain.demo.payments.ggcachepublisher

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.apache.ignite.Ignition
import org.apache.ignite.binary.BinaryObject
import org.apache.ignite.cache.query.ContinuousQuery
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.data.SchemaBuilder
import org.apache.kafka.connect.data.Struct
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
    // Connect schemas, derived once per table from JDBC metadata and reused. The
    // off-the-shelf Debezium JDBC sink requires every record to carry a non-null
    // schema (it NPEs in SinkRecordDescriptor.isFlattened otherwise), so the
    // value/key must be Structs, not schemaless Maps. (poll() runs single-threaded.)
    private val rowSchemaByTable = mutableMapOf<String, Schema>()
    private val envelopeSchemaByTable = mutableMapOf<String, Schema>()
    private val keySchemaByTable = mutableMapOf<String, Schema>()

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

        // Read the current row as a schema-bearing Struct. null on the race where
        // it was already deleted; we don't read at all for op='d' (deletes carry no
        // `source` and route out below, as before).
        val row = if (e.opType == "d") null else readRowStruct(tableName, keyMap)

        // Route by source so each downstream sink subscribes to exactly its origin.
        val rowSource = row?.after?.getString("source")
        val topicPrefix = when (rowSource) {
            "gg" -> topicPrefixGg
            "mf" -> topicPrefixMf
            else -> {
                log.debug(
                    "Skipping cache event on {} key={} op={} source='{}' (no topic-prefix mapping)",
                    e.cacheName, e.key, e.opType, rowSource,
                )
                return null
            }
        }
        val r = row!! // non-null here: rowSource resolved to "gg"/"mf"
        val topic = "$topicPrefix.public.$tableName"

        // Debezium-shaped envelope, now SCHEMA-BEARING so the off-the-shelf
        // debezium-connector-jdbc sink accepts it (it NPEs on a null valueSchema).
        // `before` stays null — we re-read current state and deletes are skipped
        // above; correlation_id rides the header, not the payload.
        val envSchema = envelopeSchema(tableName, r.schema)
        val envelope = Struct(envSchema)
            .put("op", e.opType)
            .put("after", r.after)
            .put("ts_ms", e.tsMs)

        val (keySchema, keyStruct) = keyStructFor(tableName, keyMap)

        val headers = ConnectHeaders()
        if (r.correlationId != null) {
            headers.add("correlation-id", r.correlationId, Schema.STRING_SCHEMA)
        }

        return SourceRecord(
            /* sourcePartition  = */ mapOf("cache" to e.cacheName),
            /* sourceOffset     = */ mapOf("ts" to e.tsMs),
            /* topic            = */ topic,
            /* partition        = */ null,
            /* keySchema        = */ keySchema,
            /* key              = */ keyStruct,
            /* valueSchema      = */ envSchema,
            /* value            = */ envelope,
            /* timestamp        = */ e.tsMs,
            /* headers          = */ headers,
        )
    }

    /**
     * Fetches the current row identified by [keyMap] (column -> value for every
     * primary-key column) as a schema-bearing [RowResult]. Returns null if the row
     * has already been removed (race between the CQ notification and this SELECT);
     * the caller skips the event.
     *
     * The `correlation_id` column is lifted out of the [Struct] (it rides the Kafka
     * record header, never the JDBC-sink-bound payload — see class doc) and returned
     * separately on [RowResult.correlationId].
     */
    private fun readRowStruct(table: String, keyMap: Map<String, Any?>): RowResult? {
        if (keyMap.isEmpty()) return null
        val where = keyMap.keys.joinToString(" AND ") { "$it = ?" }
        val sql = "SELECT * FROM PUBLIC.$table WHERE $where"
        val c = jdbc ?: return null
        return c.prepareStatement(sql).use { ps ->
            keyMap.values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@use null
                val schema = rowSchema(table, rs)
                val struct = Struct(schema)
                val meta = rs.metaData
                var correlationId: String? = null
                for (i in 1..meta.columnCount) {
                    val col = meta.getColumnLabel(i).lowercase()
                    val raw = rs.getObject(i)
                    if (col == CORRELATION_FIELD) {
                        correlationId = raw?.toString()
                        continue
                    }
                    struct.put(col, coerce(raw, meta.getColumnType(i)))
                }
                RowResult(schema, struct, correlationId)
            }
        }
    }

    /**
     * Connect value schema for a row of [table], derived once from JDBC metadata
     * and cached. `correlation_id` is excluded (it rides the record header). The
     * schema is `.optional()` so the envelope's `before`/`after` fields may be null
     * (we never populate `before`, and deletes are skipped before we read).
     */
    private fun rowSchema(table: String, rs: ResultSet): Schema = rowSchemaByTable.getOrPut(table) {
        val meta = rs.metaData
        val b = SchemaBuilder.struct().name("gg.public.$table.Value").optional()
        for (i in 1..meta.columnCount) {
            val col = meta.getColumnLabel(i).lowercase()
            if (col == CORRELATION_FIELD) continue
            b.field(col, connectSchema(meta.getColumnType(i)))
        }
        b.build()
    }

    /**
     * Maps a `java.sql.Types` constant to the Connect schema the JDBC sink expects.
     * All columns are OPTIONAL so nullable columns (e.g. `transaction.product_id`)
     * round-trip. TIMESTAMP becomes the Connect logical Timestamp type so the
     * downstream debezium-connector-jdbc sink writes a real DB TIMESTAMP rather than
     * an epoch-millis bigint.
     */
    internal fun connectSchema(jdbcType: Int): Schema = when (jdbcType) {
        java.sql.Types.BIGINT -> Schema.OPTIONAL_INT64_SCHEMA
        java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> Schema.OPTIONAL_INT32_SCHEMA
        java.sql.Types.BOOLEAN, java.sql.Types.BIT -> Schema.OPTIONAL_BOOLEAN_SCHEMA
        java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL,
        java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> Schema.OPTIONAL_FLOAT64_SCHEMA
        java.sql.Types.TIMESTAMP -> org.apache.kafka.connect.data.Timestamp.builder().optional().build()
        else -> Schema.OPTIONAL_STRING_SCHEMA
    }

    /**
     * Coerces a raw JDBC value into the Java type the matching [connectSchema]
     * declares, so `Struct.put` validation passes. Connect's Timestamp logical type
     * wants a `java.util.Date`, which `java.sql.Timestamp` already satisfies.
     */
    internal fun coerce(raw: Any?, jdbcType: Int): Any? {
        if (raw == null) return null
        return when (jdbcType) {
            java.sql.Types.BIGINT -> (raw as Number).toLong()
            java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> (raw as Number).toInt()
            java.sql.Types.BOOLEAN, java.sql.Types.BIT -> raw as Boolean
            java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL,
            java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> (raw as Number).toDouble()
            java.sql.Types.TIMESTAMP -> raw as java.util.Date
            else -> raw.toString()
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

    /**
     * Debezium-shaped envelope schema for [table], cached per table. Named
     * `*.Envelope` ON PURPOSE: the debezium-connector-jdbc sink calls
     * `SinkRecordDescriptor.isFlattened()`, which returns false only when the value
     * schema name ends in ".Envelope" — that branch reads `op`/`after` natively and
     * writes only the `after` struct's fields as columns. `before` is declared (for
     * Debezium parity) but never populated; we skip deletes upstream.
     */
    internal fun envelopeSchema(table: String, rowSchema: Schema): Schema =
        envelopeSchemaByTable.getOrPut(table) {
            SchemaBuilder.struct().name("gg.public.$table.Envelope")
                .field("before", rowSchema)
                .field("after", rowSchema)
                .field("op", Schema.OPTIONAL_STRING_SCHEMA)
                .field("ts_ms", Schema.OPTIONAL_INT64_SCHEMA)
                .build()
        }

    /**
     * Builds the record key [Struct] (and its cached schema) from [keyMap]. With the
     * sink's `primary.key.mode=record_key`, these fields become the upsert key. PK
     * columns in this data model are all BIGINT; the field type is still derived from
     * the value so a future string/int PK works without change.
     */
    internal fun keyStructFor(table: String, keyMap: Map<String, Any?>): Pair<Schema, Struct> {
        val schema = keySchemaByTable.getOrPut(table) {
            val b = SchemaBuilder.struct().name("gg.public.$table.Key")
            keyMap.forEach { (col, v) -> b.field(col, keyFieldSchema(v)) }
            b.build()
        }
        val struct = Struct(schema)
        keyMap.forEach { (col, v) -> struct.put(col, coerceKeyValue(v)) }
        return schema to struct
    }

    private fun keyFieldSchema(v: Any?): Schema = when (v) {
        is Long -> Schema.INT64_SCHEMA
        is Int -> Schema.INT32_SCHEMA
        is Boolean -> Schema.BOOLEAN_SCHEMA
        else -> Schema.STRING_SCHEMA
    }

    private fun coerceKeyValue(v: Any?): Any? = when (v) {
        is Long, is Int, is Boolean -> v
        else -> v?.toString()
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

    /**
     * A row read back from GG as a Connect [Struct], with its [schema] and the
     * (header-bound, non-payload) `correlation_id` lifted out. [correlationId] is
     * null when the row carries no correlation id (e.g. a mainframe-originated row).
     */
    private data class RowResult(
        val schema: Schema,
        val after: Struct,
        val correlationId: String?,
    )

    companion object {
        // GG-only column. The publisher lifts the value onto the Kafka record
        // header and strips it from the payload, so downstream JDBC sinks
        // never see it as a row column. See payments-schema.yaml for the
        // GG-side declaration; Postgres / MariaDB schemas don't carry it.
        const val CORRELATION_FIELD = "correlation_id"
    }
}
