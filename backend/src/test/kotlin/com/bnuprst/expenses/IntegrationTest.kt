package com.bnuprst.expenses

import com.bnuprst.expenses.banking.EbAccess
import com.bnuprst.expenses.banking.EbAccount
import com.bnuprst.expenses.banking.EbAccountId
import com.bnuprst.expenses.banking.EbBankTxnCode
import com.bnuprst.expenses.banking.EbAmount
import com.bnuprst.expenses.banking.EbAspsp
import com.bnuprst.expenses.banking.EbAuthRequest
import com.bnuprst.expenses.banking.EbAuthResponse
import com.bnuprst.expenses.banking.EbParty
import com.bnuprst.expenses.banking.EbSessionResponse
import com.bnuprst.expenses.banking.EbTransaction
import com.bnuprst.expenses.banking.EbTransactionsPage
import com.bnuprst.expenses.banking.EnableBankingApi
import com.bnuprst.expenses.banking.EnableBankingException
import com.bnuprst.expenses.banking.PsuContext
import com.bnuprst.expenses.categorization.FakeLlm
import com.bnuprst.expenses.config.AppConfig
import com.bnuprst.expenses.config.DbConfig
import com.bnuprst.expenses.db.Db
import com.bnuprst.expenses.db.FxCoverage
import com.bnuprst.expenses.db.FxRates
import com.bnuprst.expenses.db.Transactions
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.fx.FakeFxProvider
import com.bnuprst.expenses.fx.FxDay
import com.bnuprst.expenses.fx.MutableClock
import com.bnuprst.expenses.shared.AuthResponse
import com.bnuprst.expenses.shared.BankAuthResponse
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.CreateTransactionRequest
import com.bnuprst.expenses.shared.ErrorResponse
import com.bnuprst.expenses.shared.FinalizeImportResult
import com.bnuprst.expenses.shared.ImportResult
import com.bnuprst.expenses.shared.StatsDto
import com.bnuprst.expenses.shared.SyncResultDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import com.bnuprst.expenses.shared.WalletDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        exec("TRUNCATE users, wallets, bank_connections, fx_rates, fx_coverage RESTART IDENTITY CASCADE")
    }
}

class FakeBank : EnableBankingApi {
    var lastAuth: EbAuthRequest? = null
    var pages: List<EbTransaction> = emptyList()
    /** Per-account pages, keyed by EbAccount.uid; falls back to [pages] when absent. */
    var pagesByAccount: Map<String, List<EbTransaction>> = emptyMap()
    /** Accounts returned by createSession; tests may replace them with two to exercise own-account pairs. */
    var accounts: List<EbAccount> = listOf(
        EbAccount("uid-1", EbAccountId(iban = "FI2112345600000785"), "hash-1", "Main", "EUR"),
    )
    var transactionCalls = 0
    val dateFroms = mutableListOf<LocalDate>()
    val psus = mutableListOf<PsuContext?>()
    /** Rejects requests made without the user present, like a bank whose PSD2 daily limit is used up. */
    var unattendedLimitReached = false
    /** Rejects requests made with the user present, for the manual-sync rate-limit message. */
    var userLimitReached = false

    override suspend fun aspsps(country: String?) = listOf(EbAspsp("Nordea", "FI", maximumConsentValidity = 90L * 86400))
    override suspend fun startAuth(request: EbAuthRequest) = EbAuthResponse("https://bank.example/auth?s=${request.state}").also { lastAuth = request }
    override suspend fun createSession(code: String) =
        EbSessionResponse("sess-1", access = EbAccess("2027-01-01T00:00:00+00:00")) to accounts
    override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?, psu: PsuContext?): EbTransactionsPage {
        transactionCalls++
        dateFroms += dateFrom
        psus += psu
        if ((unattendedLimitReached && psu == null) || (userLimitReached && psu != null)) {
            throw EnableBankingException(429, """{"code":429,"message":"Too many requests","error":"ASPSP_RATE_LIMIT_EXCEEDED"}""")
        }
        return EbTransactionsPage(pagesByAccount[accountUid] ?: pages)
    }
}

class IntegrationTest {
    private val llm = FakeLlm("""{"category_id": 0, "confidence": 0.3}""")
    private val bank = FakeBank()
    private val fxProvider = FakeFxProvider()
    private lateinit var services: Services

    @BeforeTest
    fun reset() {
        TestDb.reset()
    }

