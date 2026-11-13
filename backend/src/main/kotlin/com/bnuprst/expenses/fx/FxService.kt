package com.bnuprst.expenses.fx

import com.bnuprst.expenses.config.FxConfig
import com.bnuprst.expenses.db.FxCoverage
import com.bnuprst.expenses.db.FxRates
import com.bnuprst.expenses.db.dbQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** An immutable rate snapshot: pure, so conversion is unit-testable without a DB. */
class FxTable internal constructor(
    private val providerBase: String,
    /** currency -> as_of -> units of that currency per 1 unit of providerBase. */
    private val quotes: Map<String, Map<LocalDate, BigDecimal>>,
    dayList: List<LocalDate>,
    private val maxStaleDays: Long,
    val enabled: Boolean,
) {
    /** Every as_of that has at least one quote, descending. */
    private val days: List<LocalDate> = dayList.distinct().sortedDescending()
    private val daySet: Set<LocalDate> = days.toSet()

    /** [amount] of [currency] expressed in [target] on [date], with the rate date used; null if unknown. */
    fun convert(amount: BigDecimal, currency: String, target: String, date: LocalDate): Converted? {
        if (!enabled) return null
        val from = currency.uppercase()
        val to = target.uppercase()
        if (from == to) return Converted(amount, date)

        fun q(code: String, day: LocalDate): BigDecimal? =
            if (code == providerBase && day in daySet) BigDecimal.ONE else quotes[code]?.get(day)

        // The greatest quoted day at or before the booking date where both legs are known. If the
        // provider base is one leg, its implicit rate of 1 still requires a day with a publication.
        val rateDay = days.firstOrNull { it <= date && q(from, it) != null && q(to, it) != null } ?: return null
        if (ChronoUnit.DAYS.between(rateDay, date) > maxStaleDays) return null
        val converted = amount.multiply(q(to, rateDay)!!)
            .divide(q(from, rateDay)!!, MathContext(20, RoundingMode.HALF_UP))
            .setScale(6, RoundingMode.HALF_UP)
        return Converted(converted, rateDay)
    }

    companion object {
        fun disabled(providerBase: String): FxTable =
            FxTable(providerBase, emptyMap(), emptyList(), 0, false)
    }
}

data class Converted(val amount: BigDecimal, val rateDate: LocalDate)

private data class RateRow(val asOf: LocalDate, val base: String, val quote: String, val rate: BigDecimal)
private data class CoverageRow(val base: String, val asOf: LocalDate, val published: Boolean, val fetchedAt: OffsetDateTime)

/**
 * Stores, covers and serves daily FX rates. Rates come from an [FxProvider] and are kept in
 * `fx_rates`; `fx_coverage` records which calendar days have already been asked about so a weekend
 * (no publication) is not refetched forever.
 */
