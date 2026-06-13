package com.gridgain.demo.payments.ui.services

import java.sql.Timestamp

/**
 * A point-in-time copy of the mainframe-proxy (Postgres) tables, used by the
 * phase-2 "bring GridGain online" beat to bulk-load GG directly from the
 * mainframe — the snapshot half of "capture first, then snapshot, then stream"
 * (CLAUDE.md §2). Read while the cdc-sink is paused so the load never races the
 * event feed.
 *
 * This is an internal service-layer type, not a wire DTO. Money is in integer
 * cents and `source` is preserved from the row ('mf' for mainframe-originated)
 * so the bulk load doesn't masquerade mainframe data as GG-originated and echo
 * it back out through the gg-cache-publisher -> Postgres path.
 */
data class MainframeSnapshot(
    val customers: List<CustomerRow>,
    val accounts: List<AccountRow>,
    val products: List<ProductRow>,
    val transactions: List<TransactionRow>,
) {
    data class CustomerRow(val customerId: Long, val firstName: String, val source: String)

    data class AccountRow(val accountId: Long, val customerId: Long, val balanceCents: Long, val source: String)

    data class ProductRow(val productId: Long, val name: String, val priceCents: Long, val source: String)

    data class TransactionRow(
        val transactionId: Long,
        val accountId: Long,
        // Nullable: a PAYMENT transaction has no product (mirrors the nullable
        // transaction.product_id column in both Postgres and GG).
        val productId: Long?,
        val amountCents: Long,
        val type: String,
        val occurredAt: Timestamp,
        val source: String,
    )
}
