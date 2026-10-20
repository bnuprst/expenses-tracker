package com.bnuprst.expenses.transactions

import com.bnuprst.expenses.BadRequest
import com.bnuprst.expenses.Forbidden
import com.bnuprst.expenses.categorization.DbCategorizationStore
import com.bnuprst.expenses.categorization.Normalizer
import com.bnuprst.expenses.categorization.RuleEngine
import com.bnuprst.expenses.categorization.TxnInput
import com.bnuprst.expenses.db.Categories
import com.bnuprst.expenses.db.MerchantMemory
import com.bnuprst.expenses.db.Transactions
import com.bnuprst.expenses.db.Wallets
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.FinalizeImportResult
import com.bnuprst.expenses.shared.ImportRequest
import com.bnuprst.expenses.shared.ImportResult
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.shared.WalletRole
import com.bnuprst.expenses.wallets.WalletService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDate

/**
 * Bulk import of history from another tool. Categories chosen in the source count as the user's own
 * decisions: they are stored as MANUAL and seed merchant memory. Uncategorized rows only go through
 * memory and rules, never the LLM, so a multi-year import costs no model calls.
 */
class ImportService(private val wallets: WalletService) {

    suspend fun import(userId: Long, walletId: Long, req: ImportRequest): ImportResult {
        if (wallets.requireRole(userId, walletId) != WalletRole.OWNER) throw Forbidden("Only wallet owners can import")
        if (req.transactions.size > MAX_BATCH) throw BadRequest("At most $MAX_BATCH transactions per request")
        val store = DbCategorizationStore()
        val rules = store.rules(walletId)

        return dbQuery {
            val walletCurrency = Wallets.selectAll().where { Wallets.id eq walletId }.single()[Wallets.currency]
            val categoryIds = Categories.selectAll().where { Categories.walletId eq walletId }
                .associate { it[Categories.name].lowercase() to it[Categories.id] }.toMutableMap()

            var created = 0
            fun categoryId(name: String, kind: CategoryKind): Long = categoryIds.getOrPut(name.trim().lowercase()) {
                created++
                Categories.insert {
                    it[Categories.walletId] = walletId
                    it[Categories.name] = name.trim()
                    it[Categories.kind] = kind.name
                }[Categories.id]
            }
            val kinds = req.categories.associate { it.name.trim().lowercase() to it.kind }
            req.categories.forEach { categoryId(it.name, it.kind) }

            val memory = MerchantMemory.selectAll().where { MerchantMemory.walletId eq walletId }
                .associate { it[MerchantMemory.merchantKey] to it[MerchantMemory.categoryId] }
            var inserted = 0

            for (t in req.transactions) {
                val date = runCatching { LocalDate.parse(t.date) }.getOrElse { throw BadRequest("Invalid date '${t.date}' in ${t.externalId}") }
                val amount = t.amount.toBigDecimalOrNull() ?: throw BadRequest("Invalid amount in ${t.externalId}")
                val description = listOfNotNull(t.notes?.takeIf { it.isNotBlank() }, t.payee?.takeIf { it.isNotBlank() })
                    .firstOrNull() ?: "(no description)"
                val counterparty = t.payee?.trim()?.takeIf { it.isNotEmpty() }
                val key = Normalizer.merchantKey(counterparty, description)
                val currency = t.currency?.uppercase() ?: walletCurrency

                var catId = t.category?.takeIf { it.isNotBlank() }?.let { categoryId(it, kinds[it.trim().lowercase()] ?: CategoryKind.EXPENSE) }
                var source: CategorySource? = catId?.let { CategorySource.MANUAL }
                if (catId == null) {
                    val remembered = memory[key]?.takeIf { Normalizer.isLearnable(key) }
                    val rule = if (remembered == null) RuleEngine.match(rules, TxnInput(description, counterparty, amount, currency, key)) else null
                    catId = remembered ?: rule?.categoryId
                    source = when {
                        remembered != null -> CategorySource.MEMORY
                        rule != null -> CategorySource.RULE
                        else -> null
                    }
                }

                inserted += Transactions.insertIgnore {
                    it[Transactions.walletId] = walletId
                    it[Transactions.bankAccountId] = null
                    it[Transactions.externalId] = "${req.source}:${t.externalId}"
                    it[Transactions.bookingDate] = date
                    it[Transactions.amount] = amount
                    it[Transactions.currency] = currency
                    it[Transactions.descriptionRaw] = description
                    it[Transactions.counterparty] = counterparty
                    it[Transactions.merchantKey] = key
                    it[Transactions.categoryId] = catId
                    it[Transactions.categorySource] = source?.name
                    it[Transactions.confidence] = catId?.let { 1.0 }
                    it[Transactions.status] = (if (catId != null) TxnStatus.CATEGORIZED else TxnStatus.UNCATEGORIZED).name
                    it[Transactions.sourceAccount] = t.account?.trim()?.takeIf { it.isNotEmpty() }
                }.insertedCount
            }

            // Merchant memory is learned in finalize(), once every batch is in.
            ImportResult(req.transactions.size, inserted, created, 0)
        }
    }

    /**
     * Run once after the last batch: learns merchant memory from the majority category per merchant over
     * the whole import (a per-batch vote would let the first batch to see a merchant decide), then applies
     * it to imported rows that arrived uncategorized. Existing memory - the user's own decisions here -
     * always wins, and the catch-all key is never learned.
     */
    suspend fun finalize(userId: Long, walletId: Long, source: String): FinalizeImportResult {
        if (wallets.requireRole(userId, walletId) != WalletRole.OWNER) throw Forbidden("Only wallet owners can import")
        if (source.isBlank() || source.contains('%') || source.contains('_')) throw BadRequest("Invalid source")
        return dbQuery {
            val count = Transactions.id.count()
            val votes = Transactions.select(Transactions.merchantKey, Transactions.categoryId, count)
                .where {
                    (Transactions.walletId eq walletId) and Transactions.bankAccountId.isNull() and
                        (Transactions.externalId like "$source:%") and
                        (Transactions.categorySource eq CategorySource.MANUAL.name) and Transactions.categoryId.isNotNull()
                }
                .groupBy(Transactions.merchantKey, Transactions.categoryId)
                .map { Triple(it[Transactions.merchantKey], it[Transactions.categoryId]!!, it[count]) }
            val existing = MerchantMemory.select(MerchantMemory.merchantKey)
                .where { MerchantMemory.walletId eq walletId }.map { it[MerchantMemory.merchantKey] }.toSet()

            var learned = 0
            var recategorized = 0
            // Ties go to the lower category id, so re-running finalize is deterministic.
            for ((key, rows) in votes.groupBy { it.first }) {
                if (!Normalizer.isLearnable(key) || key in existing) continue
                val winner = rows.sortedWith(compareByDescending<Triple<String, Long, Long>> { it.third }.thenBy { it.second }).first().second
                DbCategorizationStore.learn(walletId, key, winner)
                learned++
                recategorized += Transactions.update({
                    (Transactions.walletId eq walletId) and (Transactions.merchantKey eq key) and
                        (Transactions.status eq TxnStatus.UNCATEGORIZED.name)
                }) {
                    it[Transactions.categoryId] = winner
                    it[categorySource] = CategorySource.MEMORY.name
                    it[confidence] = 1.0
                    it[status] = TxnStatus.CATEGORIZED.name
                }
            }
            FinalizeImportResult(learned, recategorized)
        }
    }

    companion object {
        const val MAX_BATCH = 2000
    }
}
