package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.RuleField
import com.bnuprst.expenses.shared.RuleOp
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RuleEngineTest {
    private val txn = TxnInput("Monthly rent March", "Landlord Ltd", BigDecimal("-950.00"), "EUR", "landlord")

    @Test
    fun `lowest priority matching rule wins`() {
        val rules = listOf(
            RuleDto(1, 200, RuleField.DESCRIPTION, RuleOp.CONTAINS, "rent", 10),
            RuleDto(2, 100, RuleField.AMOUNT, RuleOp.GT, "900", 20),
        )
        assertEquals(20, RuleEngine.match(rules, txn)?.categoryId)
    }

    @Test
    fun `regex and counterparty rules`() {
        assertEquals(5, RuleEngine.match(listOf(RuleDto(1, 1, RuleField.COUNTERPARTY, RuleOp.REGEX, "^landlord", 5)), txn)?.categoryId)
        assertNull(RuleEngine.match(listOf(RuleDto(1, 1, RuleField.MERCHANT, RuleOp.EQUALS, "landlord ltd", 5)), txn))
    }

    @Test
    fun `validation`() {
        assertNotNull(RuleEngine.validate(RuleField.AMOUNT, RuleOp.CONTAINS, "10"))
        assertNotNull(RuleEngine.validate(RuleField.DESCRIPTION, RuleOp.REGEX, "(unclosed"))
        assertNull(RuleEngine.validate(RuleField.DESCRIPTION, RuleOp.CONTAINS, "rent"))
    }
}
