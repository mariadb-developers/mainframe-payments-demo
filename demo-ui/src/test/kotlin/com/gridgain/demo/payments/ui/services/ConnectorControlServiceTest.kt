package com.gridgain.demo.payments.ui.services

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bring-online beat pauses the cdc-sink connector so GG can be bulk-loaded
 * before the event feed is unpaused (CLAUDE.md §2 phase 2). The demo UI decides
 * whether the feed is paused or live by reading the Kafka Connect
 * `/connectors/{name}/status` response, so the parser that turns that JSON into
 * a [FeedState] is the one piece of real branching logic here — these tests
 * pin it.
 */
class ConnectorControlServiceTest {

    @Test
    fun `paused connector reports PAUSED`() {
        val json = """
            {"name":"cdc-sink","connector":{"state":"PAUSED","worker_id":"10.0.0.1:8083"},
             "tasks":[{"id":0,"state":"PAUSED","worker_id":"10.0.0.1:8083"}],"type":"sink"}
        """.trimIndent()
        assertEquals(FeedState.PAUSED, ConnectorControlService.parseFeedState(json))
    }

    @Test
    fun `running connector reports LIVE`() {
        val json = """
            {"name":"cdc-sink","connector":{"state":"RUNNING","worker_id":"10.0.0.1:8083"},
             "tasks":[{"id":0,"state":"RUNNING","worker_id":"10.0.0.1:8083"}],"type":"sink"}
        """.trimIndent()
        assertEquals(FeedState.LIVE, ConnectorControlService.parseFeedState(json))
    }

    @Test
    fun `connector state is authoritative over lagging task state`() {
        // Connect flips the connector to PAUSED before its tasks settle; the
        // connector-level state is the signal the UI trusts.
        val json = """
            {"name":"cdc-sink","connector":{"state":"PAUSED","worker_id":"10.0.0.1:8083"},
             "tasks":[{"id":0,"state":"RUNNING","worker_id":"10.0.0.1:8083"}],"type":"sink"}
        """.trimIndent()
        assertEquals(FeedState.PAUSED, ConnectorControlService.parseFeedState(json))
    }

    @Test
    fun `unparseable or stateless responses report UNKNOWN`() {
        assertEquals(FeedState.UNKNOWN, ConnectorControlService.parseFeedState("not json"))
        assertEquals(FeedState.UNKNOWN, ConnectorControlService.parseFeedState("{}"))
        assertEquals(FeedState.UNKNOWN, ConnectorControlService.parseFeedState("""{"connector":{"state":"FROBNICATED"}}"""))
    }
}
