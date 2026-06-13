package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.client.gg8.DemoAddressFinder
import com.gridgain.demo.payments.ui.config.UiConfig
import com.gridgain.demo.payments.ui.model.AccountBalance
import com.gridgain.demo.payments.ui.model.CustomerSummary
import com.gridgain.demo.payments.ui.model.ProductSummary
import com.gridgain.demo.payments.ui.model.TransactionResult
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import org.apache.ignite.Ignition
import org.apache.ignite.cache.query.SqlFieldsQuery
import org.apache.ignite.client.IgniteClient
import org.apache.ignite.configuration.ClientConfiguration

/**
 * Reads from / writes to the GG8 cluster via SqlFieldsQuery. The data model is
 * provisioned as plain SQL tables (see data-model/payments-schema.yaml), so
 * the cache value type is BinaryObject under the hood — direct cache.get/put
 * with Map<String,Any> doesn't work. SQL access is the supported path.
 *
 * This service no longer publishes tailer events directly. Every GG cache
 * write fires the gg-cache-publisher (Kafka Connect SourceConnector running in
 * the cdc-pipeline namespace) via the registered ContinuousQuery, which emits
 * to `from-gg.public.<table>` for GG-originated rows. The two JDBC sinks
 * (gg-to-postgres, gg-to-mariadb) fan the events out to the legacy / modern
 * data stores; the demo-ui's KafkaTailerTaps subscribe to the same topics so
 * the visualization reflects what actually landed in each store.
 *
 * Every GG-side write stamps `source='gg'` and the originating `correlation_id`
 * into the row — the publisher lifts the correlation_id onto a Kafka record
 * header (and strips it from the published payload) so the UI can co-highlight
 * matching fan-out events without bloating downstream table schemas.
 */