    private fun app(
        extraEnv: Map<String, String> = emptyMap(),
        clock: Clock = Clock.systemUTC(),
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val base = AppConfig.fromEnv(
            mapOf(
                "JWT_SECRET" to "test-secret", "EB_APP_ID" to "app", "EB_PRIVATE_KEY_PATH" to "/dev/null",
                "LLM_API_KEY" to "x", "APP_REDIRECT_URL" to "expenses://bank-connected",
            ) + extraEnv
        )
        services = buildServices(
            base.copy(db = TestDb.config), llmProvider = llm, bankingApi = bank,
            fxProvider = fxProvider, clock = clock,
        )
        application { module(services) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        services.fx.startScheduler(scope)
        val client = createClient {
            install(ContentNegotiation) { json() }
            followRedirects = false
        }
        try {
            block(client)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun HttpClient.register(email: String): AuthResponse =
        post("/api/v1/auth/register") { json(); setBody(mapOf("email" to email, "password" to "password123", "name" to email.substringBefore('@'))) }
            .also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.json() = contentType(ContentType.Application.Json)

    private suspend fun HttpClient.createWallet(token: String): WalletDto =
        post("/api/v1/wallets") { bearerAuth(token); json(); setBody(mapOf("name" to "Family", "currency" to "EUR")) }.body()

    /** The bank callback starts a background sync; a sync request made meanwhile returns at once, so wait it out. */
    private suspend fun HttpClient.sync(token: String, connectionId: Long): SyncResultDto = withTimeout(10_000) {
        var r: SyncResultDto = post("/api/v1/bank-connections/$connectionId/sync") { bearerAuth(token) }.body()
        while (r.error?.startsWith("A sync is already running") == true) {
            delay(20)
            r = post("/api/v1/bank-connections/$connectionId/sync") { bearerAuth(token) }.body()
        }
        r
    }

    private suspend fun HttpClient.addTxn(token: String, walletId: Long, desc: String, counterparty: String?): TransactionDto =
        post("/api/v1/wallets/$walletId/transactions") {
            bearerAuth(token); json()
            setBody(mapOf("bookingDate" to "2026-10-10", "amount" to "-12.50", "description" to desc, "counterparty" to counterparty))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    /** Connects the fake bank and waits for both the callback's background sync and this one. */
    private suspend fun HttpClient.connectAndSync(token: String, walletId: Long): BankAuthResponse {
        val auth: BankAuthResponse = post("/api/v1/wallets/$walletId/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "FI"))
        }.body()
        get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        sync(token, auth.connectionId)
        return auth
    }

    private suspend fun seedRate(asOf: LocalDate, quote: String, value: String) {
        dbQuery {
            FxRates.insert {
                it[FxRates.asOf] = asOf
                it[FxRates.base] = "EUR"
                it[FxRates.quote] = quote
                it[FxRates.rate] = BigDecimal(value)
                it[FxRates.rateSource] = "seed"
            }
            FxCoverage.insert {
                it[FxCoverage.base] = "EUR"
                it[FxCoverage.asOf] = asOf
                it[FxCoverage.published] = true
                it[FxCoverage.fetchedAt] = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            }
        }
    }

    /** A raw row: no bank account, so its account key comes from [account] (or is null when absent). */
    private suspend fun seedRow(
        walletId: Long,
        date: String,
        amount: String,
        currency: String = "EUR",
        account: String? = null,
        description: String = "movement",
        group: String? = null,
    ): Long = dbQuery {
        Transactions.insert {
            it[Transactions.walletId] = walletId
            it[Transactions.bankAccountId] = null
            it[Transactions.externalId] = null
            it[Transactions.bookingDate] = LocalDate.parse(date)
            it[Transactions.amount] = BigDecimal(amount)
            it[Transactions.currency] = currency
            it[Transactions.descriptionRaw] = description
            it[Transactions.counterparty] = null
            it[Transactions.merchantKey] = "seed"
            it[Transactions.categoryId] = null
            it[Transactions.categorySource] = null
            it[Transactions.confidence] = null
            it[Transactions.status] = TxnStatus.UNCATEGORIZED.name
            it[Transactions.sourceAccount] = account
            it[Transactions.transferGroup] = group
        }[Transactions.id]
    }

    private suspend fun groupOf(id: Long): String? = dbQuery {
        Transactions.select(Transactions.transferGroup).where { Transactions.id eq id }.single()[Transactions.transferGroup]
    }

    private suspend fun overrideOf(id: Long): Boolean = dbQuery {
        Transactions.select(Transactions.transferOverride).where { Transactions.id eq id }.single()[Transactions.transferOverride]
    }

    private suspend fun awaitGrouped(first: Long, second: Long) = withTimeout(10_000) {
        while (true) {
            val a = groupOf(first)
            val b = groupOf(second)
            if (a != null && a == b) return@withTimeout
            delay(20)
        }
    }

    private suspend fun HttpClient.createWallet(token: String, currency: String): WalletDto =
        post("/api/v1/wallets") { bearerAuth(token); json(); setBody(mapOf("name" to "Family", "currency" to currency)) }.body()

    private suspend fun HttpClient.groceries(token: String, walletId: Long): Long =
        get("/api/v1/wallets/$walletId/categories") { bearerAuth(token) }
            .body<List<CategoryDto>>().first { it.name == "Groceries" }.id

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

        val r1: SyncResultDto = client.sync(token, auth.connectionId)
        val r2: SyncResultDto = client.sync(token, auth.connectionId)
        // The callback also started a background sync, which fetched first; between all syncs exactly two rows exist.
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

    @Test
    fun `user syncs are marked user-present and scheduled syncs pause at the bank's daily limit`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "fi"))
        }.body()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        client.sync(token, auth.connectionId)
        bank.unattendedLimitReached = true

        val manual: SyncResultDto = client.post("/api/v1/bank-connections/${auth.connectionId}/sync") {
            bearerAuth(token)
            header("X-Forwarded-For", "10.0.0.1, 203.0.113.7")
            header(HttpHeaders.UserAgent, "ExpensesApp/1.0")
        }.body()
        assertEquals(null, manual.error)
        assertEquals(PsuContext("203.0.113.7", "ExpensesApp/1.0"), bank.psus.last())

        services.banking!!.syncAll()
        val conn = client.get("/api/v1/bank-connections") { bearerAuth(token) }.body<List<BankConnectionDto>>().single()
        assertTrue(conn.lastSyncError!!.contains("daily limit"), conn.lastSyncError)

        val calls = bank.transactionCalls
        services.banking!!.syncAll()
        assertEquals(calls, bank.transactionCalls, "a paused connection must not be synced in the background")
    }

