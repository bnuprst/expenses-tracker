package com.bnuprst.expenses.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bnuprst.expenses.android.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the exact resolved text of every string touched by the translation fixes, so a later edit
 * cannot silently reintroduce the old wording. `getString(id, args)` formats with the JVM default
 * locale, so it is pinned to [Locale.ROOT] and restored afterwards (same pattern as FormattingTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslatedStringsTest {
    private val original = Locale.getDefault()

    @Before fun pin() = Locale.setDefault(Locale.ROOT)

    @After fun restore() = Locale.setDefault(original)

    private fun s(qualifiers: String, id: Int, vararg args: Any): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        return ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
    }

    @Test
    fun `member added leads with the verb`() {
        assertEquals("Dodano Ola do Dom", s("pl", R.string.msg_member_added, "Ola", "Dom"))
        assertEquals("Додано Оля до Дім", s("uk", R.string.msg_member_added, "Оля", "Дім"))
        assertEquals("Ola added to Home", s("en", R.string.msg_member_added, "Ola", "Home"))
    }

    @Test
    fun `purchased label names a date`() {
        assertEquals("Data zakupu", s("pl", R.string.detail_purchased))
        assertEquals("Дата покупки", s("uk", R.string.detail_purchased))
        assertEquals("Purchased", s("en", R.string.detail_purchased))
    }

    @Test
    fun `memory source talks about decisions not elections`() {
        assertEquals("Zapamiętane z Twoich wcześniejszych decyzji dla tego sprzedawcy",
            s("pl", R.string.source_memory))
        assertEquals("Запамʼятовано з ваших попередніх рішень для цього продавця",
            s("uk", R.string.source_memory))
    }

    @Test
    fun `create subtitle uses singular imperative in polish`() {
        assertEquals("Śledź wydatki rodziny razem", s("pl", R.string.login_subtitle_create))
        assertEquals("Ведіть сімейні витрати разом", s("uk", R.string.login_subtitle_create))
    }

    @Test
    fun `ai source uses the established term per language`() {
        assertEquals("Zasugerowane przez AI", s("pl", R.string.source_llm))
        assertEquals("Запропоновано ШІ", s("uk", R.string.source_llm))
        assertEquals("Suggested by AI", s("en", R.string.source_llm))
    }

    @Test
    fun `ai confidence keeps the literal percent sign`() {
        assertEquals("Zasugerowane przez AI (pewność 80%)", s("pl", R.string.source_llm_confidence, 80))
        assertEquals("Запропоновано ШІ (впевненість 80%)", s("uk", R.string.source_llm_confidence, 80))
        assertEquals("Suggested by AI (80% sure)", s("en", R.string.source_llm_confidence, 80))
    }

    @Test
    fun `household category does not collide with housing`() {
        assertEquals("Artykuły domowe", s("pl", R.string.cat_household))
        assertEquals("Mieszkanie i czynsz", s("pl", R.string.cat_housing))
        assertEquals("Товари для дому", s("uk", R.string.cat_household))
        assertEquals("Житло та оренда", s("uk", R.string.cat_housing))
    }

    @Test
    fun `accounts heading is translated`() {
        assertEquals("Accounts", s("en", R.string.banks_accounts_title))
        assertEquals("Рахунки", s("uk", R.string.banks_accounts_title))
        assertEquals("Rachunki", s("pl", R.string.banks_accounts_title))
    }

    @Test
    fun `shortened labels keep the new wording`() {
        assertEquals("Прострочено", s("uk", R.string.status_expired))
        assertEquals("Sprawdź", s("pl", R.string.tab_review))
    }
}
