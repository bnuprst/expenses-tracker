package com.bnuprst.expenses.transactions

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferDetectorTest {
    private val day = LocalDate.parse("2026-10-05")
    private val nextDay = LocalDate.parse("2026-10-06")

    private fun leg(
        id: Long,
        amount: String,
        date: LocalDate = day,
        currency: String = "EUR",
        accountKey: String? = "bank:1",
        ownIban: String? = null,
        counterpartyIban: String? = null,
        text: String = "",
        manualNonTransfer: Boolean = false,
    ) = TransferLeg(
        id = id,
        date = date,
        amount = BigDecimal(amount),
        currency = currency,
        accountKey = accountKey,
        ownIbanDigits = ownIban,
        counterpartyIbanDigits = counterpartyIban,
        text = text,
        manualNonTransfer = manualNonTransfer,
    )

    private fun pairIds(legs: List<TransferLeg>, convert: (TransferLeg) -> BigDecimal? = { null }) =
        TransferDetector.pair(legs, convert).map { it.outId to it.inId }.toSet()

    private fun pairOf(pair: TransferPair) = pair.outId to pair.inId

    @Test
    fun `two identical moves on one day pair one-to-one without crossing`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(3, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(4, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
        )
        assertEquals(setOf(1L to 3L, 2L to 4L), pairIds(legs))
    }

    @Test
    fun `an A to B to C chain pairs A with B and leaves C unflagged`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(3, "-100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "8888"),
            leg(4, "100", accountKey = "bank:C", ownIban = "8888", counterpartyIban = "9999"),
        )
        assertEquals(setOf(1L to 2L), pairIds(legs))
    }

    @Test
    fun `two independent exchanges on one account pair separately`() {
        val later = day.plusDays(10)
        val convert: (TransferLeg) -> BigDecimal? = { l ->
            if (l.currency == "EUR") l.amount
            else l.amount.divide(BigDecimal("1.08"), MathContext(20, RoundingMode.HALF_UP))
        }
        val legs = listOf(
            leg(1, "-100", currency = "EUR", accountKey = "bank:A"),
            leg(2, "108", currency = "USD", accountKey = "bank:A"),
            leg(3, "-100", date = later, currency = "EUR", accountKey = "bank:A"),
            leg(4, "108", date = later, currency = "USD", accountKey = "bank:A"),
        )
        assertEquals(setOf(1L to 2L, 3L to 4L), pairIds(legs, convert))
    }

    @Test
    fun `transfers in opposite directions on separate dates pair separately`() {
        val later = day.plusDays(10)
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(3, "-100", date = later, accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(4, "100", date = later, accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
        )
        assertEquals(setOf(1L to 2L, 3L to 4L), pairIds(legs))
    }

    @Test
    fun `intervening purchases on the return account do not suppress a later reverse transfer`() {
        val later = day.plusDays(10)
        val legs = listOf(
            // A -> B on day 0.
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            // Ordinary, unmatched purchases in B between the two transfers. They must not bridge
            // A -> B to B -> A and keep B reserved as a destination for weeks.
            leg(3, "-20", date = day.plusDays(3), accountKey = "bank:B", ownIban = "9999"),
            leg(4, "-20", date = day.plusDays(6), accountKey = "bank:B", ownIban = "9999"),
            leg(5, "-20", date = day.plusDays(9), accountKey = "bank:B", ownIban = "9999"),
            // B -> A on day 10.
            leg(6, "-100", date = later, accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(7, "100", date = later, accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
        )
        assertEquals(setOf(1L to 2L, 6L to 7L), pairIds(legs))
    }

    @Test
    fun `a same-account same-currency opposite pair is a refund, not a transfer`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "1111"),
            leg(2, "100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "1111"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `a cross-currency leg with no rate is not paired`() {
        val legs = listOf(
            leg(1, "-100", currency = "EUR", accountKey = "bank:A"),
            leg(2, "108", currency = "USD", accountKey = "bank:B"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `legs more than MAX_DAYS apart are not paired`() {
        val far = day.plusDays(TransferDetector.MAX_DAYS + 1)
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "100", date = far, accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `unrelated same-amount transactions in different accounts are not paired`() {
        val legs = listOf(
            leg(1, "-2000", currency = "PLN", accountKey = "bank:A", text = "salary top up"),
            leg(2, "2000", currency = "PLN", accountKey = "bank:B", text = "rent payment"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `a leg with an unknown account key is never paired even with transfer text`() {
        val legs = listOf(
            leg(1, "-100", accountKey = null, text = "transfer to savings"),
            leg(2, "100", accountKey = "bank:B", ownIban = "9999", text = "transfer from main"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `pairs a cross-currency exchange regardless of leg order`() {
        val convert: (TransferLeg) -> BigDecimal? = { l ->
            if (l.currency == "EUR") l.amount
            else l.amount.divide(BigDecimal("1.08"), MathContext(20, RoundingMode.HALF_UP))
        }
        val out = leg(1, "-100", currency = "EUR", accountKey = "bank:A")
        val inc = leg(2, "108", currency = "USD", accountKey = "bank:A")

        assertEquals(setOf(1L to 2L), pairIds(listOf(out, inc), convert))
        assertEquals(setOf(1L to 2L), pairIds(listOf(inc, out), convert))

        val swappedSigns = listOf(out.copy(amount = BigDecimal("100"), id = 3), inc.copy(amount = BigDecimal("-108"), id = 4))
        assertEquals(setOf(4L to 3L), pairIds(swappedSigns, convert))
    }

    @Test
    fun `a keyword in two identified accounts pairs without IBAN evidence`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", text = "przelew własny"),
            leg(2, "100", accountKey = "bank:B", text = "incoming"),
        )
        assertEquals(setOf(1L to 2L), pairIds(legs))
    }

    @Test
    fun `a user non-transfer override excludes the leg`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999", manualNonTransfer = true),
            leg(2, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
        )
        assertTrue(pairIds(legs).isEmpty())
    }

    @Test
    fun `shuffling the input yields the same pairs`() {
        val legs = listOf(
            leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(2, "-100", date = nextDay, accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
            leg(3, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            leg(4, "100", date = nextDay, accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
        )
        val expected = pairIds(legs)
        assertEquals(expected, pairIds(legs.reversed()))
        assertEquals(expected, pairIds(listOf(legs[2], legs[0], legs[3], legs[1])))
    }

    @Test
    fun `evidence labels name the rule that fired`() {
        val e1 = TransferDetector.pair(
            listOf(
                leg(1, "-100", accountKey = "bank:A", ownIban = "1111", counterpartyIban = "9999"),
                leg(2, "100", accountKey = "bank:B", ownIban = "9999", counterpartyIban = "1111"),
            ),
        ) { null }
        assertEquals("E1", e1.single().evidence)

        val e3 = TransferDetector.pair(
            listOf(
                leg(3, "-100", currency = "EUR", accountKey = "bank:A"),
                leg(4, "108", currency = "USD", accountKey = "bank:A"),
            ),
        ) { l -> if (l.currency == "EUR") l.amount else l.amount.divide(BigDecimal("1.08"), MathContext(20, RoundingMode.HALF_UP)) }
        assertEquals("E3", e3.single().evidence)
    }
}
