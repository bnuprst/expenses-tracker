package com.bnuprst.expenses.fx

import java.math.BigDecimal
import java.time.LocalDate

/** One published day: [quotes] units of each currency per 1 unit of [base]. */
data class FxDay(val asOf: LocalDate, val base: String, val quotes: Map<String, BigDecimal>)

class FxUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A daily-rate source. Deliberately narrow so a second provider (NBU for UAH, exchangerate.host)
 * can be added without touching FxService or FxTable.
 */
interface FxProvider {
    val source: String

    /** Rates published in [from]..[to]; days the provider does not publish are simply absent. */
    suspend fun rates(from: LocalDate, to: LocalDate, base: String): List<FxDay>
}
