package com.bnuprst.expenses.android.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads the raw locale `strings.xml` files directly (no Robolectric), so tests that only need the
 * authored text can share one parser. The Gradle unit-test working directory is the module dir
 * (`android/app`), so the resource tree is at `src/main/res`.
 */
object LocaleStrings {
    private val res = File("src/main/res")
    private val dirs = mapOf("en" to "values", "uk" to "values-uk", "pl" to "values-pl")
    private val cache = mutableMapOf<String, Map<String, String>>()

    fun text(locale: String, key: String): String =
        loaded(locale)[key] ?: error("Missing string '$key' in ${dirs[locale] ?: locale}/strings.xml")

    fun keys(locale: String): Set<String> = loaded(locale).keys

    private fun loaded(locale: String): Map<String, String> = cache.getOrPut(locale) { load(locale) }

    private fun load(locale: String): Map<String, String> {
        val dir = dirs[locale] ?: error("Unknown locale '$locale'")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val root = factory.newDocumentBuilder().parse(File(File(res, dir), "strings.xml")).documentElement
        val nodes = root.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val element = nodes.item(it) as Element
            element.getAttribute("name") to unescape(element.textContent)
        }
    }

    /** aapt strips the XML escape `\'`; measurement and counting must see the real apostrophe. */
    private fun unescape(text: String): String = text.replace("\\'", "'")
}
