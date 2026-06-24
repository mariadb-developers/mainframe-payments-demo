package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.AccountBalance
import com.gridgain.demo.payments.ui.model.CuratedTransaction
import com.gridgain.demo.payments.ui.model.TransactionResult
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class MainframeProxyService(config: UiConfig) : AutoCloseable {

    // Use dataSourceClassName instead of jdbcUrl to avoid Hikari calling
    // DriverManager.getDrivers() — that triggers ignite-core's
    // IgniteJdbcThinDriver static init, which fails in this JVM and crashes
    // any subsequent JDBC use.
    private val ds: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
            addDataSourceProperty("url", config.mainframeJdbcUrl)
            addDataSourceProperty("user", config.mainframeUsername)
            addDataSourceProperty("password", config.mainframePassword)
            maximumPoolSize = 4
            poolName = "mainframe-proxy"
            // Lazy pool init: the DB may not be reachable when the UI starts
            // (operator typically runs scripts/dev-port-forwards.sh after the UI boots).
            initializationFailTimeout = -1
        },
    )

    // The curated menu is decoupled from the `transaction` table: it loads from the
    // shipped curated-transactions.yaml so the panel shows choices even though the
    // demo opens with zero transactions and $0 balances. See CLAUDE.md §10.
    private val catalog: CuratedTransactionCatalog = CuratedTransactionCatalog.fromClasspath()

    /**
     * The curated menu for the mainframe panel — sourced from [catalog]
     * (curated-transactions.yaml), not the `transaction` table, so it is populated
     * regardless of how many transactions have actually occurred.
     */
    fun listCuratedTransactions(): List<CuratedTransaction> = catalog.curatedTransactions()

    fun listAccountBalances(): List<AccountBalance> = ds.connection.use { c ->
        val sql = """
            SELECT a.account_id, c.customer_id, c.first_name, a.balance
            FROM account a
            JOIN customer c ON c.customer_id = a.customer_id
            ORDER BY a.account_id
        """.trimIndent()
        c.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AccountBalance(
                                accountId = rs.getLong("account_id").toString(),
                                customerId = rs.getLong("customer_id").toString(),
                                customerName = rs.getString("first_name"),
                                balance = BigDecimal(rs.getLong("balance")).movePointLeft(2),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads a full snapshot of the mainframe-proxy tables for the phase-2 bulk
     * load into GG (CLAUDE.md §2). `source` is preserved per row so GG-side rows
     * stay 'mf'-stamped and don't get re-published outbound as GG-originated.
     */
    fun readSnapshot(): PaymentsSnapshot = ds.connection.use { c ->
        val customers = c.prepareStatement(
            "SELECT customer_id, first_name, source FROM customer ORDER BY customer_id",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PaymentsSnapshot.CustomerRow(
                                customerId = rs.getLong("customer_id"),
                                firstName = rs.getString("first_name"),
                                source = rs.getString("source"),
                            ),
                        )
                    }
                }
            }
        }
        val accounts = c.prepareStatement(
            "SELECT account_id, customer_id, balance, source FROM account ORDER BY account_id",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PaymentsSnapshot.AccountRow(
                                accountId = rs.getLong("account_id"),
                                customerId = rs.getLong("customer_id"),
                                balanceCents = rs.getLong("balance"),
                                source = rs.getString("source"),
                            ),
                        )
                    }
                }
            }
        }
        val products = c.prepareStatement(
            "SELECT product_id, name, price, source FROM product ORDER BY product_id",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PaymentsSnapshot.ProductRow(
                                productId = rs.getLong("product_id"),
                                name = rs.getString("name"),
                                priceCents = rs.getLong("price"),
                                source = rs.getString("source"),
                            ),
                        )
                    }
                }
            }
        }
        val transactions = c.prepareStatement(
            "SELECT transaction_id, account_id, product_id, amount, type, occurred_at, source " +
                "FROM transaction ORDER BY transaction_id",
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val productId = rs.getLong("product_id").let { if (rs.wasNull()) null else it }
                        add(
                            PaymentsSnapshot.TransactionRow(
                                transactionId = rs.getLong("transaction_id"),
                                accountId = rs.getLong("account_id"),
                                productId = productId,
                                amountCents = rs.getLong("amount"),
                                type = rs.getString("type"),
                                occurredAt = rs.getTimestamp("occurred_at"),
                                source = rs.getString("source"),
                            ),
                        )
                    }
                }
            }
        }
        PaymentsSnapshot(customers, accounts, products, transactions)
    }

    /**
     * Re-applies a curated transaction (uses the same template but with a fresh
     * transaction id and current timestamp). The cache update is what would normally
     * propagate via CDC to GG; here the demo is showing the mainframe-side write.
     */
    fun executeCuratedTransaction(curatedTransactionId: String): TransactionResult = ds.connection.use { c ->
        val template = catalog.byId(curatedTransactionId)
        c.autoCommit = false
        try {
            val newTxId = ThreadLocalRandom.current().nextLong(10_000, 1_000_000_000)
            c.prepareStatement(
                """
                INSERT INTO transaction (transaction_id, account_id, product_id, amount, type, source)
                VALUES (?, ?, ?, ?, ?, 'mf')
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, newTxId)
                ps.setLong(2, template.accountId)
                if (template.productId == null) ps.setNull(3, java.sql.Types.BIGINT) else ps.setLong(3, template.productId)
                ps.setLong(4, template.amountCents)
                ps.setString(5, template.type)
                ps.executeUpdate()
            }
            val delta = if (template.type == "PURCHASE") -template.amountCents else template.amountCents
            // Re-stamp source='mf': this is a mainframe-originated change. If the account was last
            // touched by a GG transaction (source='gg', written back via the GG→Postgres sink in
            // phase 3), leaving it 'gg' makes the cdc-sink's source.filter=mf DROP this update, so
            // GG's balance never reflects the mainframe payment (phase 4).
            val balanceAfter = c.prepareStatement(
                "UPDATE account SET balance = balance + ?, source = 'mf' WHERE account_id = ? RETURNING balance",
            ).use { ps ->
                ps.setLong(1, delta)
                ps.setLong(2, template.accountId)
                ps.executeQuery().use { rs ->
                    rs.next(); rs.getLong("balance")
                }
            }
            c.commit()
            TransactionResult(
                transactionId = newTxId.toString(),
                correlationId = UUID.randomUUID().toString(),
                accountBalanceAfter = BigDecimal(balanceAfter).movePointLeft(2),
            )
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    /**
     * Truncates and re-seeds the mainframe-proxy tables to the curated demo
     * opening state — 5 customers (1001-1005), 5 accounts (2001-2005, all
     * mainframe-originated, all $0), 10 products (1-10), and NO transactions.
     * The curated menu the presenter picks from lives in curated-transactions.yaml,
     * not the `transaction` table, so the demo opens with zero transactions and
     * $0 balances (see CLAUDE.md §10). Matches what `postgres-init/20-seed-curated.sql`
     * produces on a fresh install, so the same SQL doesn't need to be maintained in
     * two places: the init SQL bootstraps the empty DB, this method resets the DB
     * back to that same state mid-demo.
     */
    fun reset() {
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("TRUNCATE TABLE transaction, account, product, customer RESTART IDENTITY CASCADE")
                    st.execute(CURATED_SEED_SQL)
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    override fun close() {
        ds.close()
    }

    companion object {
        // Mirrors postgres-init/20-seed-curated.sql verbatim. If you change the
        // canonical init script, change this too (and vice-versa). No transactions
        // are seeded — the demo opens with zero transactions and $0 balances; the
        // curated menu lives in curated-transactions.yaml (see CLAUDE.md §10).
        private val CURATED_SEED_SQL = """
            INSERT INTO customer (customer_id, first_name, source) VALUES
                (1001, 'Raghu', 'mf'),
                (1002, 'Sonya', 'mf'),
                (1003, 'Mei',   'mf'),
                (1004, 'Diego', 'mf'),
                (1005, 'Priya', 'mf');
            INSERT INTO account (account_id, customer_id, balance, source) VALUES
                (2001, 1001, 0, 'mf'),
                (2002, 1002, 0, 'mf'),
                (2003, 1003, 0, 'mf'),
                (2004, 1004, 0, 'mf'),
                (2005, 1005, 0, 'mf');
            INSERT INTO product (product_id, name, price, source) VALUES
                (1,  'NVIDIA GeForce RTX 5080 Graphics Card',   134999, 'mf'),
                (2,  'Meta Quest 3S VR Headset',                 43550, 'mf'),
                (3,  'Apple MacBook Pro 16" M4 Max',            379900, 'mf'),
                (4,  'Sony WH-1000XM6 Wireless Headphones',      39999, 'mf'),
                (5,  'Steam Deck OLED 1TB',                      64900, 'mf'),
                (6,  'Logitech MX Master 4 Mouse',               11999, 'mf'),
                (7,  'Keychron Q1 Pro Mechanical Keyboard',      19999, 'mf'),
                (8,  'Samsung Galaxy S25 Ultra',                129999, 'mf'),
                (9,  'Google Pixel 10 Pro',                      99999, 'mf'),
                (10, 'iPhone 17 Pro Max',                       119900, 'mf');
        """.trimIndent()
    }
}
