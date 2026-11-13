package com.bnuprst.expenses.transactions

import com.bnuprst.expenses.db.BankAccounts
import com.bnuprst.expenses.db.Categories
import com.bnuprst.expenses.db.Transactions
import com.bnuprst.expenses.db.Wallets
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.fx.FxService
import com.bnuprst.expenses.fx.FxTable
import com.bnuprst.expenses.shared.CategoryKind
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

/**
 * Recomputes transfer groups for a wallet. Pairing is evidence-based (see [TransferDetector]);
 * every affected group is cleared in full and rewritten atomically under the shared [WalletLocks]
 * mutex, so a scan can never leave one leg of a group flagged and the other free.
 */
class TransferScanService(private val fx: FxService, private val locks: WalletLocks) {
    private val log = LoggerFactory.getLogger(TransferScanService::class.java)

    private data class Window(val from: LocalDate, val to: LocalDate, val groups: Set<String>)

    /**
     * Recomputes transfer groups for [walletId] around [from]..[to]. Idempotent.
     * [propagateRates] = true (the default) means: if this scan had to fetch new rates, every other
     * wallet that those rates could now serve is rescanned afterwards. Rescans started *by* that
     * propagation pass themselves pass false, so one fetch causes exactly one propagation round.
     * Returns the pairs written.
     */
    suspend fun scan(walletId: Long, from: LocalDate, to: LocalDate, propagateRates: Boolean = true): Int {
        if (from > to) return 0
        var window = closure(walletId, from, to)
        val storedDates = mutableListOf<LocalDate>()
        fetchRates(window, storedDates)

        var attempts = 0
        var paired = 0
        while (true) {
            var wider: Window? = null
            val result: Int? = locks.withWallet(walletId) {
                // Recompute the closure of step 1 under the lock from the same requested range:
                // rows may have changed since the read-only pass.
                val closed = dbQuery { closureTx(walletId, from, to) }
                if ((closed.from < window.from || closed.to > window.to) && attempts < MAX_RATE_RETRIES) {
                    wider = closed
                    return@withWallet null
                }
                val (walletCurrency, currencies) = currenciesInWindow(walletId, closed)
                val table = fx.table(currencies + walletCurrency, closed.from, closed.to)
                dbQuery { rewrite(walletId, closed, table, walletCurrency) }
            }
            if (result == null) {
                val next = wider ?: break
                window = next
                attempts++
                fetchRates(window, storedDates)
                continue
            }
            paired = result
            break
        }

        // Deferred propagation, after the lock is released. A notify=false fetch marks those days
        // covered, so a dropped notification would strand every other wallet's unpaired rows.
        if (storedDates.isNotEmpty()) {
            if (propagateRates) fx.notifyRatesStored(storedDates.distinct().sorted())
            // A propagation round's own fetch is inside the range that round already covers, so it is
            // deliberately not propagated again.
        }
        return paired
    }

    /** One pass over all history of every wallet; best effort, logged. */
    suspend fun scanAll() {
        val walletIds = dbQuery { Wallets.select(Wallets.id).map { it[Wallets.id] } }
        for (walletId in walletIds) {
            val range = dbQuery { bookingRange(walletId) } ?: continue
            runCatching { scan(walletId, range.first, range.second) }
                .onFailure { log.error("Transfer scan for wallet {} failed", walletId, it) }
        }
    }

