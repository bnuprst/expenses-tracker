package com.bnuprst.expenses.android.ui

import com.bnuprst.expenses.shared.TransactionDto
import java.math.BigDecimal

internal data class DailyMovements(
    val currency: String,
    val incoming: BigDecimal,
    val outgoing: BigDecimal,
)

/** Exact per-currency incoming/outgoing totals for a day, without FX conversion or rounding. */
internal fun dailyMovements(items: List<TransactionDto>): List<DailyMovements> {
    return items
        .mapNotNull { item -> item.amount.toBigDecimalOrNull()?.let { item.currency to it } }
        .groupBy { (currency, _) -> currency }
        .map { (currency, amounts) ->
            var incoming = BigDecimal.ZERO
            var outgoing = BigDecimal.ZERO
            for ((_, amount) in amounts) {
                when {
                    amount.signum() > 0 -> incoming = incoming.add(amount)
                    amount.signum() < 0 -> outgoing = outgoing.add(amount)
                }
            }
            DailyMovements(currency, incoming, outgoing)
        }
        .sortedBy { it.currency }
}
