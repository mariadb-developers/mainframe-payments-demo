package com.gridgain.demo.payments.ui.services

import java.sql.Timestamp

/**
 * A point-in-time copy of the payments tables (customer / account / product /
 * transaction), used by the "bring X online" beats to bulk-load one store from
 * another directly (CLAUDE.md §2):
 *  - Postgres → GG  (phase 2): read by [MainframeProxyService], written by [GridGainService].
 *  - GG → MariaDB   (phase 5): read by [GridGainService], written by [MariaDbService].
 *
 * An internal service-layer type, not a wire DTO. Money is in integer cents and
 * `source` is preserved from the row ('mf' / 'gg') so a bulk load doesn't
 * masquerade rows as a different origin.
 */
data class PaymentsSnapshot(
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
