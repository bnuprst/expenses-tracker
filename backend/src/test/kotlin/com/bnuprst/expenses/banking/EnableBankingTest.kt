package com.bnuprst.expenses.banking

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnableBankingTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `jwt is RS256 with app id as kid and is cached`() {
        var now = Instant.parse("2026-10-01T10:00:00Z")
        val signer = EnableBankingJwtSigner("app-123", keyPair.private as RSAPrivateKey, clock = { now })
        val token = signer.token()
        val decoded = JWT.require(Algorithm.RSA256(keyPair.public as RSAPublicKey, null))
            .withIssuer("enablebanking.com").withAudience("api.enablebanking.com")
            .acceptExpiresAt(Long.MAX_VALUE / 1000).acceptIssuedAt(Long.MAX_VALUE / 1000)
            .build().verify(token)
        assertEquals("app-123", decoded.keyId)
        assertEquals("RS256", decoded.algorithm)
        assertEquals("JWT", decoded.type)
        assertEquals(3600, decoded.expiresAtAsInstant.epochSecond - decoded.issuedAtAsInstant.epochSecond)
        assertSame(token, signer.token())
        now = now.plusSeconds(3400) // within 5 minutes of expiry → refreshed
        assertNotEquals(token, signer.token())
    }

    @Test
    fun `loads pkcs8 pem`() {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        assertEquals((keyPair.private as RSAPrivateKey).modulus, EnableBankingClient.loadPrivateKey(pem).modulus)
    }

    @Test
    fun `maps sample transactions`() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<EbTransactionsPage>(javaClass.getResource("/enablebanking/transactions.json")!!.readText())
        val mapped = TransactionMapper.map(page.transactions, LocalDate.parse("2026-10-20"))

        assertEquals(4, mapped.size, "pending transaction is skipped")
        val grocery = mapped[0]
        assertEquals("REF-1", grocery.externalId)
        assertEquals(BigDecimal("-23.45"), grocery.amount)
        assertEquals("K-Market Kamppi", grocery.counterparty)
        assertEquals("K-MARKET KAMPPI 12.10", grocery.description)
        assertEquals(LocalDate.parse("2026-10-11"), grocery.transactionDate)
        assertEquals(LocalDate.parse("2026-10-12"), grocery.valueDate)
        assertEquals("Card payment", grocery.paymentType)

        val salary = mapped[1]
        assertEquals(BigDecimal("3200.00"), salary.amount)
        assertEquals("ACME Oy", salary.counterparty)
        assertEquals("FI2112345600000785", salary.counterpartyIban, "the payer's account for money in, without spaces")
        assertEquals(null, salary.paymentType)

        // Two identical purchases without references both survive, with stable distinct ids.
        assertTrue(mapped[2].externalId!!.startsWith("h:"))
        assertNotEquals(mapped[2].externalId, mapped[3].externalId)
        assertEquals(mapped.map { it.externalId }, TransactionMapper.map(page.transactions, LocalDate.parse("2026-10-20")).map { it.externalId })
        assertEquals("cont-1", page.continuationKey)
    }

    @Test
    fun `hash ids stay distinct across pages of one fetch`() {
        val coffee = EbTransaction(
            transactionAmount = EbAmount("PLN", "4.50"), creditDebitIndicator = "DBIT", status = "BOOK",
            bookingDate = "2026-10-16", creditor = EbParty("Cafe"), remittanceInformation = listOf("CAFE"),
        )
        val seen = mutableMapOf<String, Int>()
        val page1 = TransactionMapper.map(listOf(coffee), LocalDate.parse("2026-10-20"), seen)
        val page2 = TransactionMapper.map(listOf(coffee), LocalDate.parse("2026-10-20"), seen)
        assertNotEquals(page1.single().externalId, page2.single().externalId)
        assertTrue(page2.single().externalId!!.endsWith(":2"))
    }
}
