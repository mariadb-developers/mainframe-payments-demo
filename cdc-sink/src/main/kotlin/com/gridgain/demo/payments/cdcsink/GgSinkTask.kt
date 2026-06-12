package com.gridgain.demo.payments.cdcsink

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTask
import org.slf4j.LoggerFactory

class GgSinkTask : SinkTask() {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var jdbcUrl: String
    private lateinit var schema: String
    private lateinit var dialect: Dialect
    private var sourceFilter: String? = null
    private var jdbcUsername: String? = null
    private var jdbcPassword: String? = null
    private var connection: Connection? = null
    private val pkColumnsByTable = mutableMapOf<String, List<String>>()

    enum class Dialect(val driverClass: String) {
        GG8("org.apache.ignite.IgniteJdbcThinDriver"),
        POSTGRES("org.postgresql.Driver"),
        MARIADB("org.mariadb.jdbc.Driver"),
    }

    override fun version(): String = "0.0.1-SNAPSHOT"

    override fun start(props: Map<String, String>) {
        jdbcUrl = requireNotNull(props[GgSinkConfig.GG_JDBC_URL]) { "Missing ${GgSinkConfig.GG_JDBC_URL}" }
        schema = props[GgSinkConfig.SQL_SCHEMA] ?: GgSinkConfig.SQL_SCHEMA_DEFAULT
        dialect = Dialect.valueOf((props[GgSinkConfig.DIALECT] ?: GgSinkConfig.DIALECT_DEFAULT).uppercase())
        sourceFilter = props[GgSinkConfig.SOURCE_FILTER]?.takeIf { it.isNotBlank() }
        jdbcUsername = props[GgSinkConfig.JDBC_USERNAME]?.takeIf { it.isNotBlank() }
        jdbcPassword = props[GgSinkConfig.JDBC_PASSWORD]?.takeIf { it.isNotBlank() }
        // Force-load the JDBC driver. Kafka Connect plugin classloaders don't
        // always trigger DriverManager service discovery for plugin-private
        // JARs; an explicit Class.forName ensures registration.
        Class.forName(dialect.driverClass)
        connection = openConnection()
        log.info(
            "GgSinkTask connected to {} (schema={}, dialect={}, sourceFilter={})",
            jdbcUrl, schema, dialect, sourceFilter,
        )
    }

    private fun openConnection(): Connection {
        val c = if (jdbcUsername != null) {
            DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }
        c.autoCommit = true
        try { c.schema = this.schema } catch (_: Exception) { /* not all drivers support setSchema */ }
        return c
    }

    /**
     * Returns a known-live JDBC connection, reopening it if the cached one is
     * null, closed, or stale (MariaDB / Postgres can idle-close the connection
     * after long quiet periods — without this check, the first event after a
     * lull would fail with "Connection is closed" and the task would silently
     * fail every subsequent record).
     */
    private fun connection(): Connection {
        val c = connection
        if (c == null || c.isClosed || !c.isValid(2)) {
            try { c?.close() } catch (_: Exception) {}
            connection = openConnection()
        }
        return connection!!
    }

    override fun put(records: Collection<SinkRecord>) {
        if (records.isEmpty()) return
        val c = connection()
        for (record in records) {
            try {
                applyOne(c, record)
            } catch (e: Exception) {
                // Don't fail the task on a single bad record — log and move on.
                log.warn("Failed to apply CDC record from topic={} partition={} offset={}: {}",
                    record.topic(), record.kafkaPartition(), record.kafkaOffset(), e.message, e)
            }
        }
    }

    private fun applyOne(c: Connection, record: SinkRecord) {
        val table = tableNameFromTopic(record.topic()) ?: return
        @Suppress("UNCHECKED_CAST")
        val envelope = record.value() as? Map<String, Any?> ?: return
        val op = envelope["op"] as? String ?: return
        when (op) {
            "c", "u", "r" -> {
                @Suppress("UNCHECKED_CAST")
                val after = envelope["after"] as? Map<String, Any?> ?: return
                val src = after["source"]?.toString()
                if (sourceFilter != null && src != sourceFilter) {
                    log.debug("Skipping CDC event with source='{}' on topic={} (filter={})",
                        src, record.topic(), sourceFilter)
                    return
                }
                upsert(c, table, after)
            }
            "d" -> {
                @Suppress("UNCHECKED_CAST")
                val before = envelope["before"] as? Map<String, Any?> ?: return
                val src = before["source"]?.toString()
                if (sourceFilter != null && src != null && src != sourceFilter) {
                    log.debug("Skipping CDC delete with source='{}' on topic={} (filter={})",
                        src, record.topic(), sourceFilter)
                    return
                }
                val pkColumn = "${table}_id"
                val pkValue = before[pkColumn] ?: return
                deleteByPk(c, table, pkColumn, pkValue)
            }
            else -> {
                log.debug("Skipping CDC event with op='{}' on topic={}", op, record.topic())
            }
        }
    }

