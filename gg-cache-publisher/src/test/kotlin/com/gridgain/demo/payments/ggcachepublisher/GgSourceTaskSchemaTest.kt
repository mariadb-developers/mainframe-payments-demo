package com.gridgain.demo.payments.ggcachepublisher

import java.sql.Types
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.data.SchemaBuilder
import org.apache.kafka.connect.data.Timestamp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks down the pure schema/coercion logic the off-the-shelf
 * debezium-connector-jdbc sink depends on. The sink NPEs on a null value schema
 * and only treats a record as a Debezium change event (reading `op`/`after`) when
 * the value schema name ends in ".Envelope" — both contracts are verified here so
 * a regression is caught in seconds rather than after a full Connect-image rebuild.
 */
class GgSourceTaskSchemaTest {

    private val task = GgSourceTask()

    @Test
    fun `connectSchema maps the data model's JDBC types`() {
        assertEquals(Schema.Type.INT64, task.connectSchema(Types.BIGINT).type())
        assertTrue(task.connectSchema(Types.BIGINT).isOptional)

        assertEquals(Schema.Type.STRING, task.connectSchema(Types.VARCHAR).type())

        // TIMESTAMP must surface as the Connect logical Timestamp (name-tagged INT64)
        // so the sink writes a real DB TIMESTAMP, not an epoch-millis bigint.
        val ts = task.connectSchema(Types.TIMESTAMP)
        assertEquals(Schema.Type.INT64, ts.type())
        assertEquals(Timestamp.LOGICAL_NAME, ts.name())

        assertEquals(Schema.Type.INT32, task.connectSchema(Types.INTEGER).type())
        assertEquals(Schema.Type.BOOLEAN, task.connectSchema(Types.BOOLEAN).type())
        assertEquals(Schema.Type.FLOAT64, task.connectSchema(Types.DOUBLE).type())
    }

    @Test
    fun `coerce converts JDBC values to the Connect-declared Java types`() {
        // GG thin JDBC can hand back an Int for a BIGINT column; the OPTIONAL_INT64
        // schema requires a Long or Struct.put validation throws.
        assertEquals(7L, task.coerce(7, Types.BIGINT))
        assertEquals(java.lang.Long::class.java, task.coerce(7, Types.BIGINT)!!.javaClass)

        // Connect's Timestamp logical type wants a java.util.Date; java.sql.Timestamp is one.
        val sqlTs = java.sql.Timestamp(1_700_000_000_000L)
        val coerced = task.coerce(sqlTs, Types.TIMESTAMP)
        assertTrue(coerced is java.util.Date)
        assertEquals(1_700_000_000_000L, (coerced as java.util.Date).time)

        assertEquals("PURCHASE", task.coerce("PURCHASE", Types.VARCHAR))
        assertNull(task.coerce(null, Types.BIGINT))
    }

    @Test
    fun `envelopeSchema is a Debezium-shaped, sink-recognised envelope`() {
        val rowSchema = SchemaBuilder.struct().name("gg.public.transaction.Value").optional()
            .field("transaction_id", Schema.OPTIONAL_INT64_SCHEMA)
            .field("source", Schema.OPTIONAL_STRING_SCHEMA)
            .build()

        val env = task.envelopeSchema("transaction", rowSchema)

        // The sink's isFlattened() returns false (→ reads op/after) ONLY for *.Envelope.
        assertTrue(env.name().endsWith(".Envelope"), "schema name must end in .Envelope")
        assertEquals("gg.public.transaction.Envelope", env.name())
        assertEquals(rowSchema, env.field("after").schema())
        assertEquals(rowSchema, env.field("before").schema())
        assertEquals(Schema.Type.STRING, env.field("op").schema().type())
        assertEquals(Schema.Type.INT64, env.field("ts_ms").schema().type())
        // `before` must be optional — we never populate it, so the Struct stays valid.
        assertTrue(env.field("before").schema().isOptional)
    }

    @Test
    fun `keyStructFor builds a composite key for the account table`() {
        // account PK is (account_id, customer_id) — CLAUDE.md §6 colocation key.
        val keyMap = linkedMapOf<String, Any?>("account_id" to 42L, "customer_id" to 7L)

        val (schema, struct) = task.keyStructFor("account", keyMap)

        assertEquals("gg.public.account.Key", schema.name())
        assertEquals(Schema.Type.INT64, schema.field("account_id").schema().type())
        assertFalse(schema.field("account_id").schema().isOptional, "PK fields are required")
        assertEquals(42L, struct.getInt64("account_id"))
        assertEquals(7L, struct.getInt64("customer_id"))
    }
}
