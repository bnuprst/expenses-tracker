package com.bnuprst.expenses.i18n

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessagesTest {
    private fun specifiers(s: String) = Regex("%[a-z]").findAll(s).map { it.value }.toList()

    @Test
    fun `every language defines every key with the same placeholders`() {
        val en = Messages.catalogues.getValue("en")
        assertEquals(52, en.size)
        for ((lang, catalogue) in Messages.catalogues) {
            assertEquals(en.keys, catalogue.keys, "keys differ for $lang")
            for ((key, pattern) in catalogue) {
                assertEquals(specifiers(en.getValue(key)), specifiers(pattern), "placeholders differ for $key in $lang")
            }
        }
    }

    @Test
    fun `accept-language picks the highest-weighted supported language`() {
        assertEquals("uk", Messages.language("uk-UA,uk;q=0.9,en;q=0.8"))
        assertEquals("pl", Messages.language("pl-PL"))
        assertEquals("pl", Messages.language("en;q=0,pl;q=1"))
        assertEquals("pl", Messages.language("en;q=0.4,pl;q=0.8"))
        assertEquals("uk", Messages.language("de-DE,uk-UA;q=0.5"))
        assertEquals("en", Messages.language(null))
        assertEquals("en", Messages.language(""))
        assertEquals("en", Messages.language("de-DE,fr;q=0.7"))
        assertEquals("en", Messages.language("*"))
    }

    @Test
    fun `english stays the fallback and statuses are translated`() {
        assertTrue(Messages.format("bank.sync_running").startsWith("A sync is already running"))
        assertEquals("Невідомий банк «X» у PL", Messages.format("bank.unknown", listOf("X", "PL"), "uk"))
        assertEquals("Стан підключення: Очікує", Messages.format("bank.connection_status", listOf(Messages.status("PENDING", "uk")), "uk"))
        assertEquals("Stan połączenia: Wygasłe", Messages.format("bank.connection_status", listOf(Messages.status("EXPIRED", "pl")), "pl"))
        // unknown language and unknown key both degrade predictably
        assertEquals("Not found", Messages.format("error.not_found", language = "de"))
        assertEquals("nope.missing", Messages.format("nope.missing", language = "uk"))
    }
}
