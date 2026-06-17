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

/**
 * Pods-only load control for the data generator (CLAUDE.md §3/§10). The presenter sets only the
 * pod count; each pod runs at its GG-round-trip latency ceiling (~500 ops/sec), so adding pods is
 * the lever for saturating GG.
 *
 *  - [replicas] is the number of generator pods (0 = off).
 *  - [targetOpsPerSecond] is a display estimate (replicas × ~500); the measured rate comes from the
 *    metrics panel, not this field.
 *  - [running] reflects whether a distributed generator run is currently dispatched.
 */
data class GeneratorState(
    val targetOpsPerSecond: Int,
    val replicas: Int,
    val running: Boolean,
)

/** Body of POST /api/generator/pods. Snake_case maps via Ktor's Jackson naming strategy. */
data class SetPodsRequest(
    val pods: Int,
)

/** A Kafka Connect task currently in FAILED state (connector still RUNNING but applying nothing). */
data class FailedTaskRef(
    val connector: String,
    val task: Int,
)

/**
 * Health of the demo's Kafka Connect connectors. The UI polls this so a sink whose task has
 * died (e.g. a stale idle JDBC connection the DB dropped) is visible rather than silently
 * applying nothing — the tailer "live" badge reflects the browser↔backend socket, not the sink.
 */
data class ConnectorHealth(
    val failedTasks: List<FailedTaskRef>,
)
