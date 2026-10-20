package com.bnuprst.expenses

import com.bnuprst.expenses.banking.EbAccess
import com.bnuprst.expenses.banking.EbAccount
import com.bnuprst.expenses.banking.EbAccountId
import com.bnuprst.expenses.banking.EbAmount
import com.bnuprst.expenses.banking.EbAspsp
import com.bnuprst.expenses.banking.EbAuthRequest
import com.bnuprst.expenses.banking.EbAuthResponse
import com.bnuprst.expenses.banking.EbParty
import com.bnuprst.expenses.banking.EbSessionResponse
import com.bnuprst.expenses.banking.EbTransaction
import com.bnuprst.expenses.banking.EbTransactionsPage
import com.bnuprst.expenses.banking.EnableBankingApi
import com.bnuprst.expenses.categorization.FakeLlm
import com.bnuprst.expenses.config.AppConfig
import com.bnuprst.expenses.config.DbConfig
import com.bnuprst.expenses.db.Db
import com.bnuprst.expenses.shared.AuthResponse
import com.bnuprst.expenses.shared.BankAuthResponse
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.SyncResultDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.shared.WalletDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object TestDb {
    val config: DbConfig by lazy {
        val url = System.getenv("TEST_DB_URL")?.takeIf { it.isNotBlank() }
        if (url != null) {
            DbConfig(url, System.getenv("TEST_DB_USER") ?: "expenses", System.getenv("TEST_DB_PASSWORD") ?: "expenses")
        } else {
            val pg = PostgreSQLContainer("postgres:16-alpine").apply { start() }
            DbConfig(pg.jdbcUrl, pg.username, pg.password)
        }
    }
    init { Db.connect(config) }

    fun reset() = transaction {
        exec("TRUNCATE users, wallets, bank_connections RESTART IDENTITY CASCADE")
    }
}

class FakeBank : EnableBankingApi {
    var lastAuth: EbAuthRequest? = null
    var pages: List<EbTransaction> = emptyList()
    var transactionCalls = 0

    override suspend fun aspsps(country: String?) = listOf(EbAspsp("Nordea", "FI", maximumConsentValidity = 90L * 86400))
    override suspend fun startAuth(request: EbAuthRequest) = EbAuthResponse("https://bank.example/auth?s=${request.state}").also { lastAuth = request }
    override suspend fun createSession(code: String) =
        EbSessionResponse("sess-1", access = EbAccess("2027-01-01T00:00:00+00:00")) to
            listOf(EbAccount("uid-1", EbAccountId(iban = "FI2112345600000785"), "hash-1", "Main", "EUR"))
    override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?): EbTransactionsPage {
        transactionCalls++
        return EbTransactionsPage(pages)
    }
}

class IntegrationTest {
    private val llm = FakeLlm("""{"category_id": 0, "confidence": 0.3}""")
    private val bank = FakeBank()

    @BeforeTest
    fun reset() {
        TestDb.reset()
    }

    private fun app(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val base = AppConfig.fromEnv(
            mapOf(
                "JWT_SECRET" to "test-secret", "EB_APP_ID" to "app", "EB_PRIVATE_KEY_PATH" to "/dev/null",
                "LLM_API_KEY" to "x", "APP_REDIRECT_URL" to "expenses://bank-connected",
            )
        )
        val services = buildServices(base.copy(db = TestDb.config), llmProvider = llm, bankingApi = bank)
        application { module(services) }
        val client = createClient {
            install(ContentNegotiation) { json() }
            followRedirects = false
        }
        block(client)
    }

