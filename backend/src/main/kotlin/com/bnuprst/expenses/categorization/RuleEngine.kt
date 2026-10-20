package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.RuleField
import com.bnuprst.expenses.shared.RuleOp

object RuleEngine {
    /** First matching rule by ascending priority wins. */
    fun match(rules: List<RuleDto>, txn: TxnInput): RuleDto? =
        rules.sortedWith(compareBy({ it.priority }, { it.id })).firstOrNull { matches(it, txn) }

    fun matches(rule: RuleDto, txn: TxnInput): Boolean {
        if (rule.field == RuleField.AMOUNT) {
            val threshold = rule.value.toBigDecimalOrNull() ?: return false
            val amount = txn.amount.abs()
            return when (rule.op) {
                RuleOp.GT -> amount > threshold
                RuleOp.LT -> amount < threshold
                RuleOp.EQUALS -> amount.compareTo(threshold) == 0
                else -> false
            }
        }
        val text = when (rule.field) {
            RuleField.DESCRIPTION -> txn.description
            RuleField.MERCHANT -> txn.merchantKey
            RuleField.COUNTERPARTY -> txn.counterparty ?: ""
            RuleField.AMOUNT -> error("unreachable")
        }
        return when (rule.op) {
            RuleOp.CONTAINS -> text.contains(rule.value, ignoreCase = true)
            RuleOp.EQUALS -> text.equals(rule.value, ignoreCase = true)
            RuleOp.REGEX -> runCatching { Regex(rule.value, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
            RuleOp.GT, RuleOp.LT -> false
        }
    }

    /** Validation used when rules are created. Returns an error message or null. */
    fun validate(field: RuleField, op: RuleOp, value: String): String? = when {
        value.isBlank() -> "Value is required"
        field == RuleField.AMOUNT && op !in setOf(RuleOp.GT, RuleOp.LT, RuleOp.EQUALS) -> "Amount rules support GT, LT, EQUALS"
        field == RuleField.AMOUNT && value.toBigDecimalOrNull() == null -> "Amount value must be a number"
        field != RuleField.AMOUNT && op in setOf(RuleOp.GT, RuleOp.LT) -> "GT/LT only apply to AMOUNT"
        op == RuleOp.REGEX && runCatching { Regex(value) }.isFailure -> "Invalid regex"
        else -> null
    }
}
