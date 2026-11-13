package com.bnuprst.expenses.android.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CategoryNamesTest {
    private val seeded = CategoryDto(1, "Groceries", CategoryKind.EXPENSE, null, "groceries")
    private val custom = CategoryDto(2, "Groceries", CategoryKind.EXPENSE, null, null)

    private fun name(qualifiers: String, c: CategoryDto): String {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val context = ApplicationProvider.getApplicationContext<Context>()
        return seededCategoryNameRes(c.slug)?.let { context.getString(it) } ?: c.name
    }

    @Test
    fun `seeded categories are translated and custom ones are not`() {
        assertEquals("Продукти", name("uk", seeded))
        assertEquals("Zakupy spożywcze", name("pl", seeded))
        assertEquals("Groceries", name("en", seeded))
        // A category the user created that happens to be called "Groceries" must stay as typed.
        assertEquals("Groceries", name("uk", custom))
        assertEquals("Groceries", name("pl", custom))
    }

    @Test
    fun `search matches the displayed name, not the stored english one`() {
        RuntimeEnvironment.setQualifiers("uk")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val all = listOf(seeded, custom, CategoryDto(3, "Моя категорія", CategoryKind.EXPENSE, null, null))
        val display = { c: CategoryDto -> seededCategoryNameRes(c.slug)?.let { context.getString(it) } ?: c.name }
        assertEquals(listOf(1L), filterCategories(all, "прод", display).map { it.id })
        assertEquals(listOf(2L), filterCategories(all, "grocer", display).map { it.id })
        assertEquals(listOf(3L), filterCategories(all, "моя", display).map { it.id })
        assertEquals(all.map { it.id }, filterCategories(all, "  ", display).map { it.id })
    }
}
