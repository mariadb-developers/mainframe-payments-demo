package com.gridgain.demo.payments.ggcachepublisher

import org.apache.kafka.common.config.ConfigDef

object GgSourceConfig {
    const val GG_CLIENT_ADDRESSES = "gg.client.addresses"
    const val GG_CLIENT_ADDRESSES_DOC =
        "Comma-separated list of GridGain 8 thin-client endpoints (host:port). " +
        "E.g. 'mainframe-payments-gg8-0.mainframe-payments-gg8.mainframe-payments-gg8.svc.cluster.local:10800'."

    const val GG_CACHES = "gg.caches"
    const val GG_CACHES_DOC =
        "Comma-separated list of cache names to observe. " +
        "Each cache emits to its own Kafka topic '<topic.prefix>.public.<lower(cacheName)>'."

    // Topics are source-routed: each event is published to a topic family
    // determined by the row's `source` column. This lets downstream sinks pick
    // exactly which origin they want without needing a value-aware Filter SMT
    // (Kafka Connect ships only header-/topic-/tombstone-aware predicates).
    const val TOPIC_PREFIX_GG = "topic.prefix.gg"
    const val TOPIC_PREFIX_GG_DEFAULT = "from-gg"
    const val TOPIC_PREFIX_GG_DOC =
        "Topic prefix used for events whose row carries source='gg'. " +
        "Full topic name: '<prefix>.public.<lower(cacheName)>'. Default 'from-gg'."

    const val TOPIC_PREFIX_MF = "topic.prefix.mf"
    const val TOPIC_PREFIX_MF_DEFAULT = "from-mf"
    const val TOPIC_PREFIX_MF_DOC =
        "Topic prefix used for events whose row carries source='mf' (i.e. " +
        "mainframe-originated rows that arrived in GG via the inbound CDC sink). " +
        "Full topic name: '<prefix>.public.<lower(cacheName)>'. Default 'from-mf'."

    val CONFIG_DEF: ConfigDef = ConfigDef()
        .define(GG_CLIENT_ADDRESSES, ConfigDef.Type.STRING, ConfigDef.Importance.HIGH, GG_CLIENT_ADDRESSES_DOC)
        .define(GG_CACHES, ConfigDef.Type.STRING, ConfigDef.Importance.HIGH, GG_CACHES_DOC)
        .define(
            TOPIC_PREFIX_GG,
            ConfigDef.Type.STRING,
            TOPIC_PREFIX_GG_DEFAULT,
            ConfigDef.Importance.MEDIUM,
            TOPIC_PREFIX_GG_DOC,
        )
        .define(
            TOPIC_PREFIX_MF,
            ConfigDef.Type.STRING,
            TOPIC_PREFIX_MF_DEFAULT,
            ConfigDef.Importance.MEDIUM,
            TOPIC_PREFIX_MF_DOC,
        )
}
