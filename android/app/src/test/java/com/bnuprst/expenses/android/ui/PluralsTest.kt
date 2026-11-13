package com.bnuprst.expenses.android.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.bnuprst.expenses.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluralsTest {
    private fun quantities(qualifiers: String, id: Int, counts: List<Int>): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers)
        return counts.map { context.resources.getQuantityString(id, it, it) }
    }

    private val counts = listOf(1, 2, 5, 21, 22, 25)

    @Test
    fun `ukrainian transaction counts use one few many`() {
        assertEquals(
            listOf("1 транзакція", "2 транзакції", "5 транзакцій", "21 транзакція", "22 транзакції", "25 транзакцій"),
            quantities("uk", R.plurals.stats_transactions_count, counts),
        )
    }

    @Test
    fun `polish transaction counts use one few many`() {
        assertEquals(
            listOf("1 transakcja", "2 transakcje", "5 transakcji", "21 transakcji", "22 transakcje", "25 transakcji"),
            quantities("pl", R.plurals.stats_transactions_count, counts),
        )
    }

    @Test
    fun `english plurals are singular at one`() {
        assertEquals(listOf("1 transaction", "2 transactions"), quantities("en", R.plurals.stats_transactions_count, listOf(1, 2)))
        assertEquals(listOf("Search 1 bank", "Search 3 banks"), quantities("en", R.plurals.banks_search_hint, listOf(1, 3)))
        assertEquals(listOf("Consent up to 1 day", "Consent up to 90 days"), quantities("en", R.plurals.banks_consent_days, listOf(1, 90)))
        assertEquals(listOf("1 category", "4 categories"), quantities("en", R.plurals.stats_categories_count, listOf(1, 4)))
    }

    @Test
    fun `consent length reads naturally in polish`() {
        assertEquals(
            listOf(
                "Zgoda maksymalnie na 1 dzień", "Zgoda maksymalnie na 2 dni", "Zgoda maksymalnie na 5 dni",
                "Zgoda maksymalnie na 22 dni", "Zgoda maksymalnie na 90 dni",
            ),
            quantities("pl", R.plurals.banks_consent_days, listOf(1, 2, 5, 22, 90)),
        )
    }

    @Test
    fun `consent length reads naturally in ukrainian`() {
        assertEquals(
            listOf(
                "Згода максимум на 1 день", "Згода максимум на 2 дні", "Згода максимум на 5 днів",
                "Згода максимум на 22 дні", "Згода максимум на 90 днів",
            ),
            quantities("uk", R.plurals.banks_consent_days, listOf(1, 2, 5, 22, 90)),
        )
    }

    @Test
    fun `every language resolves every plural at the tricky counts`() {
        for (q in listOf("en", "uk", "pl")) {
            for (id in listOf(R.plurals.banks_search_hint, R.plurals.banks_consent_days, R.plurals.stats_other_currency, R.plurals.stats_categories_count, R.plurals.stats_transactions_count)) {
                quantities(q, id, counts).forEach { assert(it.isNotBlank()) }
            }
        }
    }
}
