package com.bnuprst.expenses.categorization

/**
 * Turns a raw bank description / counterparty name into a stable merchant key, so that
 * "K-MARKET KAMPPI 12.03 *1234" and "K-Market Kamppi 14.03" map to the same key.
 */
object Normalizer {
    private const val MAX_TOKENS = 3
    const val UNKNOWN = "unknown"

    private val prefixes = listOf(
        "card purchase", "card payment", "debit card", "contactless", "purchase", "payment to", "payment",
        "pos", "visa", "mastercard", "maestro", "apple pay", "google pay", "korttiosto", "kortköp", "kartenzahlung",
    )
    private val legalSuffixes = setOf(
        "oy", "oyj", "ky", "tmi", "ab", "hb", "kb", "as", "asa", "aps", "gmbh", "ag", "kg", "ltd", "llc", "inc", "plc", "bv", "nv",
        "srl", "sa", "sas", "spa", "sia", "uab", "ou", "oü", "sp", "zoo", "z", "o", "co", "corp", "company",
    )
    private val maskedCard = Regex("""(\*+|x{2,})\d+""", RegexOption.IGNORE_CASE)
    private val dateLike = Regex("""\b\d{1,4}[./-]\d{1,2}([./-]\d{1,4})?\b""")
    private val timeLike = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
    private val url = Regex("""\b(https?://|www\.)|\.(com|net|org|eu|fi|se|de|ee|lv|lt|pl|uk)\b""", RegexOption.IGNORE_CASE)
    private val tokenWithDigit = Regex("""\S*\d\S*""")
    private val nonLetter = Regex("""[^\p{L}& ]+""")
    private val spaces = Regex("""\s+""")

    fun merchantKey(counterparty: String?, description: String): String {
        val source = counterparty?.takeIf { it.isNotBlank() } ?: description
        return normalize(source).ifEmpty { normalize(description) }.ifEmpty { UNKNOWN }
    }

    /**
     * The catch-all key groups unrelated transactions, so a decision about one of them must never be
     * remembered or spread to the rest.
     */
    fun isLearnable(merchantKey: String) = merchantKey != UNKNOWN

    fun normalize(input: String): String {
        var s = input.lowercase()
            .replace(maskedCard, " ")
            .replace(dateLike, " ")
            .replace(timeLike, " ")
            .replace(url, " ")
            .replace(tokenWithDigit, " ")
            .replace('-', ' ')
            .replace(nonLetter, " ")
            .replace(spaces, " ")
            .trim()
        for (p in prefixes) {
            if (s.startsWith("$p ")) s = s.removePrefix(p).trim()
        }
        return s.split(' ')
            .filter { it.isNotEmpty() && it !in legalSuffixes }
            .take(MAX_TOKENS)
            .joinToString(" ")
    }
}
