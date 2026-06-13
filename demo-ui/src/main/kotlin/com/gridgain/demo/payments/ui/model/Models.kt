package com.gridgain.demo.payments.ui.model

import java.math.BigDecimal
import java.time.Instant

data class CustomerSummary(
    val customerId: String,
    val name: String,
)

data class AccountBalance(
    val accountId: String,
    val customerId: String,
    val customerName: String,
    val balance: BigDecimal,
)

data class ProductSummary(
    val productId: String,
    val name: String,
    val price: BigDecimal,
)

data class CuratedTransaction(
    val id: String,
    val description: String,
    val customerId: String,
    val accountId: String,
    val productId: String?,
    val amount: BigDecimal,
    val kind: String,
)

data class ExecuteCuratedRequest(
    val curatedTransactionId: String,
)

data class ExecuteGridGainPurchaseRequest(
    val customerId: String,
    val accountId: String,
    val productId: String,
)

data class TransactionResult(
    val transactionId: String,
    val correlationId: String,
    val accountBalanceAfter: BigDecimal,
)

data class AnalyticQueryDefinition(
    val id: String,
    val label: String,
    val description: String,
)

/** Per-table row counts written by the phase-2 bulk load (CLAUDE.md §2). */
data class BulkLoadResult(
    val tablesLoaded: Map<String, Int>,
)

data class AnalyticQueryResult(
    val queryId: String,
    val columns: List<String>,
    val rows: List<List<String>>,
)

data class PhaseState(
    val phase: Int,
)

data class TailerEvent(
    val timestamp: Instant,
    val source: String,
    val operation: String,
    val table: String,
    val key: String,
    val correlationId: String?,
    val payload: Map<String, Any?>,
)

enum class GeneratorRate(val opsPerSecond: Int) {
    OFF(0),
    SLOW(2),
    MEDIUM(20),
    FAST(200),
}

data class GeneratorState(
    val rate: GeneratorRate,
    val runId: String?,
)
