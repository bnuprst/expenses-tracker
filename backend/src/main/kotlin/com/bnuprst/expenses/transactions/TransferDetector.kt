package com.bnuprst.expenses.transactions

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** One transaction reduced to what pairing needs. */
data class TransferLeg(
    val id: Long,
    val date: LocalDate,
    /** Signed. */
    val amount: BigDecimal,
    val currency: String,
    /**
     * The identified account inside the wallet: "bank:<bankAccountId>", else "src:<sourceAccount>".
     * **Null when the row's account is unknown**. A null-key leg never pairs: two unknown keys are
     * not provably different accounts, and an unknown key is not provably different from a known one.
     */
    val accountKey: String?,
    /** Digits of this leg's own account IBAN; null for imported rows, which have no BankAccounts row. */
    val ownIbanDigits: String?,
    /** Digits of the counterparty IBAN the bank reported for this leg, or null. */
    val counterpartyIbanDigits: String?,
    /** paymentType + description, lowercased, for keyword evidence. */
    val text: String,
    /** The user explicitly assigned a non-TRANSFER category to this row. */
    val manualNonTransfer: Boolean,
)

data class TransferPair(val outId: Long, val inId: Long, val evidence: String)

/**
 * Evidence-based pairing of the two legs of a movement between the user's own accounts. Amount and
 * date alone never pair anything: a candidate also needs matching IBAN digits, a transfer/exchange
 * keyword, or the shape of one in-app currency exchange booked twice on the same account.
 */
object TransferDetector {
    const val MAX_DAYS = 3L

    /** Bank spread on an in-app exchange. */
    val FX_TOLERANCE: BigDecimal = BigDecimal("0.02")

    /** Minimum matching IBAN digits for E1; shorter suffixes collide too easily. */
    const val IBAN_MIN_DIGITS = 4

    /** Lowercase substrings that mark a row as a transfer/exchange in the languages this app ships. */
    val TEXT_MARKERS = listOf(
        "transfer", "own account", "between accounts", "exchange", "fx ", "currency conversion",
        "przelew", "wymiana walut", "przewalutowanie",
        "переказ", "обмін валют", "між рахунками",
    )

    /** Deterministic pairing. [convert] gives a leg's wallet-currency value, or null when no rate. */
    fun pair(legs: List<TransferLeg>, convert: (TransferLeg) -> BigDecimal?): List<TransferPair> {
        val usable = legs
            .filter { !it.manualNonTransfer && it.amount.signum() != 0 && it.accountKey != null }
            .sortedWith(compareBy({ it.date }, { it.id }))
        val outgoing = usable.filter { it.amount.signum() < 0 }
        val incoming = usable.filter { it.amount.signum() > 0 }

        val pairedIds = mutableSetOf<Long>()
        val formed = mutableListOf<Candidate>()
        for (out in outgoing) {
            if (out.id in pairedIds) continue
            var best: Candidate? = null
            for (inc in incoming) {
                if (inc.id in pairedIds) continue
                val cand = candidate(out, inc, convert) ?: continue
                // A same-account exchange (E3) is both legs of one account and cannot be a
                // pass-through, so the guard applies only to movements between different accounts.
                if (!cand.sameAccount && passThroughBlocked(out, inc, formed, convert)) continue
                if (best == null || cand < best!!) best = cand
            }
            val chosen = best ?: continue
            pairedIds += chosen.out.id
            pairedIds += chosen.inc.id
            formed += chosen
        }
        return formed.map { TransferPair(it.out.id, it.inc.id, it.evidence) }.sortedBy { it.outId }
    }

    /**
     * The A->B->C guard, scoped to the movement itself: refuse a candidate when an already-paired
     * leg in one of its accounts is a direct pass-through of this leg (same account, within
     * [MAX_DAYS], corresponding amounts) — B would both receive and send the same money, so C is
     * left alone rather than mispaired.
     *
     * It deliberately does not chain through unrelated same-account activity. An earlier version
     * union-found every same-account pair of legs within [MAX_DAYS], so ordinary purchases in B
     * bridged an A->B transfer to a B->A transfer weeks later and permanently reserved B as a
     * destination, leaving the valid later transfer unflagged from income/expense.
     */
    private fun passThroughBlocked(
        out: TransferLeg,
        inc: TransferLeg,
        formed: List<Candidate>,
        convert: (TransferLeg) -> BigDecimal?,
    ): Boolean {
        val outAccount = out.accountKey ?: return true
        val inAccount = inc.accountKey ?: return true
        for (pair in formed) {
            if (pair.inc.accountKey == outAccount && sameMovement(pair.inc, out, convert)) return true
            if (pair.out.accountKey == inAccount && sameMovement(pair.out, inc, convert)) return true
        }
        return false
    }

