package com.gridgain.demo.payments.ui.tailer

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tailer parses two envelope shapes off Kafka:
 *  - Debezium source topics (mainframe-to-gg.public.*) are unwrapped:
 *    `{"op":"r","after":{...}}` at the root.
 *  - gg-cache-publisher topics (from-gg/from-mf.public.*) are produced through
 *    Connect's JsonConverter with schemas.enable=true, which WRAPS the value as
 *    `{"schema":...,"payload":{"op":"u","after":{...}}}`.
 *
 * Without unwrapping the second shape, `op` and the key came out as "?" in the
 * GG→Postgres / GG→MariaDB tailers (the events flowed, but rendered
 * `? <table> key=?`). These pin the unwrap.
 */
class KafkaTailerTapTest {

    private val mapper = ObjectMapper()

    @Test
    fun `unwraps the Connect JsonConverter schema-payload envelope (publisher topics)`() {
        val json =
            """{"schema":null,"payload":{"op":"u","before":null,"after":{"account_id":2005,"source":"gg"},"ts_ms":1}}"""
        val env = KafkaTailerTap.unwrapConnectEnvelope(mapper.readTree(json))
        assertEquals("u", env.path("op").asText())
        assertEquals(2005, env.path("after").path("account_id").asInt())
    }

    @Test
    fun `passes an already-unwrapped Debezium envelope through unchanged`() {
        val json = """{"before":null,"after":{"account_id":2001,"source":"mf"},"op":"r","ts_ms":2}"""
        val env = KafkaTailerTap.unwrapConnectEnvelope(mapper.readTree(json))
        assertEquals("r", env.path("op").asText())
        assertEquals(2001, env.path("after").path("account_id").asInt())
    }

    @Test
    fun `treats a row that merely has a payload column as unwrapped (needs both schema and payload)`() {
        // A real row could in theory carry a column named "payload"; only the
        // Connect envelope has BOTH "schema" and "payload" at the top level.
        val json = """{"op":"c","after":{"account_id":7,"payload":"note"},"ts_ms":3}"""
        val env = KafkaTailerTap.unwrapConnectEnvelope(mapper.readTree(json))
        assertEquals("c", env.path("op").asText())
        assertEquals(7, env.path("after").path("account_id").asInt())
    }
}