    /** Rescans every affected wallet over the booking dates the newly arrived rates can serve. */
    suspend fun rescanForRates(dates: List<LocalDate>, exceptWallet: Long? = null) {
        if (dates.isEmpty()) return
        val from = dates.min()
        val newest = dbQuery {
            val maxDate = Transactions.bookingDate.max()
            Transactions.select(maxDate).single()[maxDate]
        }
        val to = newest?.let { minOf(dates.max().plusDays(fx.maxStaleDays), it) }
            ?: dates.max().plusDays(fx.maxStaleDays)
        if (from > to) return
        val targets = dbQuery {
            Transactions.join(Wallets, JoinType.INNER, Transactions.walletId, Wallets.id)
                .select(Transactions.walletId)
                .where {
                    (Transactions.bookingDate greaterEq from) and (Transactions.bookingDate lessEq to) and
                        Transactions.transferGroup.isNull() and (Transactions.currency neq Wallets.currency) and
                        (if (exceptWallet != null) Transactions.walletId neq exceptWallet else Op.TRUE)
                }
                .map { it[Transactions.walletId] }.distinct()
        }
        for (walletId in targets) {
            runCatching { scan(walletId, from, to, propagateRates = false) }
                .onFailure { log.warn("Rate-arrival rescan for wallet {} failed", walletId, it) }
        }
    }

    private suspend fun fetchRates(window: Window, into: MutableList<LocalDate>) {
        if (!fx.enabled) return
        runCatching { fx.ensureCovered(window.from, window.to, notify = false) }
            .onSuccess { into += it }
            .onFailure { log.warn("FX coverage for a transfer scan failed", it) }
    }

    // --- window closure ---

    private suspend fun closure(walletId: Long, from: LocalDate, to: LocalDate): Window =
        dbQuery { closureTx(walletId, from, to) }

    /**
     * Grows the requested window until every group touched by it is fully inside. Widening can pull
     * in a second group whose own partner leg sits outside, so groups are re-derived each round.
     */
    private fun closureTx(walletId: Long, from: LocalDate, to: LocalDate): Window {
        var lo = from.minusDays(TransferDetector.MAX_DAYS)
        var hi = to.plusDays(TransferDetector.MAX_DAYS)
        var groups = emptySet<String>()
        var iterations = 0
        while (true) {
            val found = Transactions.select(Transactions.transferGroup)
                .where {
                    (Transactions.walletId eq walletId) and (Transactions.bookingDate greaterEq lo) and
                        (Transactions.bookingDate lessEq hi) and Transactions.transferGroup.isNotNull()
                }
                .mapNotNull { it[Transactions.transferGroup] }.toSet()
            val newGroups = groups + found
            var newLo = lo
            var newHi = hi
            if (newGroups.isNotEmpty()) {
                val minDate = Transactions.bookingDate.min()
                val maxDate = Transactions.bookingDate.max()
                val row = Transactions.select(minDate, maxDate).where {
                    (Transactions.walletId eq walletId) and (Transactions.transferGroup inList newGroups.toList())
                }.single()
                row[minDate]?.let { if (it.minusDays(TransferDetector.MAX_DAYS) < newLo) newLo = it.minusDays(TransferDetector.MAX_DAYS) }
                row[maxDate]?.let { if (it.plusDays(TransferDetector.MAX_DAYS) > newHi) newHi = it.plusDays(TransferDetector.MAX_DAYS) }
            }
            if (newGroups == groups && newLo == lo && newHi == hi) return Window(newLo, newHi, newGroups)
            groups = newGroups
            lo = newLo
            hi = newHi
            if (++iterations >= MAX_CLOSURE_ITERATIONS) {
                val minDate = Transactions.bookingDate.min()
                val maxDate = Transactions.bookingDate.max()
                val full = Transactions.select(minDate, maxDate).where { Transactions.walletId eq walletId }.single()
                val fullLo = full[minDate] ?: lo
                val fullHi = full[maxDate] ?: hi
                val allGroups = if (fullLo <= fullHi) {
                    Transactions.select(Transactions.transferGroup).where {
                        (Transactions.walletId eq walletId) and (Transactions.bookingDate greaterEq fullLo) and
                            (Transactions.bookingDate lessEq fullHi) and Transactions.transferGroup.isNotNull()
                    }.mapNotNull { it[Transactions.transferGroup] }.toSet()
                } else emptySet()
                log.warn(
                    "Transfer-scan window closure did not converge for wallet {}; widened to {}..{}",
                    walletId, fullLo, fullHi,
                )
                return Window(fullLo, fullHi, groups + allGroups)
            }
        }
    }

