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
import com.bnuprst.expenses.shared.ImportRequest
import com.bnuprst.expenses.shared.ImportResult
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.shared.WalletRole
import com.bnuprst.expenses.wallets.WalletService
import org.jetbrains.exposed.sql.insert
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
            val votes = mutableMapOf<String, MutableMap<Long, Int>>()
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
                    val remembered = memory[key]
                    val rule = if (remembered == null) RuleEngine.match(rules, TxnInput(description, counterparty, amount, currency, key)) else null
                    catId = remembered ?: rule?.categoryId
                    source = when {
                        remembered != null -> CategorySource.MEMORY
                        rule != null -> CategorySource.RULE
                        else -> null
                    }
                } else {
                    votes.getOrPut(key) { mutableMapOf() }.merge(catId!!, 1, Int::plus)
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
                }.insertedCount
            }

            // Majority vote per merchant; existing memory (the user's decisions here) always wins.
            var learned = 0
            for ((key, counts) in votes) {
                if (key == "unknown" || key in memory) continue
                DbCategorizationStore.learn(walletId, key, counts.maxBy { it.value }.key)
                learned++
            }
            ImportResult(req.transactions.size, inserted, created, learned)
        }
    }

    companion object {
        const val MAX_BATCH = 2000
    }
}