    @Test
    fun `abandoned connection attempts are replaced and connections can be removed`() = app { client ->
        val anna = client.register("anna@example.com").token
        val ben = client.register("ben@example.com").token
        val wallet = client.createWallet(anna)
        suspend fun connect(): BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(anna); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "fi"))
        }.body()
        suspend fun list(): List<BankConnectionDto> = client.get("/api/v1/bank-connections") { bearerAuth(anna) }.body()

        connect()
        connect()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&error=access_denied")
        assertEquals(ConnectionStatus.ERROR, list().single().status)
        val latest = connect()
        assertEquals(listOf(latest.connectionId), list().map { it.id }, "the errored attempt is replaced")

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/bank-connections/${latest.connectionId}") { bearerAuth(ben) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/bank-connections/${latest.connectionId}") { bearerAuth(anna) }.status)
        assertTrue(list().isEmpty())
    }

    @Test
    fun `import is idempotent, seeds merchant memory and bounds the first bank sync`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val recent = LocalDate.now().minusDays(10)
        val body = """
            {"source":"actual",
             "categories":[{"name":"Food","kind":"EXPENSE"},{"name":"Paycheck","kind":"INCOME"},{"name":"groceries","kind":"EXPENSE"}],
             "transactions":[
               {"externalId":"t1","date":"2025-01-03","amount":"-20.10","payee":"LIDL SP Z O O","category":"Food"},
               {"externalId":"t2","date":"2025-02-03","amount":"-11.00","payee":"Lidl","category":"Food"},
               {"externalId":"t3","date":"2025-02-05","amount":"-5.00","payee":"Lidl","category":"Groceries"},
               {"externalId":"t4","date":"$recent","amount":"4000.00","payee":"ACME","category":"Paycheck","account":"Main"},
               {"externalId":"t6","date":"$recent","amount":"-7.00","payee":"Kiosk","category":"Food","account":"Main"},
               {"externalId":"c1","date":"${recent.minusDays(20)}","amount":"-2.00","payee":"Market","category":"Food","account":"Cash"},
               {"externalId":"t5","date":"2025-03-01","amount":"-9.99","payee":"Mystery shop"}
             ]}
        """.trimIndent()
        val first: ImportResult = client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(token); json(); setBody(body) }.body()
        assertEquals(ImportResult(received = 7, inserted = 7, categoriesCreated = 2, merchantsLearned = 0), first)
        val again: ImportResult = client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(token); json(); setBody(body) }.body()
        assertEquals(0, again.inserted)
        val finalized: FinalizeImportResult = client.post("/api/v1/wallets/${wallet.id}/import/finalize?source=actual") { bearerAuth(token) }.body()
        assertEquals(4, finalized.merchantsLearned) // lidl, acme, kiosk, market; the uncategorized row teaches nothing


        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        val food = categories.single { it.name == "Food" }
        val review: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/review") { bearerAuth(token) }.body()
        assertEquals(TxnStatus.UNCATEGORIZED, review.single { it.amount == "-9.99" }.status, "uncategorized rows belong in the review queue")

        // Majority vote: Lidl was Food twice, Groceries once.
        val next = client.addTxn(token, wallet.id, "LIDL 1234 WARSZAWA", "LIDL")
        assertEquals(CategorySource.MEMORY, next.categorySource)
        assertEquals(food.id, next.categoryId)
        assertEquals(0, llm.calls)

        // A member cannot import.
        client.register("ben@example.com")
        client.post("/api/v1/wallets/${wallet.id}/members") { bearerAuth(token); json(); setBody(mapOf("email" to "ben@example.com")) }
        val ben = client.post("/api/v1/auth/login") { json(); setBody(mapOf("email" to "ben@example.com", "password" to "password123")) }.body<AuthResponse>().token
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(ben); json(); setBody(body) }.status)

        // The first bank pull starts on the last imported day of the matching account ("Main", the bank
        // account's name) - not 90 days back, not the day after, and not at "Cash", which ends earlier.
        // That day's already-imported rows are not inserted twice; the rest of the day is.
        bank.pages = listOf(
            EbTransaction("B1", null, EbAmount("PLN", "7.00"), "DBIT", "BOOK", recent.toString(), creditor = EbParty("KIOSK 12")),
            EbTransaction("B2", null, EbAmount("PLN", "3.20"), "DBIT", "BOOK", recent.toString(), creditor = EbParty("Bakery")),
            EbTransaction("B3", null, EbAmount("PLN", "15.00"), "DBIT", "BOOK", recent.plusDays(1).toString(), creditor = EbParty("Pharmacy")),
        )
        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "FI"))
        }.body()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        client.sync(token, auth.connectionId)
        assertEquals(recent, bank.dateFroms.first())
        val all: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body()
        val bankRows = all.filter { it.bankAccountId != null }.map { it.amount }.toSet()
        assertEquals(setOf("-3.20", "-15.00"), bankRows)
        // The next sync's 7-day overlap re-fetches the boundary day: the imported row must still win.
        client.sync(token, auth.connectionId)
        val again2: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body()
        assertEquals(listOf("-15.00", "-3.20"), again2.filter { it.bankAccountId != null }.map { it.amount }.sorted())
    }

    @Test
    fun `merchant majority is decided over all batches, not the first one`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        fun batch(vararg rows: Pair<String, String>) = """{"source":"actual","transactions":[""" +
            rows.joinToString(",") { (id, cat) -> """{"externalId":"$id","date":"2025-01-01","amount":"-1.00","payee":"Lidl","category":"$cat"}""" } + "]}"
        client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(token); json(); setBody(batch("a" to "Restaurants & Cafes")) }
        client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(token); json(); setBody(batch("b" to "Groceries", "c" to "Groceries")) }
        val r: FinalizeImportResult = client.post("/api/v1/wallets/${wallet.id}/import/finalize?source=actual") { bearerAuth(token) }.body()
        assertEquals(1, r.merchantsLearned)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        val next = client.addTxn(token, wallet.id, "LIDL 77", "LIDL")
        assertEquals(categories.single { it.name == "Groceries" }.id, next.categoryId)
    }

    @Test
    fun `the catch-all unknown merchant is never learned or spread`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        llm.reply = """{"category_id": 0, "confidence": 0.1}"""
        val a = client.addTxn(token, wallet.id, "123 456 *999", null)
        val b = client.addTxn(token, wallet.id, "0000 1111", null)
        assertEquals("unknown", a.merchantKey)
        client.patch("/api/v1/transactions/${a.id}") { bearerAuth(token); json(); setBody(mapOf("categoryId" to categories.first { it.name == "Transfers" }.id)) }
        val txns: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body()
        assertTrue(txns.single { it.id == b.id }.categorySource != CategorySource.MEMORY)
        val c = client.addTxn(token, wallet.id, "999 888", null)
        assertTrue(c.categorySource != CategorySource.MEMORY)
    }

    @Test
    fun `bank rows left uncategorized by an llm outage are retried on the next sync`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        llm.reply = """{"category_id": ${categories.first { it.name == "Groceries" }.id}, "confidence": 0.95}"""
        llm.fail = true
        bank.pages = listOf(EbTransaction("R1", null, EbAmount("EUR", "20.00"), "DBIT", "BOOK", LocalDate.now().toString(), creditor = EbParty("Lidl")))
        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "FI"))
        }.body()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        client.sync(token, auth.connectionId)
        val before: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/review") { bearerAuth(token) }.body()
        assertEquals(TxnStatus.UNCATEGORIZED, before.single().status)

        llm.fail = false
        client.sync(token, auth.connectionId)
        val after: List<TransactionDto> = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body()
        assertEquals(TxnStatus.CATEGORIZED, after.single().status)
        assertEquals(CategorySource.LLM, after.single().categorySource)
    }

    private suspend fun HttpClient.txn(
        token: String, walletId: Long, date: String, amount: String, merchant: String,
        categoryId: Long? = null, currency: String? = null,
    ): TransactionDto =
        post("/api/v1/wallets/$walletId/transactions") {
            bearerAuth(token); json()
            setBody(CreateTransactionRequest(date, amount, currency, "$merchant CARD PAYMENT", merchant, categoryId))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }.body()

    private fun assertAmount(expected: String, actual: String) =
        assertEquals(0, expected.toBigDecimal().compareTo(actual.toBigDecimal()), "expected $expected, got $actual")

    @Test
    fun `transactions can be searched, filtered to uncategorized and sorted`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        llm.fail = true
        val coffee = client.txn(token, wallet.id, "2026-10-01", "-3.00", "Cafe 100% Arabica", categories.first { it.name == "Restaurants & Cafes" }.id)
        val market = client.txn(token, wallet.id, "2026-10-05", "-50.00", "K-Market Kamppi", categories.first { it.name == "Groceries" }.id)
        val salary = client.txn(token, wallet.id, "2026-10-25", "2000.00", "ACME Oy")
        suspend fun ids(params: String): List<Long> =
            client.get("/api/v1/wallets/${wallet.id}/transactions?$params") { bearerAuth(token) }.body<List<TransactionDto>>().map { it.id }

        assertEquals(listOf(market.id), ids("q=k-market"), "counterparty, case-insensitive")
        assertEquals(listOf(coffee.id), ids("q=card+payment&sort=DATE_ASC&limit=1"), "description")
        assertEquals(listOf(coffee.id), ids("q=%25"), "a % in the query is literal")
        assertEquals(emptyList(), ids("q=_"))
        assertEquals(listOf(salary.id), ids("category=none"))
        assertEquals(listOf(salary.id, market.id, coffee.id), ids("sort=DATE_DESC"))
        assertEquals(listOf(coffee.id, market.id, salary.id), ids("sort=date_asc"))
        assertEquals(listOf(market.id, coffee.id, salary.id), ids("sort=AMOUNT_ASC"))
        assertEquals(listOf(salary.id, coffee.id, market.id), ids("sort=AMOUNT_DESC"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/wallets/${wallet.id}/transactions?sort=size") { bearerAuth(token) }.status)
    }

    @Test
    fun `stats total each category and leave transfers and other currencies out`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        fun cat(name: String) = categories.first { it.name == name }.id
        llm.fail = true
        client.txn(token, wallet.id, "2026-10-03", "-40.00", "Lidl", cat("Groceries"))
        client.txn(token, wallet.id, "2026-10-04", "-60.00", "Prisma", cat("Groceries"))
        client.txn(token, wallet.id, "2026-10-06", "10.00", "Prisma Refund", cat("Groceries"))
        client.txn(token, wallet.id, "2026-10-07", "-25.00", "Pizzeria", cat("Restaurants & Cafes"))
        client.txn(token, wallet.id, "2026-10-25", "3000.00", "ACME Oy", cat("Salary"))
        client.txn(token, wallet.id, "2026-10-26", "-500.00", "Savings", cat("Transfers"))
        client.txn(token, wallet.id, "2026-10-12", "-15.00", "Mystery Shop")
        client.txn(token, wallet.id, "2026-10-13", "20.00", "Mystery Payer")
        client.txn(token, wallet.id, "2026-10-14", "-99.00", "Amazon US", cat("Groceries"), currency = "USD")
        client.txn(token, wallet.id, "2026-09-15", "-100.00", "Lidl September", cat("Groceries"))
        client.txn(token, wallet.id, "2026-11-01", "-7.00", "Lidl November", cat("Groceries"))

        val stats: StatsDto = client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-01&to=2026-10-31") { bearerAuth(token) }.body()
        assertEquals("EUR", stats.currency)
        assertEquals(listOf(cat("Groceries"), cat("Restaurants & Cafes"), null), stats.expenses.map { it.categoryId })
        assertAmount("-90.00", stats.expenses[0].total)
        assertEquals(3, stats.expenses[0].count)
        assertAmount("-15.00", stats.expenses[2].total)
        assertEquals(listOf(cat("Salary"), null), stats.income.map { it.categoryId })
        assertAmount("130.00", stats.totalExpenses)
        assertAmount("3020.00", stats.totalIncome)
        assertEquals(1, stats.otherCurrencyCount)

        assertEquals((5..10).map { "2026-%02d".format(it) }, stats.months.map { it.month })
        assertAmount("100.00", stats.months[4].expenses)
        assertAmount("0", stats.months[4].income)
        assertAmount("130.00", stats.months[5].expenses)
        assertAmount("3020.00", stats.months[5].income)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-31&to=2026-10-01") { bearerAuth(token) }.status)
    }

    @Test
    fun `bank details are stored, named by account and backfilled once`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        llm.fail = true
        val plain = EbTransaction("R1", null, EbAmount("EUR", "20.00"), "DBIT", "BOOK", "2026-10-03", creditor = EbParty("Lidl"))
        bank.pages = listOf(plain)
        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "FI"))
        }.body()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        client.sync(token, auth.connectionId)

        bank.pages = listOf(
            plain.copy(transactionDate = "2026-10-01", bankTransactionCode = EbBankTxnCode("Card payment")),
            EbTransaction(
                "R2", null, EbAmount("EUR", "1000.00"), "CRDT", "BOOK", "2026-10-02", valueDate = "2026-10-02",
                debtor = EbParty("ACME"), debtorAccount = EbAccountId(iban = "FI21 1234 5600 0007 85"),
                bankTransactionCode = EbBankTxnCode("SEPA credit transfer"),
            ),
        )
        client.sync(token, auth.connectionId)
        suspend fun byRef(): Map<String, TransactionDto> =
            client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>().associateBy { it.amount }

        val txns = byRef()
        val lidl = txns.getValue("-20.00")
        assertEquals("2026-10-01", lidl.transactionDate, "backfilled from the re-fetched row")
        assertEquals("Card payment", lidl.paymentType)
        val salary = txns.getValue("1000.00")
        assertEquals("FI2112345600000785", salary.counterpartyIban)
        assertEquals("2026-10-02", salary.valueDate)
        assertEquals("SEPA credit transfer", salary.paymentType)
        assertEquals("Main •••• 0785", salary.account)

        // Rows that already have details keep them.
        bank.pages = listOf(plain.copy(transactionDate = "2026-09-30", bankTransactionCode = EbBankTxnCode("Other")))
        client.sync(token, auth.connectionId)
        assertEquals("Card payment", byRef().getValue("-20.00").paymentType)
    }

    @Test
    fun `api errors are localized from Accept-Language`() = app { client ->
        val bad = mapOf("email" to "nobody@example.com", "password" to "wrong-password")
        suspend fun error(lang: String?): String =
            client.post("/api/v1/auth/login") {
                json(); setBody(bad)
                lang?.let { header(HttpHeaders.AcceptLanguage, it) }
            }.body<ErrorResponse>().error
        assertEquals("Invalid email or password", error(null))
        assertEquals("Invalid email or password", error("de-DE"))
        assertEquals("Невірна пошта або пароль", error("uk-UA,uk;q=0.9"))
        assertEquals("Nieprawidłowy e-mail lub hasło", error("en;q=0,pl"))
    }

    @Test
    fun `a malformed body answers with a localized generic message`() = app { client ->
        val r = client.post("/api/v1/auth/login") {
            json(); header(HttpHeaders.AcceptLanguage, "pl"); setBody("{ not json")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("Nieprawidłowe żądanie", r.body<ErrorResponse>().error)
    }

    @Test
    fun `rate-limit sync errors are shown in each reader's language`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val auth: BankAuthResponse = client.post("/api/v1/wallets/${wallet.id}/bank-connections") {
            bearerAuth(token); json(); setBody(mapOf("aspspName" to "Nordea", "country" to "fi"))
        }.body()
        client.get("/api/v1/banking/callback?state=${bank.lastAuth!!.state}&code=abc")
        // The callback starts a background sync; this helper waits until it has finished.
        client.sync(token, auth.connectionId)

        // A scheduled sync (no PSU context) hits the bank's daily limit and is paused until tomorrow.
        bank.unattendedLimitReached = true
        services.banking!!.syncAll()
        suspend fun lastError(lang: String?): String =
            client.get("/api/v1/bank-connections?wallet=${wallet.id}") {
                bearerAuth(token); lang?.let { header(HttpHeaders.AcceptLanguage, it) }
            }.body<List<BankConnectionDto>>().single().lastSyncError!!
        assertTrue(lastError(null).contains("daily limit"), lastError(null))
        assertTrue(lastError("uk").contains("денний ліміт"), lastError("uk"))
        assertTrue(lastError("pl").contains("dzienny limit"), lastError("pl"))

        // A user-present sync gets the other message, also localized. Each manual request is checked in
        // its own language; the latest run's persisted error is then read back in all three.
        bank.userLimitReached = true
        val manualEn = "The bank is refusing requests for now (rate limit); try again later."
        val manualUk = "Банк наразі відхиляє запити (ліміт); спробуйте пізніше."
        val manualPl = "Bank odrzuca teraz żądania (limit); spróbuj później."
        suspend fun manualError(lang: String?): String? =
            client.post("/api/v1/bank-connections/${auth.connectionId}/sync") {
                bearerAuth(token); lang?.let { header(HttpHeaders.AcceptLanguage, it) }
            }.body<SyncResultDto>().error
        assertEquals(manualEn, manualError(null))
        assertEquals(manualUk, manualError("uk"))
        assertEquals(manualPl, manualError("pl"))

        // The manual run is now the latest one, so its error key is rendered per reader too. Assert the
        // exact sentences rather than a substring: "limit" alone would also match the English fallback.
        assertEquals(manualEn, lastError(null))
        assertEquals(manualUk, lastError("uk"))
        assertEquals(manualPl, lastError("pl"))
    }

    // --- FX conversion ---

    @Test
    fun `transactions carry the amount converted into the wallet currency`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        seedRate(LocalDate.parse("2026-10-14"), "USD", "1.10")
        client.txn(token, wallet.id, "2026-10-14", "-99.00", "Amazon US", currency = "USD")
        client.txn(token, wallet.id, "2026-10-14", "-10.00", "Lidl")

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        val usd = rows.single { it.currency == "USD" }
        assertEquals("EUR", usd.baseCurrency)
        assertEquals("2026-10-14", usd.baseRateDate)
        assertAmount("-90.000000", usd.baseAmount!!)
        val eur = rows.single { it.currency == "EUR" }
        assertEquals("EUR", eur.baseCurrency)
        assertEquals(eur.amount, eur.baseAmount)
    }

    @Test
    fun `fx disabled leaves every converted field null`() = app(extraEnv = mapOf("FX_ENABLED" to "false")) { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        seedRate(LocalDate.parse("2026-10-14"), "USD", "1.10")
        client.txn(token, wallet.id, "2026-10-14", "-99.00", "Amazon US", currency = "USD")
        client.txn(token, wallet.id, "2026-10-14", "-10.00", "Lidl")

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        assertEquals(2, rows.size)
        for (row in rows) {
            assertNull(row.baseAmount)
            assertNull(row.baseCurrency)
            assertNull(row.baseRateDate)
        }
    }

    @Test
    fun `a day with no published rate falls back to the previous business day`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        seedRate(LocalDate.parse("2026-10-09"), "USD", "1.10")
        client.txn(token, wallet.id, "2026-10-11", "-50.00", "Amazon US", currency = "USD")

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        val usd = rows.single { it.currency == "USD" }
        assertEquals("2026-10-09", usd.baseRateDate)
        assertAmount("-45.454545", usd.baseAmount!!)
    }

    @Test
    fun `eur transactions convert into the PLN wallet currency at the publication date`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token, "PLN")
        fxProvider.days = listOf(FxDay(LocalDate.parse("2026-10-09"), "EUR", mapOf("PLN" to BigDecimal("4.30"))))
        client.txn(token, wallet.id, "2026-10-09", "-10.00", "Lidl Europe", currency = "EUR")
        client.txn(token, wallet.id, "2026-10-11", "-20.00", "Weekend Shop", currency = "EUR")
        client.txn(token, wallet.id, "2026-10-09", "-5.00", "Lidl Polska")

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        val friday = rows.single { it.currency == "EUR" && it.amount == "-10.00" }
        assertEquals("-10.00", friday.amount, "the original EUR amount is preserved")
        assertEquals("PLN", friday.baseCurrency)
        assertAmount("-43.000000", friday.baseAmount!!)
        assertEquals("2026-10-09", friday.baseRateDate)

        val weekend = rows.single { it.currency == "EUR" && it.amount == "-20.00" }
        assertEquals("PLN", weekend.baseCurrency)
        assertAmount("-86.000000", weekend.baseAmount!!)
        assertEquals("2026-10-09", weekend.baseRateDate, "a weekend booking uses Friday's publication")

        val native = rows.single { it.currency == "PLN" }
        assertEquals("PLN", native.baseCurrency)
        assertEquals(native.amount, native.baseAmount)
        assertEquals(1, fxProvider.calls.size, "the transaction list fetches coverage once")
    }

    @Test
    fun `a currency with no rate at all stays unconverted`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        client.txn(token, wallet.id, "2026-10-14", "-50.00", "Kyiv Shop", currency = "UAH")

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        val uah = rows.single { it.currency == "UAH" }
        assertNull(uah.baseAmount)
        assertEquals("EUR", uah.baseCurrency)
        val stats: StatsDto = client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-01&to=2026-10-31") { bearerAuth(token) }.body()
        assertAmount("0", stats.totalExpenses)
        assertEquals(1, stats.otherCurrencyCount)
    }

    @Test
    fun `an unpaired foreign-currency transfer is not counted as other currency`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val categories: List<CategoryDto> = client.get("/api/v1/wallets/${wallet.id}/categories") { bearerAuth(token) }.body()
        fun cat(name: String) = categories.first { it.name == name }.id
        client.txn(token, wallet.id, "2026-10-14", "-50.00", "Mystery", cat("Transfers"), currency = "USD")
        client.txn(token, wallet.id, "2026-10-14", "-40.00", "Amazon", cat("Groceries"), currency = "USD")

        val stats: StatsDto = client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-01&to=2026-10-31") { bearerAuth(token) }.body()
        assertEquals(1, stats.otherCurrencyCount, "the TRANSFER-kind row is not an other-currency expense")
    }

    // --- internal transfers ---

    @Test
    fun `internal transfers between own accounts are paired and excluded`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        llm.fail = true
        bank.accounts = listOf(
            EbAccount("uid-A", EbAccountId(iban = "FI1111111111111111"), "hash-A", "Main", "EUR"),
            EbAccount("uid-B", EbAccountId(iban = "FI9999999999999999"), "hash-B", "Savings", "EUR"),
        )
        bank.pagesByAccount = mapOf(
            "uid-A" to listOf(
                EbTransaction(
                    "OUT", null, EbAmount("EUR", "200.00"), "DBIT", "BOOK", "2026-10-01",
                    creditor = EbParty("Savings"), creditorAccount = EbAccountId(iban = "FI0000000000009999"),
                ),
            ),
            "uid-B" to listOf(
                EbTransaction(
                    "IN", null, EbAmount("EUR", "200.00"), "CRDT", "BOOK", "2026-10-03",
                    debtor = EbParty("Main"), debtorAccount = EbAccountId(iban = "FI0000000000001111"),
                ),
            ),
        )
        client.connectAndSync(token, wallet.id)

        suspend fun rows() = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }
            .body<List<TransactionDto>>()
        val first = rows()
        val out = first.single { it.amount == "-200.00" }
        val incoming = first.single { it.amount == "200.00" }
        assertNotNull(out.transferGroup)
        assertEquals(out.transferGroup, incoming.transferGroup)

        val stats: StatsDto = client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-01&to=2026-10-31") { bearerAuth(token) }.body()
        assertAmount("0", stats.totalExpenses)
        assertAmount("0", stats.totalIncome)
        assertEquals(0, stats.otherCurrencyCount)

        // Idempotent: a second scan leaves the same two ids grouped and flags no third row.
        services.transfers.scan(wallet.id, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"))
        val second = rows()
        assertEquals(setOf(out.id, incoming.id), second.filter { it.transferGroup != null }.map { it.id }.toSet())
        assertEquals(groupOf(out.id), groupOf(incoming.id))
    }

    @Test
    fun `an in-app currency exchange is paired across currencies`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        llm.fail = true
        val date = "2026-10-06"
        fxProvider.days = listOf(FxDay(LocalDate.parse(date), "EUR", mapOf("USD" to BigDecimal("1.08"))))
        bank.pages = listOf(
            EbTransaction("X1", null, EbAmount("EUR", "100.00"), "DBIT", "BOOK", date, creditor = EbParty("Exchange")),
            EbTransaction("X2", null, EbAmount("USD", "108.00"), "CRDT", "BOOK", date, debtor = EbParty("Exchange")),
        )
        client.connectAndSync(token, wallet.id)

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        val out = rows.single { it.currency == "EUR" }
        val incoming = rows.single { it.currency == "USD" }
        assertNotNull(out.transferGroup)
        assertEquals(out.transferGroup, incoming.transferGroup)
    }

    @Test
    fun `unrelated same-amount transactions in different accounts stay in the totals`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        llm.fail = true
        bank.accounts = listOf(
            EbAccount("uid-A", EbAccountId(iban = "FI1111111111111111"), "hash-A", "Main", "EUR"),
            EbAccount("uid-B", EbAccountId(iban = "FI9999999999999999"), "hash-B", "Savings", "EUR"),
        )
        bank.pagesByAccount = mapOf(
            "uid-A" to listOf(
                EbTransaction("OUT", null, EbAmount("EUR", "200.00"), "DBIT", "BOOK", "2026-10-01", creditor = EbParty("Landlord")),
            ),
            "uid-B" to listOf(
                EbTransaction("IN", null, EbAmount("EUR", "200.00"), "CRDT", "BOOK", "2026-10-02", debtor = EbParty("ACME")),
            ),
        )
        client.connectAndSync(token, wallet.id)

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.transferGroup == null })
        val stats: StatsDto = client.get("/api/v1/wallets/${wallet.id}/stats?from=2026-10-01&to=2026-10-31") { bearerAuth(token) }.body()
        assertAmount("200.00", stats.totalExpenses)
        assertAmount("200.00", stats.totalIncome)
    }

    @Test
    fun `a manually added transaction is never paired`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val day = LocalDate.parse("2026-10-10")
        client.txn(token, wallet.id, day.toString(), "-100.00", "transfer to savings")
        client.txn(token, wallet.id, day.toString(), "100.00", "transfer from main")
        services.transfers.scan(wallet.id, day, day)

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        assertTrue(rows.all { it.transferGroup == null })
    }

    @Test
    fun `a manual non-transfer category releases both legs of a transfer`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val outId = seedRow(wallet.id, "2026-10-10", "-100", account = "Main", description = "transfer out", group = "G")
        val inId = seedRow(wallet.id, "2026-10-12", "100", account = "Savings", description = "transfer in", group = "G")
        val groceries = client.groceries(token, wallet.id)

        val patched: TransactionDto = client.patch("/api/v1/transactions/$outId") {
            bearerAuth(token); json(); setBody(mapOf("categoryId" to groceries))
        }.body()
        assertNull(patched.transferGroup)
        assertNull(groupOf(outId))
        assertNull(groupOf(inId))
        assertTrue(overrideOf(outId))

        services.transfers.scan(wallet.id, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"))
        assertNull(groupOf(outId), "a scan must not re-flag a row the user released")
        assertNull(groupOf(inId))
    }

    @Test
    fun `an imported category does not suppress transfer detection`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val body = """
            {"source":"actual",
             "categories":[{"name":"Groceries","kind":"EXPENSE"}],
             "transactions":[
               {"externalId":"o1","date":"2025-01-10","amount":"-100.00","payee":"Own","notes":"transfer to savings","category":"Groceries","account":"Main"},
               {"externalId":"i1","date":"2025-01-11","amount":"100.00","payee":"Own","notes":"transfer from main","category":"Groceries","account":"Savings"}
             ]}
        """.trimIndent()
        client.post("/api/v1/wallets/${wallet.id}/import") { bearerAuth(token); json(); setBody(body) }
        client.post("/api/v1/wallets/${wallet.id}/import/finalize?source=actual") { bearerAuth(token) }

        val rows = client.get("/api/v1/wallets/${wallet.id}/transactions") { bearerAuth(token) }.body<List<TransactionDto>>()
        assertEquals(2, rows.size)
        assertNotNull(rows[0].transferGroup)
        assertEquals(rows[0].transferGroup, rows[1].transferGroup)
        for (row in rows) assertTrue(!overrideOf(row.id), "an importer-supplied category must not become a user veto")
    }

    @Test
    fun `a concurrent scan does not re-flag a released transfer`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val groceries = client.groceries(token, wallet.id)
        repeat(20) { i ->
            val day = LocalDate.parse("2025-01-01").plusDays(i * 10L)
            val outId = seedRow(wallet.id, day.toString(), "-100", account = "Main", description = "transfer out", group = "G$i")
            val inId = seedRow(wallet.id, day.plusDays(2).toString(), "100", account = "Savings", description = "transfer in", group = "G$i")
            coroutineScope {
                val scan = launch(Dispatchers.Default) {
                    services.transfers.scan(wallet.id, day, day.plusDays(2))
                }
                val patch = launch(Dispatchers.Default) {
                    client.patch("/api/v1/transactions/$outId") {
                        bearerAuth(token); json(); setBody(mapOf("categoryId" to groceries))
                    }.body<TransactionDto>()
                }
                joinAll(scan, patch)
            }
            assertNull(groupOf(outId), "iteration $i: the patched row was re-flagged")
            assertNull(groupOf(inId), "iteration $i: the released partner stayed grouped")
            assertTrue(overrideOf(outId), "iteration $i: the user's override was lost")
        }
    }

    @Test
    fun `a scan window that touches one leg clears or rebuilds the whole group`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val outId = seedRow(wallet.id, "2026-10-01", "-100", account = "Main", description = "transfer out")
        val inId = seedRow(wallet.id, "2026-10-03", "100", account = "Savings", description = "transfer in")

        services.transfers.scan(wallet.id, LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-03"))
        val group = groupOf(outId)
        assertNotNull(group)
        assertEquals(group, groupOf(inId))

        dbQuery { Transactions.deleteWhere { Transactions.id eq outId } }
        services.transfers.scan(wallet.id, LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-03"))
        assertNull(groupOf(inId))
    }

    @Test
    fun `a boundary group dragged in by widening is rebuilt whole`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val base = LocalDate.parse("2025-02-01")
        val bOut = seedRow(wallet.id, base.plusDays(-2).toString(), "-100", account = "Main", description = "transfer b out", group = "B")
        val bIn = seedRow(wallet.id, base.plusDays(1).toString(), "100", account = "Savings", description = "transfer b in", group = "B")
        val aOut = seedRow(wallet.id, base.plusDays(2).toString(), "-100", account = "Main", description = "transfer a out", group = "A")
        val aIn = seedRow(wallet.id, base.plusDays(5).toString(), "100", account = "Savings", description = "transfer a in", group = "A")

        services.transfers.scan(wallet.id, base.plusDays(3), base.plusDays(3))

        val groupA = groupOf(aOut)
        assertNotNull(groupA)
        assertEquals(groupA, groupOf(aIn))
        val groupB = groupOf(bOut)
        assertNotNull(groupB)
        assertEquals(groupB, groupOf(bIn))
    }

    @Test
    fun `a scan that fetches new rates does not deadlock`() = app { client ->
        val token = client.register("anna@example.com").token
        val wallet = client.createWallet(token)
        val day = LocalDate.parse("2026-10-05")
        val outId = seedRow(wallet.id, day.toString(), "-100", account = "Main", description = "exchange")
        val inId = seedRow(wallet.id, day.toString(), "108", account = "Savings", description = "exchange", currency = "USD")
        fxProvider.days = listOf(FxDay(day, "EUR", mapOf("USD" to BigDecimal("1.08"))))

        withTimeout(10_000) { services.transfers.scan(wallet.id, day, day) }
        val group = groupOf(outId)
        assertNotNull(group)
        assertEquals(group, groupOf(inId))
    }

    @Test
    fun `a cross-currency pair is formed once rates arrive`() {
        val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))
        app(clock = clock) { client ->
            val token = client.register("anna@example.com").token
            val wallet = client.createWallet(token)
            val day = LocalDate.parse("2026-11-13")
            val outId = seedRow(wallet.id, day.toString(), "-100", account = "Main", description = "exchange")
            val inId = seedRow(wallet.id, day.toString(), "108", account = "Savings", description = "exchange", currency = "USD")

            services.transfers.scan(wallet.id, day, day)
            assertNull(groupOf(outId), "without a rate the pair must stay visible")

            fxProvider.days = listOf(FxDay(day, "EUR", mapOf("USD" to BigDecimal("1.08"))))
            clock.now = clock.now.plusSeconds(12 * 3600 + 1)
            services.fx.ensureCovered(day, day, notify = true)
            awaitGrouped(outId, inId)
        }
    }

    @Test
    fun `a weekend pair is formed once Friday's rate arrives`() {
        val clock = MutableClock(Instant.parse("2026-10-10T00:00:00Z"))
        app(clock = clock) { client ->
            val token = client.register("anna@example.com").token
            val wallet = client.createWallet(token)
            val saturday = LocalDate.parse("2026-10-10")
            val sunday = saturday.plusDays(1)
            val friday = saturday.minusDays(1)
            val outId = seedRow(wallet.id, saturday.toString(), "-100", account = "Main", description = "exchange")
            val inId = seedRow(wallet.id, sunday.toString(), "108", account = "Savings", description = "exchange", currency = "USD")

            services.transfers.scan(wallet.id, saturday, sunday)
            assertNull(groupOf(outId), "without a rate the pair must stay visible")

            fxProvider.days = listOf(FxDay(friday, "EUR", mapOf("USD" to BigDecimal("1.08"))))
            clock.now = clock.now.plusSeconds(12 * 3600 + 1)
            services.fx.ensureCovered(friday, sunday, notify = true)
            awaitGrouped(outId, inId)
        }
    }

    @Test
    fun `a scan that fetches rates rescans other wallets`() = app { client ->
        val token = client.register("anna@example.com").token
        val walletA = client.createWallet(token)
        val walletB = client.createWallet(token)
        val day = LocalDate.parse("2026-10-05")
        val aOut = seedRow(walletA.id, day.toString(), "-100", account = "Main", description = "exchange")
        val aIn = seedRow(walletA.id, day.toString(), "108", account = "Savings", description = "exchange", currency = "USD")
        val bOut = seedRow(walletB.id, day.toString(), "-100", account = "Main", description = "exchange")
        val bIn = seedRow(walletB.id, day.toString(), "108", account = "Savings", description = "exchange", currency = "USD")
        fxProvider.days = listOf(FxDay(day, "EUR", mapOf("USD" to BigDecimal("1.08"))))

        services.transfers.scan(walletA.id, day, day)
        awaitGrouped(bOut, bIn)

        val groupA = groupOf(aOut)
        assertNotNull(groupA)
        assertEquals(groupA, groupOf(aIn))
        assertEquals(1, fxProvider.calls.size, "the propagation round must not fetch the same dates again")
    }
}