    private suspend fun currenciesInWindow(walletId: Long, window: Window): Pair<String, Set<String>> = dbQuery {
        val walletCurrency = Wallets.select(Wallets.currency).where { Wallets.id eq walletId }.single()[Wallets.currency]
        val currencies = Transactions.select(Transactions.currency)
            .where {
                (Transactions.walletId eq walletId) and (Transactions.bookingDate greaterEq window.from) and
                    (Transactions.bookingDate lessEq window.to)
            }
            .map { it[Transactions.currency] }.toSet()
        walletCurrency to currencies
    }

    private fun bookingRange(walletId: Long): Pair<LocalDate, LocalDate>? {
        val minDate = Transactions.bookingDate.min()
        val maxDate = Transactions.bookingDate.max()
        val row = Transactions.select(minDate, maxDate).where { Transactions.walletId eq walletId }.single()
        val lo = row[minDate] ?: return null
        val hi = row[maxDate] ?: return null
        return lo to hi
    }

    // --- the atomic rewrite (runs inside one dbQuery transaction) ---

    private fun rewrite(walletId: Long, window: Window, table: FxTable, walletCurrency: String): Int {
        val inWindow = (Transactions.bookingDate greaterEq window.from) and (Transactions.bookingDate lessEq window.to)
        val clear = if (window.groups.isEmpty()) {
            (Transactions.walletId eq walletId) and inWindow and Transactions.transferGroup.isNotNull()
        } else {
            (Transactions.walletId eq walletId) and
                ((Transactions.transferGroup inList window.groups.toList()) or (inWindow and Transactions.transferGroup.isNotNull()))
        }
        Transactions.update({ clear }) { it[Transactions.transferGroup] = null }

        val kinds = Categories.selectAll().where { Categories.walletId eq walletId }
            .associate { it[Categories.id] to CategoryKind.valueOf(it[Categories.kind]) }
        val rows = Transactions.join(BankAccounts, JoinType.LEFT, Transactions.bankAccountId, BankAccounts.id)
            .selectAll().where {
                (Transactions.walletId eq walletId) and (Transactions.bookingDate greaterEq window.from) and
                    (Transactions.bookingDate lessEq window.to)
            }
            .toList()

        val legs = rows.map { row ->
            val bankAccountId = row[Transactions.bankAccountId]
            val sourceAccount = row[Transactions.sourceAccount]
            val accountKey = when {
                bankAccountId != null -> "bank:$bankAccountId"
                !sourceAccount.isNullOrBlank() -> "src:$sourceAccount"
                else -> null
            }
            val categoryKind = row[Transactions.categoryId]?.let { kinds[it] }
            TransferLeg(
                id = row[Transactions.id],
                date = row[Transactions.bookingDate],
                amount = row[Transactions.amount],
                currency = row[Transactions.currency],
                accountKey = accountKey,
                ownIbanDigits = row.getOrNull(BankAccounts.ibanMasked)?.filter(Char::isDigit),
                counterpartyIbanDigits = row[Transactions.counterpartyIban]?.filter(Char::isDigit),
                text = ((row[Transactions.paymentType] ?: "") + " " + row[Transactions.descriptionRaw]).lowercase(),
                manualNonTransfer = row[Transactions.transferOverride] && categoryKind != CategoryKind.TRANSFER,
            )
        }
        val converted = { leg: TransferLeg ->
            table.convert(leg.amount, leg.currency, walletCurrency, leg.date)?.amount
        }
        val pairs = TransferDetector.pair(legs, converted)
        for (pair in pairs) {
            val group = UUID.randomUUID().toString()
            Transactions.update({ Transactions.id inList listOf(pair.outId, pair.inId) }) {
                it[Transactions.transferGroup] = group
            }
        }
        return pairs.size
    }

    private companion object {
        const val MAX_CLOSURE_ITERATIONS = 8
        const val MAX_RATE_RETRIES = 2
    }
}
