package com.bnuprst.expenses.android.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * Compares every locale `strings.xml` against the default `values/strings.xml` directly from the
 * XML sources (no Robolectric): identical keys, identical placeholder sets, and the plural
 * quantities each language actually uses. The Gradle unit-test working directory is the module dir
 * (`android/app`), so the resource tree is at `src/main/res`.
 */
class StringResourceParityTest {
    private val res = File("src/main/res")

    private val pluralRules = mapOf(
        "pl" to setOf("one", "few", "many", "other"),
        "uk" to setOf("one", "few", "many", "other"),
    )
    private val defaultPluralQuantities = setOf("one", "other")

    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    /** Placeholder *tokens* (not MatchResults) in source order, sorted for order-insensitive comparison. */
    private fun placeholders(text: String): List<String> =
        placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun document(dir: File): Element = parse(File(dir, "strings.xml"))

    private fun childElements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun strings(dir: File): Map<String, String> =
        childElements(document(dir), "string").associate { it.getAttribute("name") to it.textContent }

    private fun plurals(dir: File): Map<String, Map<String, String>> =
        childElements(document(dir), "plurals").associate { p ->
            p.getAttribute("name") to
                childElements(p, "item").associate { it.getAttribute("quantity") to it.textContent }
        }

    private fun defaultDir(): File = File(res, "values")

    /** `values-pl`, `values-pl-rPL` and `values-b+sr+Latn` all reduce to their language code. */
    private fun languageCode(dir: File): String {
        val qualifiers = dir.name.removePrefix("values-")
        return qualifiers.substringBefore('-').substringBefore('+')
    }

    private fun localeDirs(): List<File> =
        (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .sortedBy { it.name }

    @Test
    fun `every locale has exactly the default keys`() {
        val defaultStrings = strings(defaultDir()).keys
        val defaultPlurals = plurals(defaultDir()).keys
        for (dir in localeDirs()) {
            val actualStrings = strings(dir).keys
            assertEquals(
                "${dir.name}: missing strings ${defaultStrings - actualStrings}; extra strings ${actualStrings - defaultStrings}",
                defaultStrings, actualStrings,
            )
            val actualPlurals = plurals(dir).keys
            assertEquals(
                "${dir.name}: missing plurals ${defaultPlurals - actualPlurals}; extra plurals ${actualPlurals - defaultPlurals}",
                defaultPlurals, actualPlurals,
            )
        }
    }

    @Test
    fun `placeholders match the default in every locale`() {
        val defaultStrings = strings(defaultDir())
        val defaultPlurals = plurals(defaultDir())
        for (dir in localeDirs()) {
            for ((name, text) in strings(dir)) {
                val expected = defaultStrings[name] ?: continue
                assertEquals("${dir.name}/$name placeholders differ", placeholders(expected), placeholders(text))
            }
            for ((name, items) in plurals(dir)) {
                val defaultOther = defaultPlurals[name]?.get("other")
                assertNotNull("default plurals/$name has no other item", defaultOther)
                val expected = placeholders(defaultOther!!)
                for ((quantity, text) in items) {
                    assertEquals(
                        "${dir.name}/plurals/$name/$quantity placeholders differ",
                        expected, placeholders(text),
                    )
                }
            }
        }
    }

    @Test
    fun `plurals cover each language's quantities`() {
        val expectedFor = { code: String ->
            if (code == "en") defaultPluralQuantities
            else pluralRules[code] ?: error("No plural rule declared for locale '$code'")
        }
        for ((name, items) in plurals(defaultDir())) {
            assertEquals("values/plurals/$name quantities", expectedFor("en"), items.keys)
        }
        for (dir in localeDirs()) {
            val expected = expectedFor(languageCode(dir))
            for ((name, items) in plurals(dir)) {
                assertEquals("${dir.name}/plurals/$name quantities", expected, items.keys)
            }
        }
    }

    @Test
    fun `no locale directory is missing from localeConfig`() {
        val languageCodes = localeDirs().map(::languageCode).toSet()

        val config = parse(File(res, "xml/locales_config.xml"))
        val configNodes = config.getElementsByTagName("locale")
        val configuredInConfig = (0 until configNodes.length)
            .map { (configNodes.item(it) as Element).getAttribute("android:name") }
            .toSet() - "en"

        val gradle = File("build.gradle.kts").readText()
        val match = Regex("""resourceConfigurations\s*\+=\s*listOf\(([^)]*)\)""").find(gradle)
        assertNotNull("resourceConfigurations += listOf(...) not found in build.gradle.kts", match)
        val configuredInGradle = Regex("\"([^\"]+)\"")
            .findAll(match!!.groupValues[1])
            .map { it.groupValues[1] }
            .toSet() - "en"

        assertEquals("values-* directories vs app/build.gradle.kts resourceConfigurations", configuredInGradle, languageCodes)
        assertEquals("values-* directories vs res/xml/locales_config.xml", configuredInConfig, languageCodes)
    }
}
