package com.bnuprst.expenses.fx

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FxTableTest {
    private val friday = LocalDate.parse("2026-10-09")
    private val saturday = LocalDate.parse("2026-10-10")
    private val sunday = LocalDate.parse("2026-10-11")

    private fun table(
        quotes: Map<String, Map<LocalDate, BigDecimal>>,
        days: List<LocalDate> = quotes.values.flatMap { it.keys }.distinct().sortedDescending(),
        maxStale: Long = 7,
    ) = FxTable("EUR", quotes, days, maxStale, true)

    private fun assertAmount(expected: String, actual: BigDecimal?) {
        assertNotNull(actual, "expected $expected, got null")
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")
    }

    @Test
    fun `same currency is an identity with no lookup`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))))
        val converted = t.convert(BigDecimal("123.45"), "PLN", "PLN", sunday)
        assertNotNull(converted)
        assertAmount("123.45", converted.amount)
        assertEquals(sunday, converted.rateDate)
    }

    @Test
    fun `converts a provider-base row into a non-base wallet currency using the implicit rate of 1`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))))
        val converted = t.convert(BigDecimal("10"), "EUR", "PLN", friday)
        assertNotNull(converted)
        assertAmount("43.000000", converted.amount)
        assertEquals(friday, converted.rateDate)
    }

    @Test
    fun `converts a quoted row into a provider-base wallet currency`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))))
        val converted = t.convert(BigDecimal("43"), "PLN", "EUR", friday)
        assertNotNull(converted)
        assertAmount("10.000000", converted.amount)
    }

    @Test
    fun `cross rate equals amount times target quote over source quote`() {
        val quotes = mapOf(
            "PLN" to mapOf(friday to BigDecimal("4.30")),
            "USD" to mapOf(friday to BigDecimal("1.10")),
        )
        val t = table(quotes)
        val converted = t.convert(BigDecimal("100"), "PLN", "USD", friday)
        assertNotNull(converted)
        val expected = BigDecimal("100").multiply(BigDecimal("1.10"))
            .divide(BigDecimal("4.30"), java.math.MathContext(20, java.math.RoundingMode.HALF_UP))
            .setScale(6, java.math.RoundingMode.HALF_UP)
        assertAmount(expected.toPlainString(), converted.amount)
    }

    @Test
    fun `friday's rate serves saturday and sunday and reports the friday date`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))))
        assertEquals(friday, t.convert(BigDecimal.ONE, "PLN", "EUR", saturday)?.rateDate)
        assertEquals(friday, t.convert(BigDecimal.ONE, "PLN", "EUR", sunday)?.rateDate)
    }

    @Test
    fun `a rate older than maxStaleDays is not used`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))), maxStale = 1)
        assertNull(t.convert(BigDecimal.ONE, "PLN", "EUR", sunday))
    }

    @Test
    fun `a currency with no rate returns null`() {
        val t = table(mapOf("PLN" to mapOf(friday to BigDecimal("4.30"))))
        assertNull(t.convert(BigDecimal.ONE, "UAH", "EUR", friday))
    }

    @Test
    fun `a disabled table converts nothing, not even the wallet currency`() {
        val t = FxTable.disabled("EUR")
        assertNull(t.convert(BigDecimal.ONE, "EUR", "EUR", friday))
        assertNull(t.convert(BigDecimal.ONE, "PLN", "EUR", friday))
    }

    @Test
    fun `a day where only one leg is quoted steps back to a day with both`() {
        val thursday = LocalDate.parse("2026-10-08")
        val quotes = mapOf(
            "PLN" to mapOf(thursday to BigDecimal("4.30"), friday to BigDecimal("4.40")),
            "USD" to mapOf(thursday to BigDecimal("1.10")),
        )
        val t = table(quotes, days = listOf(friday, thursday))
        val converted = t.convert(BigDecimal("100"), "PLN", "USD", friday)
        assertNotNull(converted)
        assertEquals(thursday, converted.rateDate)
        // 100 * 1.10 / 4.30
        val expected = BigDecimal("100").multiply(BigDecimal("1.10"))
            .divide(BigDecimal("4.30"), java.math.MathContext(20, java.math.RoundingMode.HALF_UP))
            .setScale(6, java.math.RoundingMode.HALF_UP)
        assertAmount(expected.toPlainString(), converted.amount)
    }
}
