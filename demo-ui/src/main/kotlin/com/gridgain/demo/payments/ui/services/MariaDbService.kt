package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.AnalyticQueryDefinition
import com.gridgain.demo.payments.ui.model.AnalyticQueryResult
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

class MariaDbService(config: UiConfig) : AutoCloseable {

    // Use dataSourceClassName instead of jdbcUrl to avoid Hikari calling
    // DriverManager.getDrivers() — that triggers ignite-core's
    // IgniteJdbcThinDriver static init, which fails in this JVM and crashes
    // any subsequent JDBC use.
    private val ds: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            dataSourceClassName = "org.mariadb.jdbc.MariaDbDataSource"
            addDataSourceProperty("url", config.mariaDbJdbcUrl)
            addDataSourceProperty("user", config.mariaDbUsername)
            addDataSourceProperty("password", config.mariaDbPassword)
            maximumPoolSize = 4
            poolName = "mariadb-analytics"
            // Lazy pool init: the DB may not be reachable when the UI starts
            // (operator typically launches port-forward after the UI boots).
            initializationFailTimeout = -1
        },
    )

    fun listQueries(): List<AnalyticQueryDefinition> = QUERIES.values.map { it.definition }

    fun runQuery(id: String): AnalyticQueryResult {
        val query = QUERIES[id] ?: throw NoSuchElementException("Unknown analytic query: $id")
        return ds.connection.use { c ->
            c.prepareStatement(query.sql).use { ps ->
                ps.executeQuery().use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                    val rows = buildList {
                        while (rs.next()) {
                            add((1..meta.columnCount).map { i -> rs.getObject(i)?.toString() ?: "" })
                        }
                    }
                    AnalyticQueryResult(queryId = id, columns = columns, rows = rows)
                }
            }
        }
    }

    /**
     * Truncates the analytics tables. MariaDB starts the demo empty — it's the
     * modern target that accumulates rows via the GG cache publisher's
     * fan-out, not a system that holds seed state. After reset, MariaDB
     * repopulates within seconds because reseeding Postgres + GG produces a
     * burst of from-mf events that the gg-to-mariadb sink applies here.
     */
    fun reset() {
        ds.connection.use { c ->
            // MariaDB doesn't allow multi-statement TRUNCATE in one call; do
            // them sequentially. FKs were dropped at deploy time
            // (see ResetService comment), so order doesn't matter.
            c.createStatement().use { st ->
                listOf("transaction", "account", "product", "customer").forEach {
                    st.execute("TRUNCATE TABLE $it")
                }
            }
        }
    }

    override fun close() {
        ds.close()
    }

    private data class Query(val definition: AnalyticQueryDefinition, val sql: String)

    companion object {
        private val QUERIES: Map<String, Query> = listOf(
            Query(
                AnalyticQueryDefinition(
                    id = "row-counts",
                    label = "Row counts",
                    description = "Counts in each analytics table.",
                ),
                """
                SELECT 'customer' AS table_name, COUNT(*) AS row_count FROM customer
                UNION ALL SELECT 'account',     COUNT(*) FROM account
                UNION ALL SELECT 'transaction', COUNT(*) FROM transaction
                UNION ALL SELECT 'product',     COUNT(*) FROM product
                """.trimIndent(),
            ),
            Query(
                AnalyticQueryDefinition(
                    id = "raghu-transactions",
                    label = "Raghu's transactions",
                    description = "All transactions on Raghu's account (curated customer 1001).",
                ),
                """
                SELECT t.transaction_id, t.amount, t.type, p.name AS product, t.occurred_at
                FROM transaction t
                JOIN account a ON a.account_id = t.account_id
                LEFT JOIN product p ON p.product_id = t.product_id
                WHERE a.customer_id = 1001
                ORDER BY t.occurred_at DESC
                LIMIT 50
                """.trimIndent(),
            ),
            Query(
                AnalyticQueryDefinition(
                    id = "recent-window",
                    label = "Last hour of transactions",
                    description = "Sum and count of transactions in the last hour.",
                ),
                """
                SELECT COUNT(*) AS tx_count, COALESCE(SUM(amount), 0) AS total_amount_cents
                FROM transaction
                WHERE occurred_at >= NOW() - INTERVAL 1 HOUR
                """.trimIndent(),
            ),
            Query(
                AnalyticQueryDefinition(
                    id = "top-customers",
                    label = "Top 5 spenders",
                    description = "Customers with the largest total spend across all PURCHASE transactions.",
                ),
                """
                SELECT c.first_name, COUNT(t.transaction_id) AS purchases, SUM(t.amount) AS total_cents
                FROM customer c
                JOIN account a ON a.customer_id = c.customer_id
                JOIN transaction t ON t.account_id = a.account_id
                WHERE t.type = 'PURCHASE'
                GROUP BY c.customer_id, c.first_name
                ORDER BY total_cents DESC
                LIMIT 5
                """.trimIndent(),
            ),
        ).associateBy { it.definition.id }
    }
}
