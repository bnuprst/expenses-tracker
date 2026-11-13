package com.bnuprst.expenses.android.ui

import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.TransactionDto
import java.math.BigDecimal
import java.math.RoundingMode

internal data class DailyMovements(
    val currency: String,
    val incoming: BigDecimal,
    val outgoing: BigDecimal,
    /** True when at least one row in this bucket came from another currency. */
    val converted: Boolean = false,
)

internal data class DailyTotals(
    /** Totals in the wallet currency; null when nothing on the day could be expressed in it. */
    val base: DailyMovements?,
    /** Per-currency totals for rows with no rate, so money is never hidden. */
    val unconverted: List<DailyMovements>,
    /** Rows left out as movements between the user's own accounts. */
    val excludedTransfers: Int,
)

/**
 * Exact per-currency totals for a day. Rows with a converted base amount are summed into one
 * wallet-currency pair; rows with no rate stay in their own currency instead of being counted 1:1.
 * Internal transfers contribute nothing.
 */
internal fun dailyTotals(
    items: List<TransactionDto>,
    categories: Map<Long, CategoryDto>,
    baseCurrency: String?,
): DailyTotals {
    var baseIncoming = BigDecimal.ZERO
    var baseOutgoing = BigDecimal.ZERO
    var hasBaseRow = false
    var converted = false
    val unconverted = linkedMapOf<String, Array<BigDecimal>>()
    var excludedTransfers = 0

    for (item in items) {
        val category = item.categoryId?.let { categories[it] }
        if (item.transferGroup != null || category?.kind == CategoryKind.TRANSFER) {
            excludedTransfers++
            continue
        }
        val amount = item.amount.toBigDecimalOrNull() ?: continue
        val baseAmount = if (baseCurrency != null && item.baseCurrency == baseCurrency) {
            item.baseAmount?.toBigDecimalOrNull()
        } else null

        when {
            baseAmount != null -> {
                hasBaseRow = true
                if (item.currency != baseCurrency) converted = true
                if (baseAmount.signum() > 0) baseIncoming = baseIncoming.add(baseAmount)
                else if (baseAmount.signum() < 0) baseOutgoing = baseOutgoing.add(baseAmount)
            }
            item.currency == baseCurrency -> {
                hasBaseRow = true
                if (amount.signum() > 0) baseIncoming = baseIncoming.add(amount)
                else if (amount.signum() < 0) baseOutgoing = baseOutgoing.add(amount)
            }
            else -> {
                val bucket = unconverted.getOrPut(item.currency) { arrayOf(BigDecimal.ZERO, BigDecimal.ZERO) }
                if (amount.signum() > 0) bucket[0] = bucket[0].add(amount)
                else if (amount.signum() < 0) bucket[1] = bucket[1].add(amount)
            }
        }
    }

    val base = if (hasBaseRow) {
        DailyMovements(
            currency = baseCurrency.orEmpty(),
            // Rounded once, after summing: converted base amounts carry six decimals.
            incoming = baseIncoming.setScale(2, RoundingMode.HALF_UP),
            outgoing = baseOutgoing.setScale(2, RoundingMode.HALF_UP),
            converted = converted,
        )
    } else null
    val others = unconverted.entries.sortedBy { it.key }
        .map { (currency, amounts) -> DailyMovements(currency, amounts[0], amounts[1]) }
    return DailyTotals(base = base, unconverted = others, excludedTransfers = excludedTransfers)
}
