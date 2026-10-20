package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.categorization.llm.LlmCategorizer
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.TxnStatus
import org.slf4j.LoggerFactory
import java.math.BigDecimal

data class TxnInput(
    val description: String,
    val counterparty: String?,
    val amount: BigDecimal,
    val currency: String,
    val merchantKey: String,
)

data class CategorizationResult(
    val categoryId: Long?,
    val source: CategorySource?,
    val confidence: Double?,
    val status: TxnStatus,
)

interface CategorizationStore {
    suspend fun memoryLookup(walletId: Long, merchantKey: String): Long?
    suspend fun rules(walletId: Long): List<RuleDto>
    suspend fun categories(walletId: Long): List<CategoryDto>
}

/**
 * Pipeline: merchant memory → rules → LLM fallback. LLM answers below the confidence threshold
 * keep their suggestion but land in the review queue. Corrections feed merchant memory, so the
 * share of LLM calls drops over time.
 */
class Categorizer(
    private val store: CategorizationStore,
    private val llm: LlmCategorizer?,
    private val confidenceThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(Categorizer::class.java)

    /** Reuse one session per batch (e.g. a sync run) so repeated unknown merchants hit the LLM once. */
    inner class Session(private val walletId: Long) {
        private var rules: List<RuleDto>? = null
        private var categories: List<CategoryDto>? = null
        private val llmCache = mutableMapOf<String, CategorizationResult>()

        suspend fun categorize(txn: TxnInput): CategorizationResult {
            val learnable = Normalizer.isLearnable(txn.merchantKey)
            if (learnable) {
                store.memoryLookup(walletId, txn.merchantKey)?.let {
                    return CategorizationResult(it, CategorySource.MEMORY, 1.0, TxnStatus.CATEGORIZED)
                }
            }
            val rules = rules ?: store.rules(walletId).also { rules = it }
            RuleEngine.match(rules, txn)?.let {
                return CategorizationResult(it.categoryId, CategorySource.RULE, 1.0, TxnStatus.CATEGORIZED)
            }
            if (llm == null) return UNCATEGORIZED
            if (learnable) llmCache[txn.merchantKey]?.let { return it }

            val categories = categories ?: store.categories(walletId).also { categories = it }
            val result = try {
                llm.categorize(txn, categories)?.let { s ->
                    val status = if (s.confidence >= confidenceThreshold) TxnStatus.CATEGORIZED else TxnStatus.REVIEW
                    CategorizationResult(s.categoryId, CategorySource.LLM, s.confidence, status)
                } ?: REVIEW_NO_SUGGESTION
            } catch (e: Exception) {
                log.warn("LLM categorization failed for '{}': {}", txn.merchantKey, e.message)
                return UNCATEGORIZED // not cached: a transient failure shouldn't stick for the batch
            }
            if (learnable) llmCache[txn.merchantKey] = result
            return result
        }
    }

    fun session(walletId: Long) = Session(walletId)

    companion object {
        val UNCATEGORIZED = CategorizationResult(null, null, null, TxnStatus.UNCATEGORIZED)
        val REVIEW_NO_SUGGESTION = CategorizationResult(null, CategorySource.LLM, 0.0, TxnStatus.REVIEW)
    }
}