class FxService(
    private val provider: FxProvider?,
    private val config: FxConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(FxService::class.java)
    private var schedulerScope: CoroutineScope? = null

    val enabled: Boolean get() = provider != null && config.enabled
    val maxStaleDays: Long get() = config.maxStaleDays

    /**
     * Called with the newly covered dates after a successful fetch, always dispatched on the
     * scheduler scope — never inline on the caller's stack.
     */
    var onRatesStored: (suspend (List<LocalDate>) -> Unit)? = null

    /** Rate snapshot covering [from]..[to] for [currencies]; nothing is fetched here. */
    suspend fun table(currencies: Set<String>, from: LocalDate, to: LocalDate): FxTable {
        if (!enabled) return FxTable.disabled(config.base)
        val wanted = currencies.map { it.uppercase() }.toSet()
        if (wanted.isEmpty()) return FxTable(config.base, emptyMap(), emptyList(), config.maxStaleDays, true)
        val lookupFrom = from.minusDays(config.maxStaleDays)
        val rows = dbQuery {
            FxRates.selectAll().where {
                (FxRates.base eq config.base) and (FxRates.asOf greaterEq lookupFrom) and
                    (FxRates.asOf lessEq to) and (FxRates.quote inList wanted.toList())
            }.toList()
        }
        val quotes = HashMap<String, MutableMap<LocalDate, BigDecimal>>()
        for (row in rows) {
            quotes.getOrPut(row[FxRates.quote]) { HashMap() }[row[FxRates.asOf]] = row[FxRates.rate]
        }
        val days = rows.map { it[FxRates.asOf] }.distinct().sortedDescending()
        return FxTable(config.base, quotes, days, config.maxStaleDays, true)
    }

    /**
     * Fetches every calendar day in [from]-maxStaleDays .. [to] that is not yet covered, in at most
     * backfillDays-sized contiguous chunks. Best effort: logs and returns on failure.
     * Returns the dates for which new rates were actually stored (empty when nothing changed).
     *
     * [notify] = false suppresses only the *inline* onRatesStored callback; it is used by callers
     * that hold a per-wallet scan lock, so the callback can never re-enter their own scan. Such a
     * caller MUST dispatch the returned dates itself once it has released the lock.
     */
    suspend fun ensureCovered(from: LocalDate, to: LocalDate, notify: Boolean = true): List<LocalDate> {
        val source = provider ?: return emptyList()
        if (!config.enabled) return emptyList()
        val today = LocalDate.now(clock)
        val windowEnd = minOf(to, today)
        val windowStart = from.minusDays(config.maxStaleDays)
        if (windowStart > windowEnd) return emptyList()

        val now = OffsetDateTime.now(clock)
        val coverage = dbQuery {
            FxCoverage.selectAll().where {
                (FxCoverage.base eq config.base) and (FxCoverage.asOf greaterEq windowStart) and
                    (FxCoverage.asOf lessEq windowEnd)
            }.associate { it[FxCoverage.asOf] to (it[FxCoverage.published] to it[FxCoverage.fetchedAt]) }
        }
        val provisionalFrom = today.minusDays(config.provisionalDays)
        val needed = generateSequence(windowStart) { it.plusDays(1) }.takeWhile { it <= windowEnd }
            .filter { day ->
                val covered = coverage[day]
                covered == null ||
                    (!covered.first && day >= provisionalFrom && !covered.second.isAfter(now.minus(config.refreshInterval)))
            }
            .toList()
        if (needed.isEmpty()) return emptyList()

        val stored = mutableListOf<LocalDate>()
        val chunkDays = config.backfillDays.coerceAtLeast(1)
        for ((start, end) in contiguous(needed)) {
            var chunkStart = start
            while (chunkStart <= end) {
                val chunkEnd = minOf(end, chunkStart.plusDays(chunkDays - 1))
                val fetched = runCatching { source.rates(chunkStart, chunkEnd, config.base) }.getOrElse { e ->
                    // A rate outage must never fail a transactions request, and writes no coverage
                    // rows for this interval so the days are retried immediately next time.
                    log.warn("FX fetch {}..{} failed", chunkStart, chunkEnd, e)
                    null
                }
                if (fetched != null) {
                    persist(chunkStart, chunkEnd, fetched)
                    stored += fetched.map { it.asOf }
                }
                chunkStart = chunkEnd.plusDays(1)
            }
        }
        val result = stored.distinct().sorted()
        if (notify && result.isNotEmpty()) notifyRatesStored(result)
        return result
    }

    /** refreshInterval loop that covers the trailing window; mirrors startSyncScheduler. */
    fun startScheduler(scope: CoroutineScope): Job {
        schedulerScope = scope
        return scope.launch {
            delay(30_000)
            while (isActive) {
                val today = LocalDate.now(clock)
                runCatching { ensureCovered(today.minusDays(config.backfillDays), today) }
                    .onFailure { log.warn("Scheduled FX refresh failed", it) }
                delay(config.refreshInterval.toMillis())
            }
        }
    }

    /**
     * Dispatches [dates] to [onRatesStored] on the scheduler scope. Public so a caller that fetched
     * with `notify = false` can fire the notification after unlocking. No-op on an empty list.
     */
    fun notifyRatesStored(dates: List<LocalDate>) {
        if (dates.isEmpty()) return
        val scope = schedulerScope
        val callback = onRatesStored
        if (scope == null || callback == null) {
            log.debug("FX scheduler is not running; dropping rate-arrival notification for {}", dates)
            return
        }
        scope.launch {
            runCatching { callback(dates) }.onFailure { log.warn("FX rate-arrival rescan failed", it) }
        }
    }

    private suspend fun persist(from: LocalDate, to: LocalDate, days: List<FxDay>) {
        val source = provider?.source ?: return
        val now = OffsetDateTime.now(clock)
        val daySet = days.map { it.asOf }.toSet()
        val rateRows = days.flatMap { day ->
            val base = day.base.ifBlank { config.base }
            day.quotes.map { (quote, rate) -> RateRow(day.asOf, base, quote.uppercase(), rate) }
        }
        val coverageRows = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
            .map { CoverageRow(config.base, it, it in daySet, now) }
            .toList()
        dbQuery {
            if (rateRows.isNotEmpty()) {
                FxRates.batchUpsert(rateRows, FxRates.asOf, FxRates.base, FxRates.quote) { row ->
                    this[FxRates.asOf] = row.asOf
                    this[FxRates.base] = row.base
                    this[FxRates.quote] = row.quote
                    this[FxRates.rate] = row.rate
                    this[FxRates.rateSource] = source
                }
            }
            FxCoverage.batchUpsert(coverageRows, FxCoverage.base, FxCoverage.asOf) { row ->
                this[FxCoverage.base] = row.base
                this[FxCoverage.asOf] = row.asOf
                this[FxCoverage.published] = row.published
                this[FxCoverage.fetchedAt] = row.fetchedAt
            }
        }
    }

    private fun contiguous(days: List<LocalDate>): List<Pair<LocalDate, LocalDate>> {
        val out = mutableListOf<Pair<LocalDate, LocalDate>>()
        var start: LocalDate? = null
        var previous: LocalDate? = null
        for (day in days) {
            val s = start
            val p = previous
            if (s == null || p == null) {
                start = day
                previous = day
            } else if (day == p.plusDays(1)) {
                previous = day
            } else {
                out += s to p
                start = day
                previous = day
            }
        }
        val s = start
        val p = previous
        if (s != null && p != null) out += s to p
        return out
    }
}
