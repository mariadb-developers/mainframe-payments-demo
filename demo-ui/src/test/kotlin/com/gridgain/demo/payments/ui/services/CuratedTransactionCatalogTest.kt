package com.gridgain.demo.payments.ui.services

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The catalog is the demo's curated *menu* of selectable mainframe transactions.
 * It is deliberately decoupled from the `transaction` table: the demo opens with
 * zero transactions (and $0 balances), yet the mainframe panel must still show the
 * choices. These tests pin the parsing and the display-string building.
 */
class CuratedTransactionCatalogTest {

    private val yaml = """
        transactions:
          - id: "3001"
            customer_id: "1001"
            customer_name: "Raghu"
            account_id: 2001
            product_id: 1
            product_name: "NVIDIA GeForce RTX 5080 Graphics Card"
            amount_cents: 134999
            type: PURCHASE
          - id: "3004"
            customer_id: "1001"
            customer_name: "Raghu"
            account_id: 2001
            product_id: null
            product_name: null
            amount_cents: 100000
            type: PAYMENT
    """.trimIndent()

    @Test
    fun `parses templates from yaml`() {
        val templates = CuratedTransactionCatalog.fromYaml(yaml).templates()
        assertEquals(2, templates.size)
        val purchase = templates[0]
        assertEquals("3001", purchase.id)
        assertEquals("1001", purchase.customerId)
        assertEquals("Raghu", purchase.customerName)
        assertEquals(2001L, purchase.accountId)
        assertEquals(1L, purchase.productId)
        assertEquals("NVIDIA GeForce RTX 5080 Graphics Card", purchase.productName)
        assertEquals(134999L, purchase.amountCents)
        assertEquals("PURCHASE", purchase.type)
    }

    @Test
    fun `payment template has null product`() {
        val payment = CuratedTransactionCatalog.fromYaml(yaml).byId("3004")
        assertNull(payment.productId)
        assertNull(payment.productName)
        assertEquals("PAYMENT", payment.type)
        assertEquals(100000L, payment.amountCents)
    }

    @Test
    fun `builds purchase description in dollars`() {
        val curated = CuratedTransactionCatalog.fromYaml(yaml).curatedTransactions().first { it.id == "3001" }
        assertEquals("Raghu buys NVIDIA GeForce RTX 5080 Graphics Card for $1349.99", curated.description)
        assertEquals(BigDecimal("1349.99"), curated.amount)
        assertEquals("1001", curated.customerId)
        assertEquals("2001", curated.accountId)
        assertEquals("1", curated.productId)
        assertEquals("PURCHASE", curated.kind)
    }

    @Test
    fun `builds payment description in dollars`() {
        val curated = CuratedTransactionCatalog.fromYaml(yaml).curatedTransactions().first { it.id == "3004" }
        assertEquals("Raghu pays $1000.00 on account", curated.description)
        assertNull(curated.productId)
    }

    @Test
    fun `byId throws helpful error for unknown id`() {
        val e = assertFailsWith<IllegalArgumentException> { CuratedTransactionCatalog.fromYaml(yaml).byId("9999") }
        assertTrue(e.message!!.contains("9999"), "error should name the unknown id, was: ${e.message}")
    }

    @Test
    fun `loads the shipped classpath resource with the seven curated choices`() {
        // The shipped curated list drives the mainframe menu; it must be present
        // and complete or the demo opens with an empty menu.
        val catalog = CuratedTransactionCatalog.fromClasspath()
        assertEquals(7, catalog.templates().size)
    }
}
