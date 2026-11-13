package com.bnuprst.expenses.android.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingTest {
    private val original = Locale.getDefault()

    @After fun restore() = Locale.setDefault(original)

    @Test
    fun `money follows the active locale`() {
        Locale.setDefault(Locale.forLanguageTag("pl"))
        val pl = formatMoney(BigDecimal("1234.50"), "PLN")
        assertTrue(pl, pl.contains("1") && pl.contains(",50"))
        Locale.setDefault(Locale.forLanguageTag("en"))
        assertTrue(formatMoney(BigDecimal("1234.50"), "EUR").contains(".50"))
    }

    @Test
    fun `month and day names follow the active locale`() {
        val date = LocalDate.of(2026, 3, 5)
        Locale.setDefault(Locale.forLanguageTag("uk"))
        val uk = date.format(dayFormat())
        Locale.setDefault(Locale.forLanguageTag("pl"))
        val pl = date.format(dayFormat())
        Locale.setDefault(Locale.forLanguageTag("en"))
        val en = date.format(dayFormat())
        assertEquals(3, setOf(uk, pl, en).size)
        assertTrue(uk, uk.lowercase().contains("бер"))
        assertTrue(pl, pl.lowercase().contains("mar"))
        assertTrue(en, en.contains("Mar"))
    }

    @Test
    fun `percentages use the locale decimal separator`() {
        assertEquals("12,3", String.format(Locale.forLanguageTag("uk"), "%.1f", 12.34))
        assertEquals("12.3", String.format(Locale.forLanguageTag("en"), "%.1f", 12.34))
    }

    @Test
    fun `sync timestamps are converted to the device timezone`() {
        val originalZone = TimeZone.getDefault()
        try {
            // Polish uses a 24-hour clock, so the hour is zero-padded: 09:41 / 10:41.
            Locale.setDefault(Locale.forLanguageTag("pl"))
            // 09:41 UTC is 10:41 in Warsaw in November (CET, UTC+1).
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            assertTrue(formatDateTime("2026-11-13T09:41:00Z").contains("10:41"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertTrue(formatDateTime("2026-11-13T09:41:00Z").contains("09:41"))
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `sync timestamps follow the active locale`() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.forLanguageTag("pl"))
            val pl = formatDateTime("2026-11-13T09:41:00Z")
            Locale.setDefault(Locale.forLanguageTag("en"))
            val en = formatDateTime("2026-11-13T09:41:00Z")
            assertNotEquals(pl, en)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `unparseable sync timestamps fall back to raw text`() {
        assertEquals("bogus", formatDateTime("bogus"))
        assertEquals("", formatDateTime(""))
    }
}
