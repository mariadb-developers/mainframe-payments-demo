package com.gridgain.demo.payments.ggcachepublisher

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.connect.connector.Task
import org.apache.kafka.connect.source.SourceConnector
import org.slf4j.LoggerFactory

/**
 * Kafka Connect SOURCE connector that subscribes to a set of GridGain 8 caches
 * via the thin client's ContinuousQuery support and publishes every cache event
 * (insert / update / delete) to a Kafka topic per cache.
 *
 * This is the write-through-cache half of the demo's GG ⇄ {Postgres, MariaDB}
 * integration: any cache write — by the demo backend, the data generator, or
 * the inbound cdc-sink — fires the listener and produces a Kafka event.
 * Downstream Kafka Connect JDBC sinks (debezium-connector-jdbc) fan the events
 * out to Postgres and MariaDB.
 *
 * Loop break is enforced at the downstream sink consumers (filter on the
 * row's `source` column), not here — keeping this connector dumb means a
 * future second consumer that wants the full firehose (e.g. an audit log)
 * doesn't have to fight a built-in filter.
 */
class GgSourceConnector : SourceConnector() {

    private val log = LoggerFactory.getLogger(javaClass)
    private lateinit var config: Map<String, String>

    override fun version(): String = "0.0.1-SNAPSHOT"

    override fun start(props: Map<String, String>) {
        config = HashMap(props)
        log.info(
            "GgSourceConnector starting; addresses={} caches={} topic.prefix.gg={} topic.prefix.mf={}",
            config[GgSourceConfig.GG_CLIENT_ADDRESSES],
            config[GgSourceConfig.GG_CACHES],
            config[GgSourceConfig.TOPIC_PREFIX_GG],
            config[GgSourceConfig.TOPIC_PREFIX_MF],
        )
    }

    override fun taskClass(): Class<out Task> = GgSourceTask::class.java

    override fun taskConfigs(maxTasks: Int): List<Map<String, String>> {
        // Single-task: the ContinuousQuery listener already multiplexes events
        // from all watched caches onto one in-memory queue, and partitioning
        // the watched-cache set across tasks would split related cache events
        // (Customer + Account + Transaction) across different worker JVMs,
        // breaking per-transaction ordering at the sink.
        return listOf(HashMap(config))
    }

    override fun stop() {
        log.info("GgSourceConnector stopping")
    }

    override fun config(): ConfigDef = GgSourceConfig.CONFIG_DEF
}