    /**
     * Topic naming convention: `{prefix}.public.{table}` -> GG SQL table `{table}`.
     * Anything that doesn't match the `.public.` segment is rejected (returns null);
     * keeps Kafka Connect's internal topics from being routed.
     */
    internal fun tableNameFromTopic(topic: String): String? {
        val parts = topic.split('.')
        val publicIdx = parts.indexOf("public")
        if (publicIdx < 0 || publicIdx == parts.size - 1) return null
        return parts.last()
    }

    private fun upsert(c: Connection, table: String, row: Map<String, Any?>) {
        if (row.isEmpty()) return
        val cols = row.keys.toList()
        val placeholders = cols.joinToString(", ") { "?" }
        val keyCols = keyColumns(c, table)
        val keyList = keyCols.joinToString(", ")
        val nonKeyCols = cols.filter { it !in keyCols }
        val sql = when (dialect) {
            Dialect.GG8 -> """
                MERGE INTO $table (${cols.joinToString(", ")})
                KEY ($keyList)
                VALUES ($placeholders)
            """.trimIndent()
            Dialect.POSTGRES -> """
                INSERT INTO $table (${cols.joinToString(", ")})
                VALUES ($placeholders)
                ON CONFLICT ($keyList) DO UPDATE SET
                ${nonKeyCols.joinToString(", ") { "$it = EXCLUDED.$it" }}
            """.trimIndent()
            Dialect.MARIADB -> """
                INSERT INTO $table (${cols.joinToString(", ")})
                VALUES ($placeholders)
                ON DUPLICATE KEY UPDATE
                ${nonKeyCols.joinToString(", ") { "$it = VALUES($it)" }}
            """.trimIndent()
        }
        c.prepareStatement(sql).use { ps ->
            cols.forEachIndexed { idx, col -> bind(ps, idx + 1, col, row[col]) }
            ps.executeUpdate()
        }
    }

    /**
     * Primary-key columns for [table], introspected once via JDBC metadata and cached.
     * The MERGE/upsert KEY clause must match the table's real PK: a table colocated by
     * a composite PK (e.g. account keyed by (account_id, customer_id) for GG affinity,
     * CLAUDE.md §6) needs every PK column in the KEY, not just `<table>_id`. Falls back
     * to the `<table>_id` convention if metadata is unavailable.
     */
    private fun keyColumns(c: Connection, table: String): List<String> =
        pkColumnsByTable.getOrPut(table) {
            val found = mutableListOf<Pair<Int, String>>()
            try {
                c.metaData.getPrimaryKeys(null, schema, table.uppercase()).use { rs ->
                    while (rs.next()) {
                        found += rs.getInt("KEY_SEQ") to rs.getString("COLUMN_NAME").lowercase()
                    }
                }
            } catch (e: Exception) {
                log.warn("Could not introspect primary key for table '{}': {}", table, e.message)
            }
            if (found.isEmpty()) listOf("${table}_id")
            else found.sortedBy { it.first }.map { it.second }
        }

    private fun deleteByPk(c: Connection, table: String, pkColumn: String, pkValue: Any) {
        val sql = "DELETE FROM $table WHERE $pkColumn = ?"
        c.prepareStatement(sql).use { ps ->
            bind(ps, 1, pkColumn, pkValue)
            ps.executeUpdate()
        }
    }

    /**
     * Bind one value. Debezium emits Postgres TIMESTAMP as microseconds-since-epoch
     * (a JSON number), so the `occurred_at` column needs explicit conversion to
     * java.sql.Timestamp — without it, GG's SQL engine rejects the implicit
     * BIGINT->TIMESTAMP cast.
     */
    private fun bind(ps: PreparedStatement, idx: Int, col: String, raw: Any?) {
        if (raw == null) {
            ps.setObject(idx, null)
            return
        }
        if (col == "occurred_at" && raw is Number) {
            ps.setTimestamp(idx, Timestamp(raw.toLong() / 1000)) // µs -> ms
            return
        }
        ps.setObject(idx, raw)
    }

    override fun stop() {
        try {
            connection?.close()
        } catch (e: Exception) {
            log.warn("Error closing GG JDBC connection: {}", e.message)
        }
        connection = null
    }
}
