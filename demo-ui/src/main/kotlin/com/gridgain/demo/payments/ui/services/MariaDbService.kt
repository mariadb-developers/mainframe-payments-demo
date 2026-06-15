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
            // The analytics schema has FK constraints (fk_tx_account, fk_tx_product,
            // fk_account_customer), and MariaDB refuses to TRUNCATE an FK-referenced
            // table (it has no TRUNCATE ... CASCADE like Postgres). Disable FK checks
            // for the duration so every table can be cleared, then re-enable before the
            // connection returns to the pool. Statements run separately — MariaDB JDBC
            // doesn't allow multiple statements in one execute().
            c.createStatement().use { st ->
                st.execute("SET FOREIGN_KEY_CHECKS = 0")
                try {
                    listOf("transaction", "account", "product", "customer").forEach {
                        st.execute("TRUNCATE TABLE $it")
                    }
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1")
                }
            }
        }
    }

    /**
     * Bulk-loads a GG snapshot into the MariaDB analytics tables — the "Bulk
     * Load" half of the phase-5 GG→MariaDB beat (CLAUDE.md §2). Run while the
     * GG→MariaDB sink is paused so it doesn't race the feed; upsert
     * (INSERT … ON DUPLICATE KEY UPDATE) makes it idempotent, so the later
     * Kafka backlog drains harmlessly over the same rows on resume.
     *
     * Inserts in FK-safe order (customer → product → account → transaction) to
     * satisfy fk_account_customer / fk_tx_account / fk_tx_product. Returns the
     * per-table row counts written.
     */
    fun bulkLoad(snapshot: PaymentsSnapshot): Map<String, Int> = ds.connection.use { c ->
        c.autoCommit = false
        try {
            c.prepareStatement(
                "INSERT INTO customer (customer_id, first_name, source) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE first_name = VALUES(first_name), source = VALUES(source)",
            ).use { ps ->
                snapshot.customers.forEach {
                    ps.setLong(1, it.customerId); ps.setString(2, it.firstName); ps.setString(3, it.source); ps.addBatch()
                }
                ps.executeBatch()
            }
            c.prepareStatement(
                "INSERT INTO product (product_id, name, price, source) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price), source = VALUES(source)",
            ).use { ps ->
                snapshot.products.forEach {
                    ps.setLong(1, it.productId); ps.setString(2, it.name); ps.setLong(3, it.priceCents); ps.setString(4, it.source); ps.addBatch()
                }
                ps.executeBatch()
            }
            c.prepareStatement(
                "INSERT INTO account (account_id, customer_id, balance, source) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE customer_id = VALUES(customer_id), balance = VALUES(balance), source = VALUES(source)",
            ).use { ps ->
                snapshot.accounts.forEach {
                    ps.setLong(1, it.accountId); ps.setLong(2, it.customerId); ps.setLong(3, it.balanceCents); ps.setString(4, it.source); ps.addBatch()
                }
                ps.executeBatch()
            }
            c.prepareStatement(
                "INSERT INTO transaction (transaction_id, account_id, product_id, amount, type, occurred_at, source) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE account_id = VALUES(account_id), product_id = VALUES(product_id), " +
                    "amount = VALUES(amount), type = VALUES(type), occurred_at = VALUES(occurred_at), source = VALUES(source)",
            ).use { ps ->
                snapshot.transactions.forEach {
                    ps.setLong(1, it.transactionId)
                    ps.setLong(2, it.accountId)
                    if (it.productId == null) ps.setNull(3, java.sql.Types.BIGINT) else ps.setLong(3, it.productId)
                    ps.setLong(4, it.amountCents)
                    ps.setString(5, it.type)
                    ps.setTimestamp(6, it.occurredAt)
                    ps.setString(7, it.source)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
        linkedMapOf(
            "customer" to snapshot.customers.size,
            "product" to snapshot.products.size,
            "account" to snapshot.accounts.size,
            "transaction" to snapshot.transactions.size,
        )
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
            Query(
                AnalyticQueryDefinition(
                    id = "settlement",
                    label = "End-of-day settlement",
                    description = "Payments-processor reconciliation: amounts due FROM each purchaser " +
                        "(debits) and TO each product supplier (credits, by brand).",
                ),
                // As a payments processor, every PURCHASE debits the purchaser's account and credits
                // the product's supplier. This is the end-of-period settlement statement: what to
                // collect from each purchaser and pay to each supplier. The two sides reconcile (sum
                // of debits across purchasers equals sum of credits across suppliers). Supplier = the
                // product's brand, derived from the product name (first word, with iPhone -> Apple).
                """
                SELECT s.party_type AS party_type, s.party AS party,
                       CONCAT('${'$'}', FORMAT(s.amount_cents / 100, 2)) AS amount
                FROM (
                    SELECT 'Due from purchaser' AS party_type, c.first_name AS party,
                           SUM(t.amount) AS amount_cents, 1 AS grp
                    FROM transaction t
                    JOIN account a  ON a.account_id = t.account_id
                    JOIN customer c ON c.customer_id = a.customer_id
                    WHERE t.type = 'PURCHASE'
                    GROUP BY c.customer_id, c.first_name
                    UNION ALL
                    SELECT 'Due to supplier',
                           CASE WHEN p.name LIKE 'iPhone%' THEN 'Apple'
                                ELSE SUBSTRING_INDEX(p.name, ' ', 1) END AS party,
                           SUM(t.amount), 2
                    FROM transaction t
                    JOIN product p ON p.product_id = t.product_id
                    WHERE t.type = 'PURCHASE'
                    GROUP BY party
                ) s
                ORDER BY s.grp, s.amount_cents DESC
                """.trimIndent(),
            ),
        ).associateBy { it.definition.id }
    }
}
