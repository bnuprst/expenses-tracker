package com.bnuprst.expenses.categorization.llm

import com.bnuprst.expenses.categorization.TxnInput
import com.bnuprst.expenses.shared.CategoryDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class LlmSuggestion(val categoryId: Long, val confidence: Double)

class LlmCategorizer(private val provider: LlmProvider) {

    /** Returns null when the model can't pick a valid category. */
    suspend fun categorize(txn: TxnInput, categories: List<CategoryDto>): LlmSuggestion? {
        if (categories.isEmpty()) return null
        val raw = provider.completeJson(SYSTEM_PROMPT, userPrompt(txn, categories))
        return parse(raw, categories.map { it.id }.toSet())
    }

    internal fun userPrompt(txn: TxnInput, categories: List<CategoryDto>): String = buildString {
        appendLine("Categories (id: name [kind]):")
        categories.forEach { appendLine("${it.id}: ${it.name} [${it.kind}]") }
        appendLine()
        appendLine("Transaction:")
        appendLine("description: ${txn.description.take(500)}")
        txn.counterparty?.let { appendLine("counterparty: ${it.take(200)}") }
        appendLine("normalized merchant: ${txn.merchantKey}")
        appendLine("amount: ${txn.amount.toPlainString()} ${txn.currency} (${if (txn.amount.signum() < 0) "outgoing" else "incoming"})")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val SYSTEM_PROMPT = """You categorize personal bank transactions for a family budget.
Pick exactly one category id from the provided list. Outgoing amounts are usually EXPENSE categories,
incoming amounts INCOME, and movements between own accounts TRANSFER.
Reply with JSON only: {"category_id": <id from the list>, "confidence": <0.0-1.0>}.
Use a low confidence when the merchant is ambiguous or unknown."""

        internal fun parse(raw: String, validIds: Set<Long>): LlmSuggestion? {
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = runCatching { json.parseToJsonElement(cleaned).jsonObject }.getOrNull() ?: return null
            val id = obj["category_id"]?.jsonPrimitive?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: return null
            if (id !in validIds) return null
            val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.5
            return LlmSuggestion(id, confidence)
        }
    }
}
