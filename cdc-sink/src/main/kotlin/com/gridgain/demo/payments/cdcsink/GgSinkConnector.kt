package com.gridgain.demo.payments.cdcsink

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.connect.connector.Task
import org.apache.kafka.connect.sink.SinkConnector
import org.slf4j.LoggerFactory

/**
 * Kafka Connect sink that applies Debezium-published CDC events to a GridGain 8
 * cluster via the thin JDBC driver. Bridges the mainframe-side Postgres source
 * to the modern GG-side cache so phase 4 of the demo (CLAUDE.md §2) works
 * end-to-end.
 *
 * Topic→table mapping is convention-based: a topic named "{prefix}.public.{table}"
 * is routed to the GG SQL table "{table}" in the PUBLIC schema. The primary key
 * column is assumed to be "{table}_id" — matches the data-model schema authored
 * in mainframe-payments-demo/src/main/resources/data-model/payments-schema.yaml.
 */
class GgSinkConnector : SinkConnector() {
    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var config: Map<String, String>

    override fun version(): String = "0.0.1-SNAPSHOT"

    override fun start(props: Map<String, String>) {
        config = HashMap(props)
        log.info("GgSinkConnector starting with jdbc.url={}", config[GgSinkConfig.GG_JDBC_URL])
    }

    override fun taskClass(): Class<out Task> = GgSinkTask::class.java

    override fun taskConfigs(maxTasks: Int): List<Map<String, String>> =
        // All tasks share the same config; Kafka Connect handles partition assignment.
        List(maxTasks) { HashMap(config) }

    override fun stop() {
        log.info("GgSinkConnector stopping")
    }

    override fun config(): ConfigDef = GgSinkConfig.CONFIG_DEF
}
