package com.bnuprst.expenses.categorization

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizerTest {
    @Test
    fun `strips card numbers dates and references`() {
        assertEquals("k market kamppi", Normalizer.normalize("K-MARKET KAMPPI 12.03 *1234"))
        assertEquals("k market kamppi", Normalizer.normalize("K-Market Kamppi 14.03.2026"))
    }

    @Test
    fun `strips payment prefixes and legal suffixes`() {
        assertEquals("spotify", Normalizer.normalize("CARD PURCHASE Spotify AB 4571xxxx1234"))
        assertEquals("lidl suomi", Normalizer.normalize("Lidl Suomi KY Oy"))
    }

    @Test
    fun `prefers counterparty over description`() {
        assertEquals("alepa", Normalizer.merchantKey("ALEPA", "Viite 123456789 maksu"))
        assertEquals("netflix", Normalizer.merchantKey("  ", "NETFLIX.COM 866-579-7172"))
    }

    @Test
    fun `falls back to unknown`() {
        assertEquals("unknown", Normalizer.merchantKey(null, "123 456 *999"))
    }
}
