package com.bnuprst.expenses.android.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast first filter over translated label length. Character counts do **not** prove text fits —
 * glyph widths, font scale and container width decide that; `LabelFitTest` is the real check and
 * this test only rejects an obviously over-long new translation in milliseconds, before anyone
 * waits on measured layout.
 *
 * Raise a single group's budget (and re-run `LabelFitTest`) rather than loosening all of them.
 */
class LabelLengthBudgetTest {
    private data class Budget(val group: String, val keys: List<String>, val max: Int)

    private val budgets = listOf(
        Budget(
            "tab_*",
            listOf("tab_transactions", "tab_review", "tab_stats", "tab_banks"),
            12,
        ),
        Budget(
            "status_*",
            listOf("status_active", "status_pending", "status_expired", "status_error"),
            12,
        ),
        Budget(
            "short actions",
            listOf("action_add", "action_remove", "action_clear", "action_create", "action_cancel", "action_apply"),
            12,
        ),
        Budget(
            "long actions",
            listOf("action_find_banks", "action_sync_now", "action_renew_consent", "action_sign_in"),
            22,
        ),
        Budget(
            "stats labels",
            listOf(
                "granularity_month", "granularity_year",
                "stats_spent", "stats_earned", "stats_net", "stats_spending", "stats_income",
            ),
            14,
        ),
        Budget(
            "detail_*",
            listOf(
                "detail_from", "detail_to", "detail_description", "detail_type", "detail_purchased",
                "detail_booked", "detail_value_date", "detail_account", "detail_category",
            ),
            18,
        ),
        Budget(
            "day_*",
            listOf("day_partial", "day_unconverted"),
            28,
        ),
    )

    private val locales = listOf("en", "uk", "pl")

    /** A rename must not silently drop a budgeted key from the reference locale. */
    @Test
    fun `budgeted keys exist in the default locale`() {
        val defaults = LocaleStrings.keys("en")
        for (budget in budgets) {
            val missing = budget.keys - defaults
            assertTrue(
                "${budget.group}: budgeted keys missing from values/strings.xml: $missing",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `labels stay within the character budget`() {
        for (budget in budgets) {
            for (locale in locales) {
                for (key in budget.keys) {
                    val text = LocaleStrings.text(locale, key)
                    assertTrue(
                        "$locale/$key: \"$text\" is ${text.length} chars, budget ${budget.max} (${budget.group})",
                        text.length <= budget.max,
                    )
                }
            }
        }
    }
}