    /** Whether [a] and [b] are two legs of one pass-through: close in time with corresponding amounts. */
    private fun sameMovement(a: TransferLeg, b: TransferLeg, convert: (TransferLeg) -> BigDecimal?): Boolean {
        if (abs(ChronoUnit.DAYS.between(a.date, b.date)) > MAX_DAYS) return false
        return amountDifference(a, b, convert) != null
    }

    /** A surviving pair candidate, or null when the account/date/amount/evidence rules reject it. */
    private fun candidate(
        out: TransferLeg,
        inc: TransferLeg,
        convert: (TransferLeg) -> BigDecimal?,
    ): Candidate? {
        val outAccount = out.accountKey ?: return null
        val inAccount = inc.accountKey ?: return null
        val daysApart = abs(ChronoUnit.DAYS.between(out.date, inc.date))
        if (daysApart > MAX_DAYS) return null

        val sameAccount = outAccount == inAccount
        // Different accounts must both be identified. The same account is only a transfer when the
        // currencies differ (an in-app exchange).
        if (sameAccount && out.currency == inc.currency) return null

        val relativeDifference = amountDifference(out, inc, convert) ?: return null
        val evidence = evidence(out, inc, sameAccount) ?: return null
        return Candidate(out, inc, daysApart, relativeDifference, evidence, sameAccount)
    }

    /** Null when the amounts do not correspond; otherwise the relative converted difference (0 for equal currencies). */
    private fun amountDifference(out: TransferLeg, inc: TransferLeg, convert: (TransferLeg) -> BigDecimal?): BigDecimal? {
        if (out.currency == inc.currency) {
            return if (out.amount.abs().compareTo(inc.amount.abs()) == 0) BigDecimal.ZERO else null
        }
        val convertedOut = convert(out) ?: return null
        val convertedIn = convert(inc) ?: return null
        val a = convertedOut.abs()
        val b = convertedIn.abs()
        val largest = a.max(b)
        if (largest.signum() == 0) return null
        val difference = (a - b).abs()
        if (difference > FX_TOLERANCE.multiply(largest)) return null
        return difference.divide(largest, MathContext(20, RoundingMode.HALF_UP))
    }

    private fun evidence(out: TransferLeg, inc: TransferLeg, sameAccount: Boolean): String? {
        if (ibanMatches(out.counterpartyIbanDigits, inc.ownIbanDigits) ||
            ibanMatches(inc.counterpartyIbanDigits, out.ownIbanDigits)
        ) return "E1"
        if (containsMarker(out.text) || containsMarker(inc.text)) return "E2"
        if (sameAccount && out.currency != inc.currency && out.date == inc.date) return "E3"
        return null
    }

    private fun containsMarker(text: String): Boolean = TEXT_MARKERS.any { text.contains(it) }

    /** One IBAN's digits end with the other's (both at least [IBAN_MIN_DIGITS] long). */
    private fun ibanMatches(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val left = a.filter(Char::isDigit)
        val right = b.filter(Char::isDigit)
        val compared = minOf(left.length, right.length)
        if (compared < IBAN_MIN_DIGITS) return false
        return left.takeLast(compared) == right.takeLast(compared)
    }

    private data class Candidate(
        val out: TransferLeg,
        val inc: TransferLeg,
        val daysApart: Long,
        val relativeDifference: BigDecimal,
        val evidence: String,
        val sameAccount: Boolean,
    ) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int {
            val byDate = daysApart.compareTo(other.daysApart)
            if (byDate != 0) return byDate
            val byAmount = relativeDifference.compareTo(other.relativeDifference)
            if (byAmount != 0) return byAmount
            return inc.id.compareTo(other.inc.id)
        }
    }
}