    private suspend fun HttpClient.register(email: String): AuthResponse =
        post("/api/v1/auth/register") { json(); setBody(mapOf("email" to email, "password" to "password123", "name" to email.substringBefore('@'))) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.json() = contentType(ContentType.Application.Json)

    private suspend fun HttpClient.createWallet(token: String): WalletDto =
        post("/api/v1/wallets") { bearerAuth(token); json(); setBody(mapOf("name" to "Family", "currency" to "EUR")) }.body()

    private suspend fun HttpClient.addTxn(token: String, walletId: Long, desc: String, counterparty: String?): TransactionDto =
        post("/api/v1/wallets/$walletId/transactions") {
            bearerAuth(token); json()
            setBody(mapOf("bookingDate" to "2026-10-10", "amount" to "-12.50", "description" to desc, "counterparty" to counterparty))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    @Test
    fun `correction feeds merchant memory so the next transaction skips the llm`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        val groceries = categories.first { it.name == "Groceries" }
        llm.reply = """{"category_id": ${groceries.id}, "confidence": 0.4}"""

        val first = client.addTxn(token, wallet.id, "K-MARKET KAMPPI 12.10 *1234", "K-Market Kamppi")
        assertEquals(TxnStatus.REVIEW, first.status)
        assertEquals(groceries.id, first.categoryId)
        assertEquals(1, llm.calls)

        val review: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/review") { bearerAuth(token) }.body()
        assertEquals(listOf(first.id), review.map { it.id })

        val corrected: TransactionDto = client.patch("/api/v1/transactions/${first.id}") {
            bearerAuth(token); json(); setBody(mapOf("categoryId" to groceries.id))
        }.body()
        assertEquals(CategorySource.MANUAL, corrected.categorySource)

        val second = client.addTxn(token, wallet.id, "K-MARKET KAMPPI 14.10 *1234", "K-MARKET KAMPPI")
        assertEquals(CategorySource.MEMORY, second.categorySource)
        assertEquals(TxnStatus.CATEGORIZED, second.status)
        assertEquals(1, llm.calls, "memory hit must not call the LLM")
    }

    @Test
    fun `wallets are private until a member is added`() = app { client ->
        val anna = client.register("anna@example.com").token
        val ben = client.register("ben@example.com").token
        val wallet = client.createWallet(anna)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(ben) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/wallets").status)

        val added = client.post("/api/v1/wallets/${wallet.id}/members") { bearerAuth(anna); json(); setBody(mapOf("email" to "ben@example.com")) }
        assertEquals(HttpStatusCode.Created, added.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(ben) }.status)
        // members can't invite
        val denied = client.post("/api/v1/wallets/${wallet.id}/members") { bearerAuth(ben); json(); setBody(mapOf("email" to "anna@example.com")) }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun `bank connect, callback, sync and dedup`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)

        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "fi"))
        }.body()
        val state = bank.lastAuth!!.state
        assertTrue(auth.authUrl.contains(state))
        assertTrue(bank.lastAuth!!.access.validUntil.startsWith(LocalDate.now().plusDays(90).toString().take(7)))

        val bad: HttpResponse = client.get("/api/v1/banking/callback?state=nope&code=x")
        assertTrue(bad.headers[HttpHeaders.Location]!!.contains("status=error"))

        bank.pages = listOf(
            EbTransaction("R1", null, EbAmount("EUR", "20.00"), "DBIT", "BOOK", "2026-10-01", creditor = EbParty("Lidl"), remittanceInformation = listOf("LIDL 123")),
            EbTransaction("R2", null, EbAmount("EUR", "1000.00"), "CRDT", "BOOK", "2026-10-02", debtor = EbParty("ACME"), remittanceInformation = listOf("Salary")),
        )
        val cb: HttpResponse = client.get("/api/v1/banking/callback?state=$state&code=abc")
        assertEquals(HttpStatusCode.Found, cb.status)
        val location = Url(cb.headers[HttpHeaders.Location]!!)
        assertEquals("ok", location.parameters["status"])
        assertEquals(auth.connectionId.toString(), location.parameters["connection"])

        val r1: SyncResultDto = client.post("/api/v1/bank-connections/${auth.connectionId}/sync") { bearerAuth(token) }.body()
        val r2: SyncResultDto = client.post("/api/v1/bank-connections/${auth.connectionId}/sync") { bearerAuth(token) }.body()
        // The callback also starts a background sync; between all syncs exactly two rows exist.
        assertEquals(0, r2.inserted)
        assertTrue(r1.inserted <= 2)
        val txns: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body()
        assertEquals(setOf("-20.00", "1000.00"), txns.map { it.amount }.toSet())

        val conns: List<BankConnectionDto> = client.get("/api/v1/bank-connections") { bearerAuth(token) }.body()
        val conn = conns.single()
        assertEquals(ConnectionStatus.ACTIVE, conn.status)
        assertEquals("•••• 0785", conn.accounts.single().ibanMasked)
        assertEquals(false, conn.renewSoon)
    }
}
