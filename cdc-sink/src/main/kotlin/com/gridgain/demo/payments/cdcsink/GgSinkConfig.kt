package com.gridgain.demo.payments.cdcsink

import org.apache.kafka.common.config.ConfigDef

object GgSinkConfig {
    const val GG_JDBC_URL = "gg.jdbc.url"
    const val GG_JDBC_URL_DOC =
        "JDBC URL of the target database. For GridGain 8 use " +
        "'jdbc:ignite:thin://<host>:10800'; for Postgres 'jdbc:postgresql://<host>:5432/<db>'; " +
        "for MariaDB 'jdbc:mariadb://<host>:3306/<db>'."

    const val SQL_SCHEMA = "gg.sql.schema"
    const val SQL_SCHEMA_DEFAULT = "PUBLIC"

    const val JDBC_USERNAME = "jdbc.username"
    const val JDBC_PASSWORD = "jdbc.password"

    const val DIALECT = "dialect"
    const val DIALECT_DEFAULT = "gg8"
    const val DIALECT_DOC =
        "SQL dialect for upsert generation: 'gg8' (MERGE INTO ... KEY), " +
        "'postgres' (INSERT ... ON CONFLICT), 'mariadb' (INSERT ... ON DUPLICATE KEY)."

    const val SOURCE_FILTER = "source.filter"
    const val SOURCE_FILTER_DOC =
        "Optional. If set, the sink applies only events whose row's `source` column " +
        "matches this value (e.g. 'mf' or 'gg'). Used for loop breaks in bidirectional " +
        "GG ⇄ Postgres flows; leave unset to apply every event."

    val CONFIG_DEF: ConfigDef = ConfigDef()
        .define(GG_JDBC_URL, ConfigDef.Type.STRING, ConfigDef.Importance.HIGH, GG_JDBC_URL_DOC)
        .define(SQL_SCHEMA, ConfigDef.Type.STRING, SQL_SCHEMA_DEFAULT, ConfigDef.Importance.MEDIUM,
            "Target SQL schema name (default PUBLIC).")
        .define(JDBC_USERNAME, ConfigDef.Type.STRING, "", ConfigDef.Importance.MEDIUM, "JDBC username (omit for unauthenticated GG8 thin client).")
        .define(JDBC_PASSWORD, ConfigDef.Type.STRING, "", ConfigDef.Importance.MEDIUM, "JDBC password.")
        .define(DIALECT, ConfigDef.Type.STRING, DIALECT_DEFAULT, ConfigDef.Importance.MEDIUM, DIALECT_DOC)
        .define(SOURCE_FILTER, ConfigDef.Type.STRING, "", ConfigDef.Importance.MEDIUM, SOURCE_FILTER_DOC)
}
