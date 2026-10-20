package com.bnuprst.expenses.transactions

import com.bnuprst.expenses.BadRequest
import com.bnuprst.expenses.NotFound
import com.bnuprst.expenses.categorization.Categorizer
import com.bnuprst.expenses.categorization.DbCategorizationStore
import com.bnuprst.expenses.categorization.Normalizer
import com.bnuprst.expenses.categorization.RuleEngine
import com.bnuprst.expenses.categorization.TxnInput
import com.bnuprst.expenses.db.Categories
import com.bnuprst.expenses.db.Rules
import com.bnuprst.expenses.db.Transactions
import com.bnuprst.expenses.db.Wallets
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.CreateRuleRequest
import com.bnuprst.expenses.shared.CreateTransactionRequest
import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.wallets.WalletService
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A first bank sync starts on the last day of imported history (inclusive, so nothing booked later that day
 * is lost). On and before that day, a bank row matching an imported row by date and amount is the same
 * transaction; each imported row absorbs at most one bank row not already stored. Built fresh for every
 * sync whose window reaches that day, so the result doesn't depend on page order or on which sync saw it.
 */
class ImportBoundary(val lastDate: LocalDate, imported: List<Pair<LocalDate, BigDecimal>>) {
    private val remaining = imported.groupingBy { it.first to it.second.stripTrailingZeros() }.eachCount().toMutableMap()

    fun covers(t: NewTransaction): Boolean {
        if (t.bookingDate > lastDate) return false
        val key = t.bookingDate to t.amount.stripTrailingZeros()
        val n = remaining[key] ?: return false
        if (n == 1) remaining.remove(key) else remaining[key] = n - 1
        return true
    }
}

/** A transaction to be stored, before categorization. */
data class NewTransaction(
    val externalId: String?,
    val bookingDate: LocalDate,
    val amount: BigDecimal,
    val currency: String,
    val description: String,
    val counterparty: String?,
)

