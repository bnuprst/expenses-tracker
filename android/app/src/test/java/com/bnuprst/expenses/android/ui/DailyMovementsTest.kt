package com.bnuprst.expenses.android.ui

import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyMovementsTest {

    private fun transaction(
        id: Long,
        amount: String,
        currency: String = "PLN",
        status: TxnStatus = TxnStatus.CATEGORIZED,
        categoryId: Long? = null,
        baseAmount: String? = null,
        baseCurrency: String? = null,
        transferGroup: String? = null,
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
        baseAmount = baseAmount,
        baseCurrency = baseCurrency,
        transferGroup = transferGroup,
    )

    private fun categories(vararg pairs: Pair<Long, CategoryKind>): Map<Long, CategoryDto> =
        pairs.associate { (id, kind) -> id to CategoryDto(id, "category-$id", kind) }

    private fun assertAmount(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

    private fun DailyTotals.baseOrFail(): DailyMovements {
        assertNotNull("expected a base bucket", base)
        return base!!
    }

    @Test
    fun `sums incoming and outgoing keeping the negative sign`() {
        val items = listOf(
            transaction(1, "10000"),
            transaction(2, "5000"),
            transaction(3, "-500"),
            transaction(4, "-53.70"),
        )
        val base = dailyTotals(items, emptyMap(), "PLN").baseOrFail()
        assertEquals("PLN", base.currency)
        assertAmount("15000", base.incoming)
        assertAmount("-553.70", base.outgoing)
        assertEquals(false, base.converted)
    }

    @Test
    fun `adds decimals exactly`() {
        val base = dailyTotals(listOf(transaction(1, "0.10"), transaction(2, "0.20")), emptyMap(), "PLN").baseOrFail()
        assertAmount("0.30", base.incoming)
        assertAmount("0", base.outgoing)
    }

    @Test
    fun `fills the missing direction with zero`() {
        val incomingOnly = dailyTotals(listOf(transaction(1, "5.00")), emptyMap(), "PLN").baseOrFail()
        assertAmount("5.00", incomingOnly.incoming)
        assertAmount("0", incomingOnly.outgoing)

        val outgoingOnly = dailyTotals(listOf(transaction(1, "-5.00")), emptyMap(), "PLN").baseOrFail()
        assertAmount("0", outgoingOnly.incoming)
        assertAmount("-5.00", outgoingOnly.outgoing)
    }

    @Test
    fun `keeps a zero-only currency with two zeros`() {
        val totals = dailyTotals(
            listOf(transaction(1, "0"), transaction(2, "0.00"), transaction(3, "-0.00")),
            emptyMap(),
            "PLN",
        )
        val base = totals.baseOrFail()
        assertAmount("0", base.incoming)
        assertAmount("0", base.outgoing)
        assertTrue(totals.unconverted.isEmpty())
    }

    @Test
    fun `returns empty for empty input`() {
        val totals = dailyTotals(emptyList(), emptyMap(), "PLN")
        assertNull(totals.base)
        assertEquals(emptyList<DailyMovements>(), totals.unconverted)
        assertEquals(0, totals.excludedTransfers)
    }

    @Test
    fun `ignores invalid amounts without throwing`() {
        val items = listOf(transaction(1, ""), transaction(2, "not-a-number"), transaction(3, "2.50"))
        val base = dailyTotals(items, emptyMap(), "PLN").baseOrFail()
        assertAmount("2.50", base.incoming)
        assertAmount("0", base.outgoing)
    }

    @Test
    fun `omits invalid-only currencies and empty invalid input`() {
        val withValid = dailyTotals(
            listOf(transaction(1, "10", currency = "PLN"), transaction(2, "not-a-number", currency = "USD")),
            emptyMap(),
            "PLN",
        )
        assertAmount("10", withValid.baseOrFail().incoming)
        assertTrue(withValid.unconverted.isEmpty())

        val allInvalid = dailyTotals(
            listOf(transaction(1, "bad", currency = "PLN"), transaction(2, "", currency = "USD")),
            emptyMap(),
            "PLN",
        )
        assertNull(allInvalid.base)
        assertEquals(emptyList<DailyMovements>(), allInvalid.unconverted)
    }

    @Test
    fun `orders unconverted currencies ascending`() {
        val items = listOf(
            transaction(1, "10", currency = "PLN"),
            transaction(2, "5", currency = "USD"),
            transaction(3, "-3", currency = "EUR"),
            transaction(4, "-2", currency = "PLN"),
            transaction(5, "-1", currency = "USD"),
            transaction(6, "4", currency = "EUR"),
        )
        val totals = dailyTotals(items, emptyMap(), baseCurrency = null)
        assertEquals(listOf("EUR", "PLN", "USD"), totals.unconverted.map { it.currency })
        val byCurrency = totals.unconverted.associateBy { it.currency }
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
            val base = dailyTotals(
                listOf(transaction(1, "7.00", status = status), transaction(2, "-2.00", status = status)),
                emptyMap(),
                "PLN",
            ).baseOrFail()
            assertAmount("7.00", base.incoming)
            assertAmount("-2.00", base.outgoing)
        }
    }

    @Test
    fun `excludes rows in a transfer group`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "-100.00", transferGroup = "g1"),
                transaction(2, "-3.00"),
            ),
            emptyMap(),
            "PLN",
        )
        assertEquals(1, totals.excludedTransfers)
        assertAmount("-3.00", totals.baseOrFail().outgoing)
    }

    @Test
    fun `excludes rows in a TRANSFER-kind category`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "100.00", categoryId = 7L),
                transaction(2, "-3.00", categoryId = 8L),
            ),
            categories(7L to CategoryKind.TRANSFER, 8L to CategoryKind.EXPENSE),
            "PLN",
        )
        assertEquals(1, totals.excludedTransfers)
        assertAmount("-3.00", totals.baseOrFail().outgoing)
        assertAmount("0", totals.baseOrFail().incoming)
    }

    @Test
    fun `sums converted base amounts into one currency`() {
        val items = listOf(
            transaction(1, "-10.00", currency = "USD", baseAmount = "-9.00", baseCurrency = "EUR"),
            transaction(2, "-5.00", currency = "EUR"),
            transaction(3, "20.00", currency = "GBP", baseAmount = "23.00", baseCurrency = "EUR"),
        )
        val totals = dailyTotals(items, emptyMap(), "EUR")
        val base = totals.baseOrFail()
        assertEquals("EUR", base.currency)
        assertAmount("-14.00", base.outgoing)
        assertAmount("23.00", base.incoming)
        assertTrue(base.converted)
        assertTrue(totals.unconverted.isEmpty())
    }

    @Test
    fun `sums converted EUR and native PLN rows into one PLN total`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "-10.00", currency = "EUR", baseAmount = "-43.000000", baseCurrency = "PLN"),
                transaction(2, "-5.00", currency = "PLN"),
            ),
            emptyMap(),
            "PLN",
        )
        val base = totals.baseOrFail()
        assertEquals("PLN", base.currency)
        assertAmount("0", base.incoming)
        assertAmount("-48.00", base.outgoing)
        assertTrue(base.converted)
        assertTrue("no EUR bucket: ${totals.unconverted}", totals.unconverted.isEmpty())
    }

    @Test
    fun `keeps a row with no rate in its own currency bucket`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "-10.00", currency = "EUR"),
                transaction(2, "-50.00", currency = "UAH"),
            ),
            emptyMap(),
            "EUR",
        )
        assertAmount("-10.00", totals.baseOrFail().outgoing)
        val uah = totals.unconverted.single()
        assertEquals("UAH", uah.currency)
        assertAmount("-50.00", uah.outgoing)
    }

    @Test
    fun `rounds the base total once after summing`() {
        val items = listOf(
            transaction(1, "-1.00", currency = "USD", baseAmount = "0.004999", baseCurrency = "EUR"),
            transaction(2, "-1.00", currency = "USD", baseAmount = "0.004999", baseCurrency = "EUR"),
        )
        val base = dailyTotals(items, emptyMap(), "EUR").baseOrFail()
        // Individually each rounds to 0.00; only a single rounding after summing yields 0.01.
        assertAmount("0.01", base.incoming)
        assertAmount("0", base.outgoing)
    }

    @Test
    fun `treats a null baseCurrency as today's per-currency output`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "10.00", currency = "USD", baseAmount = "9.00", baseCurrency = "EUR"),
                transaction(2, "-5.00", currency = "PLN"),
            ),
            emptyMap(),
            baseCurrency = null,
        )
        assertNull(totals.base)
        assertEquals(listOf("PLN", "USD"), totals.unconverted.map { it.currency })
        assertAmount("10.00", totals.unconverted.single { it.currency == "USD" }.incoming)
        assertAmount("-5.00", totals.unconverted.single { it.currency == "PLN" }.outgoing)
    }

    @Test
    fun `totals wallet-currency rows when no baseAmount is present`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "10.00", currency = "EUR"),
                transaction(2, "-4.00", currency = "EUR"),
                transaction(3, "-7.00", currency = "USD"),
            ),
            emptyMap(),
            "EUR",
        )
        val base = totals.baseOrFail()
        assertAmount("10.00", base.incoming)
        assertAmount("-4.00", base.outgoing)
        assertEquals(false, base.converted)
        assertEquals(listOf("USD"), totals.unconverted.map { it.currency })
    }

    @Test
    fun `counts excluded transfers`() {
        val totals = dailyTotals(
            listOf(
                transaction(1, "100.00", categoryId = 7L),
                transaction(2, "-50.00", transferGroup = "g"),
                transaction(3, "-5.00", categoryId = 8L),
            ),
            categories(7L to CategoryKind.TRANSFER, 8L to CategoryKind.EXPENSE),
            "PLN",
        )
        assertEquals(2, totals.excludedTransfers)
        assertAmount("-5.00", totals.baseOrFail().outgoing)
    }

    @Test
    fun `keeps exact arithmetic on large amounts`() {
        val positive = dailyTotals(
            listOf(transaction(1, "99999999999999999999999.99"), transaction(2, "0.01")),
            emptyMap(),
            "PLN",
        ).baseOrFail()
        assertAmount("100000000000000000000000.00", positive.incoming)
        assertAmount("0", positive.outgoing)

        val negative = dailyTotals(
            listOf(transaction(1, "-99999999999999999999999.99"), transaction(2, "-0.01")),
            emptyMap(),
            "PLN",
        ).baseOrFail()
        assertAmount("0", negative.incoming)
        assertAmount("-100000000000000000000000.00", negative.outgoing)
    }

    @Test
    fun `recalculates on append and replacement`() {
        val initial = dailyTotals(listOf(transaction(1, "10"), transaction(2, "-2")), emptyMap(), "PLN").baseOrFail()
        assertAmount("10", initial.incoming)
        assertAmount("-2", initial.outgoing)

        val appended = dailyTotals(
            listOf(transaction(1, "10"), transaction(2, "-2"), transaction(3, "5")),
            emptyMap(),
            "PLN",
        ).baseOrFail()
        assertAmount("15", appended.incoming)
        assertAmount("-2", appended.outgoing)

        val replaced = dailyTotals(
            listOf(transaction(1, "20"), transaction(2, "-4"), transaction(3, "1")),
            emptyMap(),
            "PLN",
        ).baseOrFail()
        assertAmount("21", replaced.incoming)
        assertAmount("-4", replaced.outgoing)
    }
}
