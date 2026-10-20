package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.categorization.llm.LlmCategorizer
import com.bnuprst.expenses.categorization.llm.LlmProvider
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.RuleField
import com.bnuprst.expenses.shared.RuleOp
import com.bnuprst.expenses.shared.TxnStatus
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeStore(
    val memory: MutableMap<String, Long> = mutableMapOf(),
    val rules: List<RuleDto> = emptyList(),
) : CategorizationStore {
    override suspend fun memoryLookup(walletId: Long, merchantKey: String) = memory[merchantKey]
    override suspend fun rules(walletId: Long) = rules
    override suspend fun categories(walletId: Long) = listOf(
        CategoryDto(1, "Groceries", CategoryKind.EXPENSE),
        CategoryDto(2, "Subscriptions", CategoryKind.EXPENSE),
    )
}

class FakeLlm(var reply: String) : LlmProvider {
    var calls = 0
    var fail = false
    override suspend fun completeJson(system: String, user: String): String {
        calls++
        if (fail) error("boom")
        return reply
    }
}

class CategorizerTest {
    private fun txn(key: String) = TxnInput("desc $key", null, BigDecimal("-10"), "EUR", key)

    @Test
    fun `memory beats rules and llm`() = runTest {
        val llm = FakeLlm("""{"category_id": 2, "confidence": 0.99}""")
        val store = FakeStore(memory = mutableMapOf("lidl" to 1L), rules = listOf(RuleDto(1, 1, RuleField.MERCHANT, RuleOp.CONTAINS, "lidl", 2)))
        val r = Categorizer(store, LlmCategorizer(llm), 0.7).session(1).categorize(txn("lidl"))
        assertEquals(CategorizationResult(1, CategorySource.MEMORY, 1.0, TxnStatus.CATEGORIZED), r)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `rules beat llm`() = runTest {
        val llm = FakeLlm("""{"category_id": 1, "confidence": 0.99}""")
        val store = FakeStore(rules = listOf(RuleDto(1, 1, RuleField.MERCHANT, RuleOp.CONTAINS, "spot", 2)))
        val r = Categorizer(store, LlmCategorizer(llm), 0.7).session(1).categorize(txn("spotify"))
        assertEquals(CategorySource.RULE, r.source)
        assertEquals(2, r.categoryId)
        assertEquals(0, llm.calls)
    }

    @Test
    fun `llm confidence threshold decides review`() = runTest {
        val llm = FakeLlm("""{"category_id": 1, "confidence": 0.9}""")
        val c = Categorizer(FakeStore(), LlmCategorizer(llm), 0.7)
        assertEquals(TxnStatus.CATEGORIZED, c.session(1).categorize(txn("a")).status)
        llm.reply = """{"category_id": 1, "confidence": 0.4}"""
        val low = c.session(1).categorize(txn("b"))
        assertEquals(TxnStatus.REVIEW, low.status)
        assertEquals(1, low.categoryId) // suggestion kept for the review UI
    }

    @Test
    fun `invalid llm category goes to review without suggestion`() = runTest {
        val r = Categorizer(FakeStore(), LlmCategorizer(FakeLlm("""{"category_id": 99, "confidence": 0.9}""")), 0.7)
            .session(1).categorize(txn("x"))
        assertEquals(Categorizer.REVIEW_NO_SUGGESTION, r)
    }

    @Test
    fun `session caches llm answers per merchant`() = runTest {
        val llm = FakeLlm("""{"category_id": 1, "confidence": 0.9}""")
        val session = Categorizer(FakeStore(), LlmCategorizer(llm), 0.7).session(1)
        repeat(3) { session.categorize(txn("same")) }
        session.categorize(txn("other"))
        assertEquals(2, llm.calls)
    }

    @Test
    fun `llm failure leaves transaction uncategorized and is not cached`() = runTest {
        val llm = FakeLlm("""{"category_id": 1, "confidence": 0.9}""").apply { fail = true }
        val session = Categorizer(FakeStore(), LlmCategorizer(llm), 0.7).session(1)
        assertEquals(Categorizer.UNCATEGORIZED, session.categorize(txn("m")))
        llm.fail = false
        assertEquals(TxnStatus.CATEGORIZED, session.categorize(txn("m")).status)
    }

    @Test
    fun `no llm configured`() = runTest {
        assertEquals(Categorizer.UNCATEGORIZED, Categorizer(FakeStore(), null, 0.7).session(1).categorize(txn("m")))
    }
}
