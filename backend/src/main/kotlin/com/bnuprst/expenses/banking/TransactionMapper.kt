package com.bnuprst.expenses.banking

import com.bnuprst.expenses.transactions.NewTransaction
import java.security.MessageDigest
import java.time.LocalDate

object TransactionMapper {

    /**
     * Maps booked transactions; pending ones are skipped because banks often assign a different reference
     * once they book, which would create duplicates. Transactions without a bank reference get a content
     * hash, with an occurrence index so two identical purchases on the same day both survive.
     *
     * [seen] counts those hashes and must span every page of one account's fetch: identical rows can
     * land on different pages, and a per-page counter would give them the same id.
     */
    fun map(
        page: List<EbTransaction>,
        today: LocalDate = LocalDate.now(),
        seen: MutableMap<String, Int> = mutableMapOf(),
    ): List<NewTransaction> {
        return page.filter { it.status == null || it.status == "BOOK" }.map { t ->
            val debit = t.creditDebitIndicator.equals("DBIT", ignoreCase = true)
            val amount = t.transactionAmount.amount.toBigDecimal().abs().let { if (debit) it.negate() else it }
            val counterparty = (if (debit) t.creditor?.name else t.debtor?.name)?.trim()?.takeIf { it.isNotEmpty() }
            val description = t.remittanceInformation?.filter { it.isNotBlank() }?.joinToString(" ")?.takeIf { it.isNotBlank() }
                ?: t.note?.takeIf { it.isNotBlank() }
                ?: counterparty
                ?: t.bankTransactionCode?.description
                ?: "(no description)"
            val date = (t.bookingDate ?: t.valueDate ?: t.transactionDate)?.let(LocalDate::parse) ?: today
            val externalId = t.entryReference?.takeIf { it.isNotBlank() }
                ?: t.transactionId?.takeIf { it.isNotBlank() }
                ?: run {
                    val base = sha256("$date|${amount.toPlainString()}|${t.transactionAmount.currency}|$description|${counterparty.orEmpty()}")
                    val n = seen.merge(base, 1, Int::plus)!!
                    "h:$base:$n"
                }
            NewTransaction(
                externalId, date, amount, t.transactionAmount.currency, description.trim(), counterparty,
                transactionDate = parseDate(t.transactionDate),
                valueDate = parseDate(t.valueDate),
                counterpartyIban = (if (debit) t.creditorAccount else t.debtorAccount)?.iban?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() },
                paymentType = t.bankTransactionCode?.description?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Detail dates are informational, so one a bank sends in an odd format is dropped rather than failing the sync. */
    private fun parseDate(s: String?): LocalDate? = s?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