class TransactionService(
    private val wallets: WalletService,
    private val categorizer: Categorizer,
) {
    suspend fun list(
        userId: Long, walletId: Long,
        from: LocalDate?, to: LocalDate?, status: TxnStatus?, categoryId: Long?,
        limit: Int = 200, offset: Long = 0,
    ): List<TransactionDto> {
        wallets.requireRole(userId, walletId)
        return dbQuery {
            Transactions.selectAll().where {
                var cond = Transactions.walletId eq walletId
                from?.let { cond = cond and (Transactions.bookingDate greaterEq it) }
                to?.let { cond = cond and (Transactions.bookingDate lessEq it) }
                status?.let { cond = cond and (Transactions.status eq it.name) }
                categoryId?.let { cond = cond and (Transactions.categoryId eq it) }
                cond
            }
                .orderBy(Transactions.bookingDate to SortOrder.DESC, Transactions.id to SortOrder.DESC)
                .limit(limit.coerceIn(1, 1000)).offset(offset)
                .map { it.toDto() }
        }
    }

    suspend fun reviewQueue(userId: Long, walletId: Long): List<TransactionDto> {
        wallets.requireRole(userId, walletId)
        return dbQuery {
            Transactions.selectAll()
                .where {
                    (Transactions.walletId eq walletId) and
                        (Transactions.status inList listOf(TxnStatus.REVIEW.name, TxnStatus.UNCATEGORIZED.name))
                }
                .orderBy(Transactions.bookingDate to SortOrder.DESC, Transactions.id to SortOrder.DESC)
                .limit(500)
                .map { it.toDto() }
        }
    }

    /**
     * Gives bank rows that ended UNCATEGORIZED (LLM down, or a rule/memory added since) another pass.
     * Bounded per run, and limited to bank rows, so a large import never fans out into LLM calls.
     */
    suspend fun retryUncategorized(walletId: Long, limit: Int = RETRY_LIMIT): Int {
        val rows = dbQuery {
            Transactions.selectAll()
                .where {
                    (Transactions.walletId eq walletId) and Transactions.bankAccountId.isNotNull() and
                        (Transactions.status eq TxnStatus.UNCATEGORIZED.name)
                }
                .orderBy(Transactions.bookingDate to SortOrder.DESC)
                .limit(limit)
                .toList()
        }
        val session = categorizer.session(walletId)
        var updated = 0
        for (row in rows) {
            val r = session.categorize(
                TxnInput(row[Transactions.descriptionRaw], row[Transactions.counterparty], row[Transactions.amount], row[Transactions.currency], row[Transactions.merchantKey])
            )
            if (r.status == TxnStatus.UNCATEGORIZED) continue
            updated += dbQuery {
                // Only if still untouched: the user may have categorized it meanwhile.
                Transactions.update({ (Transactions.id eq row[Transactions.id]) and (Transactions.status eq TxnStatus.UNCATEGORIZED.name) }) {
                    it[categoryId] = r.categoryId
                    it[categorySource] = r.source?.name
                    it[confidence] = r.confidence
                    it[status] = r.status.name
                }
            }
        }
        return updated
    }

    suspend fun create(userId: Long, walletId: Long, req: CreateTransactionRequest): TransactionDto {
        wallets.requireRole(userId, walletId)
        val date = parseDate(req.bookingDate)
        val amount = req.amount.toBigDecimalOrNull() ?: throw BadRequest("Invalid amount")
        if (req.description.isBlank()) throw BadRequest("Description is required")
        req.categoryId?.let { requireCategory(walletId, it) }
        val currency = req.currency?.uppercase() ?: dbQuery {
            Wallets.selectAll().where { Wallets.id eq walletId }.single()[Wallets.currency]
        }
        val txn = NewTransaction(null, date, amount, currency, req.description.trim(), req.counterparty?.trim())
        val merchantKey = Normalizer.merchantKey(txn.counterparty, txn.description)
        if (req.categoryId != null) {
            return dbQuery {
                if (Normalizer.isLearnable(merchantKey)) DbCategorizationStore.learn(walletId, merchantKey, req.categoryId!!)
                insertRow(walletId, null, txn, merchantKey, req.categoryId, CategorySource.MANUAL, 1.0, TxnStatus.CATEGORIZED)
            }
        }
        val result = categorizer.session(walletId).categorize(txn.toInput(merchantKey))
        return dbQuery { insertRow(walletId, null, txn, merchantKey, result.categoryId, result.source, result.confidence, result.status) }
    }

    /**
     * Stores bank transactions, skipping ones already present for the account (dedup by external id),
     * and categorizes only the new ones. Returns the number inserted.
     */
    suspend fun importBankTransactions(
        walletId: Long,
        bankAccountId: Long,
        items: List<NewTransaction>,
        importBoundary: ImportBoundary? = null,
    ): Int {
        if (items.isEmpty()) return 0
        val unique = items.distinctBy { it.externalId }
        val existing = dbQuery {
            Transactions.select(Transactions.externalId)
                .where { (Transactions.bankAccountId eq bankAccountId) and (Transactions.externalId inList unique.mapNotNull { it.externalId }) }
                .mapNotNull { it[Transactions.externalId] }.toSet()
        }
        // Existing ids first: a bank row stored on an earlier sync must not use up an imported row's match.
        val fresh = unique.filter { it.externalId !in existing }.filterNot { importBoundary?.covers(it) == true }
        val session = categorizer.session(walletId)
        var inserted = 0
        for (txn in fresh) {
            val key = Normalizer.merchantKey(txn.counterparty, txn.description)
            val r = session.categorize(txn.toInput(key))
            inserted += dbQuery {
                Transactions.insertIgnore {
                    fill(it, walletId, bankAccountId, txn, key, r.categoryId, r.source, r.confidence, r.status)
                }.insertedCount
            }
        }
        return inserted
    }

    /**
     * A user's categorization decision: stored as MANUAL, learned into merchant memory, and applied to
     * other not-yet-confirmed transactions of the same merchant in the wallet.
     */
    suspend fun setCategory(userId: Long, txnId: Long, categoryId: Long): TransactionDto {
        val row = dbQuery { Transactions.selectAll().where { Transactions.id eq txnId }.singleOrNull() }
            ?: throw NotFound("Transaction not found")
        val walletId = row[Transactions.walletId]
        wallets.requireRole(userId, walletId)
        requireCategory(walletId, categoryId)
        val merchantKey = row[Transactions.merchantKey]
        return dbQuery {
            Transactions.update({ Transactions.id eq txnId }) {
                it[Transactions.categoryId] = categoryId
                it[categorySource] = CategorySource.MANUAL.name
                it[confidence] = 1.0
                it[status] = TxnStatus.CATEGORIZED.name
            }
            if (Normalizer.isLearnable(merchantKey)) {
                DbCategorizationStore.learn(walletId, merchantKey, categoryId)
                Transactions.update({
                    (Transactions.walletId eq walletId) and (Transactions.merchantKey eq merchantKey) and
                        (Transactions.status inList listOf(TxnStatus.REVIEW.name, TxnStatus.UNCATEGORIZED.name))
                }) {
                    it[Transactions.categoryId] = categoryId
                    it[categorySource] = CategorySource.MEMORY.name
                    it[confidence] = 1.0
                    it[status] = TxnStatus.CATEGORIZED.name
                }
            }
            Transactions.selectAll().where { Transactions.id eq txnId }.single().toDto()
        }
    }

    suspend fun rules(userId: Long, walletId: Long): List<RuleDto> {
        wallets.requireRole(userId, walletId)
        return DbCategorizationStore().rules(walletId).sortedBy { it.priority }
    }

    suspend fun createRule(userId: Long, walletId: Long, req: CreateRuleRequest): RuleDto {
        wallets.requireRole(userId, walletId)
        RuleEngine.validate(req.field, req.op, req.value)?.let { throw BadRequest(it) }
        requireCategory(walletId, req.categoryId)
        return dbQuery {
            val id = Rules.insert {
                it[Rules.walletId] = walletId
                it[priority] = req.priority
                it[field] = req.field.name
                it[op] = req.op.name
                it[value] = req.value
                it[Rules.categoryId] = req.categoryId
            }[Rules.id]
            RuleDto(id, req.priority, req.field, req.op, req.value, req.categoryId)
        }
    }

    private suspend fun requireCategory(walletId: Long, categoryId: Long) {
        val ok = dbQuery {
            Categories.selectAll().where { (Categories.id eq categoryId) and (Categories.walletId eq walletId) }.any()
        }
        if (!ok) throw BadRequest("Unknown category for this wallet")
    }

    private fun insertRow(
        walletId: Long, bankAccountId: Long?, txn: NewTransaction, merchantKey: String,
        categoryId: Long?, source: CategorySource?, confidence: Double?, status: TxnStatus,
    ): TransactionDto {
        val id = Transactions.insert { fill(it, walletId, bankAccountId, txn, merchantKey, categoryId, source, confidence, status) }[Transactions.id]
        return Transactions.selectAll().where { Transactions.id eq id }.single().toDto()
    }

    private fun fill(
        it: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>,
        walletId: Long, bankAccountId: Long?, txn: NewTransaction, merchantKey: String,
        categoryId: Long?, source: CategorySource?, confidence: Double?, status: TxnStatus,
    ) {
        it[Transactions.walletId] = walletId
        it[Transactions.bankAccountId] = bankAccountId
        it[Transactions.externalId] = txn.externalId
        it[Transactions.bookingDate] = txn.bookingDate
        it[Transactions.amount] = txn.amount
        it[Transactions.currency] = txn.currency
        it[Transactions.descriptionRaw] = txn.description
        it[Transactions.counterparty] = txn.counterparty
        it[Transactions.merchantKey] = merchantKey
        it[Transactions.categoryId] = categoryId
        it[Transactions.categorySource] = source?.name
        it[Transactions.confidence] = confidence
        it[Transactions.status] = status.name
    }

    private fun NewTransaction.toInput(merchantKey: String) = TxnInput(description, counterparty, amount, currency, merchantKey)

    private fun parseDate(s: String): LocalDate = try {
        LocalDate.parse(s)
    } catch (e: DateTimeParseException) {
        throw BadRequest("Invalid date, expected yyyy-MM-dd")
    }
}

private const val RETRY_LIMIT = 50

fun ResultRow.toDto() = TransactionDto(
    id = this[Transactions.id],
    walletId = this[Transactions.walletId],
    bankAccountId = this[Transactions.bankAccountId],
    bookingDate = this[Transactions.bookingDate].toString(),
    amount = this[Transactions.amount].toPlainString(),
    currency = this[Transactions.currency],
    description = this[Transactions.descriptionRaw],
    counterparty = this[Transactions.counterparty],
    merchantKey = this[Transactions.merchantKey],
    categoryId = this[Transactions.categoryId],
    categorySource = this[Transactions.categorySource]?.let { CategorySource.valueOf(it) },
    confidence = this[Transactions.confidence],
    status = TxnStatus.valueOf(this[Transactions.status]),
)
