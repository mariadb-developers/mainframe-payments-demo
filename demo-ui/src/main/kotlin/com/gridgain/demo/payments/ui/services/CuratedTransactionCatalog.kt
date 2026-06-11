package com.gridgain.demo.payments.ui.services

import com.gridgain.demo.payments.ui.model.CuratedTransaction
import java.math.BigDecimal
import org.yaml.snakeyaml.Yaml

/**
 * A single curated transaction *template* — one entry of the mainframe panel's
 * selectable menu. Carries everything needed both to render the choice
 * (customerName / productName, denormalized) and to post a fresh transaction at
 * demo time (accountId / productId / amountCents / type).
 */
data class CuratedTransactionTemplate(
    val id: String,
    val customerId: String,
    val customerName: String,
    val accountId: Long,
    val productId: Long?,
    val productName: String?,
    val amountCents: Long,
    val type: String,
)

/**
 * The curated menu of selectable mainframe transactions, loaded from the shipped
 * `curated-transactions.yaml`.
 *
 * Deliberately decoupled from the `transaction` table: the demo opens with zero
 * transactions and $0 balances, yet the mainframe panel must still show the
 * choices. The choices therefore live in this shipped resource rather than as
 * pre-seeded rows (see CLAUDE.md §10).
 *
 * Parsing uses SnakeYAML directly (not jackson-dataformat-yaml) because the
 * workspace force-pins SnakeYAML to 1.33, which the Jackson YAML dataformat does
 * not support — see the SnakeYAML note in the root CLAUDE.md.
 */
class CuratedTransactionCatalog(private val templates: List<CuratedTransactionTemplate>) {

    fun templates(): List<CuratedTransactionTemplate> = templates

    /** Looks up a template by its curated id; throws with the known ids if absent. */
    fun byId(id: String): CuratedTransactionTemplate =
        templates.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException(
                "Unknown curated transaction id: $id. Known ids: " +
                    "${templates.joinToString(", ") { it.id }}. " +
                    "The id must match an entry in curated-transactions.yaml.",
            )

    /** The display rows for the mainframe panel menu (amounts rendered in dollars). */
    fun curatedTransactions(): List<CuratedTransaction> = templates.map { t ->
        val amount = BigDecimal(t.amountCents).movePointLeft(2)
        val description = when (t.type) {
            "PURCHASE" -> "${t.customerName} buys ${t.productName} for ${'$'}$amount"
            "PAYMENT" -> "${t.customerName} pays ${'$'}$amount on account"
            else -> "${t.customerName} ${t.type} ${'$'}$amount"
        }
        CuratedTransaction(
            id = t.id,
            description = description,
            customerId = t.customerId,
            accountId = t.accountId.toString(),
            productId = t.productId?.toString(),
            amount = amount,
            kind = t.type,
        )
    }

    companion object {
        private const val RESOURCE = "/curated-transactions.yaml"

        /** Loads the curated menu from the demo-ui classpath resource. */
        fun fromClasspath(resource: String = RESOURCE): CuratedTransactionCatalog {
            val stream = CuratedTransactionCatalog::class.java.getResourceAsStream(resource)
                ?: throw IllegalStateException(
                    "Curated transactions resource not found on classpath: $resource. " +
                        "It must be packaged under demo-ui/src/main/resources.",
                )
            return stream.use { fromYaml(it.readBytes().decodeToString()) }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromYaml(yaml: String): CuratedTransactionCatalog {
            val root = Yaml().load<Map<String, Any?>?>(yaml)
                ?: throw IllegalStateException("curated-transactions.yaml is empty.")
            val rows = root["transactions"] as? List<Map<String, Any?>>
                ?: throw IllegalStateException(
                    "curated-transactions.yaml must have a top-level 'transactions' list.",
                )
            return CuratedTransactionCatalog(rows.map(::toTemplate))
        }

        private fun toTemplate(row: Map<String, Any?>): CuratedTransactionTemplate =
            CuratedTransactionTemplate(
                id = str(row, "id"),
                customerId = str(row, "customer_id"),
                customerName = str(row, "customer_name"),
                accountId = long(row, "account_id"),
                productId = longOrNull(row, "product_id"),
                productName = row["product_name"]?.toString(),
                amountCents = long(row, "amount_cents"),
                type = str(row, "type"),
            )

        private fun str(row: Map<String, Any?>, key: String): String =
            (row[key] ?: missing(row, key)).toString()

        private fun long(row: Map<String, Any?>, key: String): Long =
            (row[key] as? Number ?: missing(row, key)).toLong()

        private fun longOrNull(row: Map<String, Any?>, key: String): Long? =
            (row[key] as? Number)?.toLong()

        private fun missing(row: Map<String, Any?>, key: String): Nothing =
            throw IllegalStateException(
                "curated-transactions.yaml entry is missing required field '$key': $row",
            )
    }
}