class GridGainService(
    private val config: UiConfig,
) : AutoCloseable {

    @Volatile private var client: IgniteClient? = null

    private val log = org.slf4j.LoggerFactory.getLogger(GridGainService::class.java)

    private fun connect(): IgniteClient? {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            return try {
                val cfg = ClientConfiguration().setAddressesFinder(DemoAddressFinder(config.clusterName))
                val newClient = Ignition.startClient(cfg)
                log.info("GG client connected to cluster '{}'", config.clusterName)
                client = newClient
                newClient
            } catch (e: Exception) {
                log.warn("GG client connect failed for cluster '{}': {}", config.clusterName, e.message, e)
                null
            }
        }
    }

    /**
     * Whether the GG cluster is reachable right now (attempts a connect). Lets the
     * UI distinguish "connected but caches empty" from "can't reach GG" — the data
     * endpoints return [] for both, so they can't tell those apart on their own.
     */
    fun connected(): Boolean = connect() != null

    fun listCustomers(): List<CustomerSummary> = runQuery(
        "SELECT customer_id, first_name FROM Customer ORDER BY customer_id",
    ).map { row ->
        CustomerSummary(
            customerId = (row[0] as Number).toString(),
            name = row[1]?.toString() ?: "?",
        )
    }

    fun listProducts(): List<ProductSummary> = runQuery(
        "SELECT product_id, name, price FROM Product ORDER BY product_id",
    ).map { row ->
        ProductSummary(
            productId = (row[0] as Number).toString(),
            name = row[1]?.toString() ?: "?",
            price = BigDecimal((row[2] as Number).toLong()).movePointLeft(2),
        )
    }

    fun listAccountBalances(): List<AccountBalance> = runQuery(
        """
        SELECT a.account_id, a.customer_id, c.first_name, a.balance
        FROM Account a
        JOIN Customer c ON c.customer_id = a.customer_id
        ORDER BY a.account_id
        """.trimIndent(),
    ).map { row ->
        AccountBalance(
            accountId = (row[0] as Number).toString(),
            customerId = (row[1] as Number).toString(),
            customerName = row[2]?.toString() ?: "?",
            balance = BigDecimal((row[3] as Number).toLong()).movePointLeft(2),
        )
    }

    /**
     * Executes a purchase: deducts the product's price from the account's balance
     * and inserts a transaction record. Both writes stamp source='gg' and the
     * same correlation_id so the gg-cache-publisher can fan them out to the
     * outbound Kafka topics with a matching `correlation-id` header — that's
     * what powers the UI's cross-tailer co-highlight.
     */
    fun executePurchase(customerId: String, accountId: String, productId: String): TransactionResult {
        connect() ?: throw IllegalStateException(
            "GridGain cluster not reachable. Start a port-forward to the GG client port and retry.",
        )

        val priceCents = runQuery("SELECT price FROM Product WHERE product_id = ?", productId.toLong())
            .firstOrNull()?.let { (it[0] as Number).toLong() }
            ?: throw NoSuchElementException("Unknown product: $productId")
        val newBalance = runQuery("SELECT balance FROM Account WHERE account_id = ?", accountId.toLong())
            .firstOrNull()?.let { (it[0] as Number).toLong() - priceCents }
            ?: throw NoSuchElementException("Unknown account: $accountId")

        val correlationId = UUID.randomUUID().toString()
        val txId = ThreadLocalRandom.current().nextLong(10_000, 1_000_000_000)

        // Account UPDATE — stamps source='gg' and correlation_id so the
        // publisher routes the resulting event to from-gg.public.account and
        // lifts the correlation-id onto a Kafka header.
        runUpdate(
            "UPDATE Account SET balance = ?, source = 'gg', correlation_id = ? WHERE account_id = ?",
            newBalance,
            correlationId,
            accountId.toLong(),
        )
        // Transaction INSERT — same routing fields.
        runUpdate(
            """
            INSERT INTO Transaction
                (transaction_id, account_id, product_id, amount, type, occurred_at, source, correlation_id)
            VALUES (?, ?, ?, ?, 'PURCHASE', ?, 'gg', ?)
            """.trimIndent(),
            txId,
            accountId.toLong(),
            productId.toLong(),
            priceCents,
            Timestamp.from(Instant.now()),
            correlationId,
        )

        return TransactionResult(
            transactionId = txId.toString(),
            correlationId = correlationId,
            accountBalanceAfter = BigDecimal(newBalance).movePointLeft(2),
        )
    }

    /**
     * Bulk-loads a mainframe snapshot directly into the GG SQL tables — the
     * "Bulk Load" half of the phase-2 bring-online beat (CLAUDE.md §2). Run while
     * the cdc-sink is paused so it doesn't race the event feed; MERGE makes it
     * idempotent, so when the feed is later resumed the replayed Kafka backlog
     * (the same rows, plus anything that arrived during the load) upserts harmlessly.
     *
     * Rows keep their mainframe `source` ('mf') and carry no correlation_id —
     * GG-originated rows ('gg', with a correlation_id) are the only ones the
     * gg-cache-publisher fans back out to Postgres, so 'mf'-stamped bulk-loaded
     * rows don't loop back to the source they came from.
     *
     * Returns the per-table row counts written.
     */
    fun bulkLoad(snapshot: MainframeSnapshot): Map<String, Int> {
        connect() ?: throw IllegalStateException(
            "GridGain cluster not reachable. Start a port-forward to the GG client port and retry.",
        )
        snapshot.customers.forEach {
            runUpdate(
                "MERGE INTO Customer (customer_id, first_name, source, correlation_id) VALUES (?, ?, ?, NULL)",
                it.customerId, it.firstName, it.source,
            )
        }
        snapshot.accounts.forEach {
            runUpdate(
                "MERGE INTO Account (account_id, customer_id, balance, source, correlation_id) VALUES (?, ?, ?, ?, NULL)",
                it.accountId, it.customerId, it.balanceCents, it.source,
            )
        }
        snapshot.products.forEach {
            runUpdate(
                "MERGE INTO Product (product_id, name, price, source, correlation_id) VALUES (?, ?, ?, ?, NULL)",
                it.productId, it.name, it.priceCents, it.source,
            )
        }
        snapshot.transactions.forEach {
            runUpdate(
                """
                MERGE INTO Transaction
                    (transaction_id, account_id, product_id, amount, type, occurred_at, source, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
                it.transactionId, it.accountId, it.productId, it.amountCents, it.type, it.occurredAt, it.source,
            )
        }
        return linkedMapOf(
            "Customer" to snapshot.customers.size,
            "Account" to snapshot.accounts.size,
            "Product" to snapshot.products.size,
            "Transaction" to snapshot.transactions.size,
        )
    }

    private fun runQuery(sql: String, vararg args: Any?): List<List<Any?>> {
        val c = connect() ?: return emptyList()
        val cache = c.cache<Any, Any>("SQL_PUBLIC_CUSTOMER") ?: return emptyList()
        val query = SqlFieldsQuery(sql).apply {
            schema = "PUBLIC"
            if (args.isNotEmpty()) setArgs(*args)
        }
        return cache.query(query).all
    }

    private fun runUpdate(sql: String, vararg args: Any?) {
        val c = connect() ?: throw IllegalStateException("GridGain cluster not reachable")
        val cache = c.cache<Any, Any>("SQL_PUBLIC_CUSTOMER")
            ?: throw IllegalStateException("SQL_PUBLIC_CUSTOMER cache not found — data model not deployed")
        val query = SqlFieldsQuery(sql).apply {
            schema = "PUBLIC"
            if (args.isNotEmpty()) setArgs(*args)
        }
        cache.query(query).all
    }

    /**
     * Wipes the GG tables. Reset (see DemoResetService) pauses the cdc-sink
     * BEFORE this runs, so GG stays empty afterwards: the Postgres reseed's
     * mainframe-to-gg.public.* events buffer in Kafka rather than refilling GG.
     * GG is then brought online explicitly during the phase-2 beat — Bulk Load
     * (a direct Postgres→GG snapshot) followed by Unpause Event Feed (drains the
     * buffered events). MERGE on both paths keeps the overlap idempotent.
     *
     * If Kafka Connect was unreachable and the pause failed, the live sink will
     * refill GG from the reseed within a couple of seconds (the old behaviour) —
     * acceptable for a quick dev loop that skips the beat.
     */
    fun reset() {
        connect() ?: throw IllegalStateException("GridGain cluster not reachable")
        listOf(
            "DELETE FROM Transaction",
            "DELETE FROM Account",
            "DELETE FROM Product",
            "DELETE FROM Customer",
        ).forEach { runUpdate(it) }
    }

    override fun close() {
        client?.close()
        client = null
    }

}
