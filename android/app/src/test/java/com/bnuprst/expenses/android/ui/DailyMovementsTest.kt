package com.bnuprst.expenses.android.ui

import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyMovementsTest {

    private fun transaction(
        id: Long,
        amount: String,
        currency: String = "PLN",
        status: TxnStatus = TxnStatus.CATEGORIZED,
        categoryId: Long? = null,
    ): TransactionDto = TransactionDto(
        id = id,
        walletId = 1L,
        bookingDate = "2026-11-12",
        amount = amount,
        currency = currency,
        description = "Test transaction",
        merchantKey = "test",
        categoryId = categoryId,
        status = status,
    )

    private fun assertAmount(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    @Test
    fun `sums incoming and outgoing keeping the negative sign`() {
        val items = listOf(
            transaction(1, "10000"),
            transaction(2, "5000"),
            transaction(3, "-500"),
            transaction(4, "-53.70"),
        )
        val result = dailyMovements(items).single()
        assertAmount("15000", result.incoming)
        assertAmount("-553.70", result.outgoing)
    }

    @Test
    fun `adds decimals exactly`() {
        val result = dailyMovements(listOf(transaction(1, "0.10"), transaction(2, "0.20"))).single()
        assertAmount("0.30", result.incoming)
        assertAmount("0", result.outgoing)
    }

    @Test
    fun `fills the missing direction with zero`() {
        val incomingOnly = dailyMovements(listOf(transaction(1, "5.00"))).single()
        assertAmount("5.00", incomingOnly.incoming)
        assertAmount("0", incomingOnly.outgoing)

        val outgoingOnly = dailyMovements(listOf(transaction(1, "-5.00"))).single()
        assertAmount("0", outgoingOnly.incoming)
        assertAmount("-5.00", outgoingOnly.outgoing)
    }

    @Test
    fun `keeps a zero-only currency with two zeros`() {
        val items = listOf(transaction(1, "0"), transaction(2, "0.00"), transaction(3, "-0.00"))
        val result = dailyMovements(items)
        assertEquals(1, result.size)
        assertAmount("0", result.single().incoming)
        assertAmount("0", result.single().outgoing)
    }

    @Test
    fun `returns empty for empty input`() {
        assertEquals(emptyList<DailyMovements>(), dailyMovements(emptyList()))
    }

    @Test
    fun `ignores invalid amounts without throwing`() {
        val items = listOf(transaction(1, ""), transaction(2, "not-a-number"), transaction(3, "2.50"))
        val result = dailyMovements(items)
        assertEquals(1, result.size)
        assertAmount("2.50", result.single().incoming)
        assertAmount("0", result.single().outgoing)
    }

    @Test
    fun `omits invalid-only currencies and empty invalid input`() {
        val withValid = dailyMovements(
            listOf(transaction(1, "10", currency = "PLN"), transaction(2, "not-a-number", currency = "USD")),
        )
        assertEquals(listOf("PLN"), withValid.map { it.currency })
        assertAmount("10", withValid.single().incoming)

        val allInvalid = dailyMovements(
            listOf(transaction(1, "bad", currency = "PLN"), transaction(2, "", currency = "USD")),
        )
        assertEquals(emptyList<DailyMovements>(), allInvalid)
    }

    @Test
    fun `orders currencies ascending`() {
        val items = listOf(
            transaction(1, "10", currency = "PLN"),
            transaction(2, "5", currency = "USD"),
            transaction(3, "-3", currency = "EUR"),
            transaction(4, "-2", currency = "PLN"),
            transaction(5, "-1", currency = "USD"),
            transaction(6, "4", currency = "EUR"),
        )
        val result = dailyMovements(items)
        assertEquals(listOf("EUR", "PLN", "USD"), result.map { it.currency })
        val byCurrency = result.associateBy { it.currency }
        assertAmount("4", byCurrency.getValue("EUR").incoming)
        assertAmount("-3", byCurrency.getValue("EUR").outgoing)
        assertAmount("10", byCurrency.getValue("PLN").incoming)
        assertAmount("-2", byCurrency.getValue("PLN").outgoing)
        assertAmount("5", byCurrency.getValue("USD").incoming)
        assertAmount("-1", byCurrency.getValue("USD").outgoing)
    }

    @Test
    fun `includes every status`() {
        for (status in TxnStatus.entries) {
            val result = dailyMovements(
                listOf(transaction(1, "7.00", status = status), transaction(2, "-2.00", status = status)),
            ).single()
            assertAmount("7.00", result.incoming)
            assertAmount("-2.00", result.outgoing)
        }
    }

    @Test
    fun `counts transfers and uncategorized rows by sign only`() {
        val result = dailyMovements(
            listOf(
                transaction(1, "15.00", categoryId = 42L), // referencing a transfer category ID
                transaction(2, "-3.00", categoryId = null), // uncategorized
            ),
        ).single()
        assertAmount("15.00", result.incoming)
        assertAmount("-3.00", result.outgoing)
    }

    @Test
    fun `keeps exact arithmetic on large amounts`() {
        val positive = dailyMovements(
            listOf(transaction(1, "99999999999999999999999.99"), transaction(2, "0.01")),
        ).single()
        assertAmount("100000000000000000000000.00", positive.incoming)
        assertAmount("0", positive.outgoing)

        val negative = dailyMovements(
            listOf(transaction(1, "-99999999999999999999999.99"), transaction(2, "-0.01")),
        ).single()
        assertAmount("0", negative.incoming)
        assertAmount("-100000000000000000000000.00", negative.outgoing)
    }

    @Test
    fun `recalculates on append and replacement`() {
        val initial = dailyMovements(listOf(transaction(1, "10"), transaction(2, "-2"))).single()
        assertAmount("10", initial.incoming)
        assertAmount("-2", initial.outgoing)

        val appended = dailyMovements(
            listOf(transaction(1, "10"), transaction(2, "-2"), transaction(3, "5")),
        ).single()
        assertAmount("15", appended.incoming)
        assertAmount("-2", appended.outgoing)

        val replaced = dailyMovements(
            listOf(transaction(1, "20"), transaction(2, "-4"), transaction(3, "1")),
        ).single()
        assertAmount("21", replaced.incoming)
        assertAmount("-4", replaced.outgoing)
    }
}
