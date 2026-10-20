package com.bnuprst.expenses

import com.bnuprst.expenses.auth.AuthService
import com.bnuprst.expenses.auth.JwtService
import com.bnuprst.expenses.banking.BankingService
import com.bnuprst.expenses.banking.EnableBankingClient
import com.bnuprst.expenses.categorization.Categorizer
import com.bnuprst.expenses.categorization.DbCategorizationStore
import com.bnuprst.expenses.categorization.llm.LlmCategorizer
import com.bnuprst.expenses.categorization.llm.LlmProvider
import com.bnuprst.expenses.categorization.llm.OpenAiCompatibleProvider
import com.bnuprst.expenses.config.AppConfig
import com.bnuprst.expenses.db.Db
import com.bnuprst.expenses.shared.ErrorResponse
import com.bnuprst.expenses.transactions.TransactionService
import com.bnuprst.expenses.wallets.WalletService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

class Services(
    val jwt: JwtService,
    val auth: AuthService,
    val wallets: WalletService,
    val transactions: TransactionService,
    val banking: BankingService?,
)

fun buildServices(config: AppConfig, llmProvider: LlmProvider? = null, bankingApi: com.bnuprst.expenses.banking.EnableBankingApi? = null): Services {
    val jwt = JwtService(config.jwt)
    val wallets = WalletService()
    val provider = llmProvider ?: if (config.llm.enabled) {
        OpenAiCompatibleProvider(HttpClient(CIO) { install(HttpTimeout) }, config.llm)
    } else null
    val categorizer = Categorizer(DbCategorizationStore(), provider?.let(::LlmCategorizer), config.llm.confidenceThreshold)
    val transactions = TransactionService(wallets, categorizer)
    val banking = config.enableBanking?.let { eb ->
        val api = bankingApi ?: EnableBankingClient(HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 60_000 } }, eb)
        BankingService(api, eb, wallets, transactions)
    }
    return Services(jwt, AuthService(jwt), wallets, transactions, banking)
}

fun main() {
    val config = AppConfig.fromEnv()
    Db.connect(config.db)
    val services = buildServices(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(services)
        startSyncScheduler(config, services)
    }.start(wait = true)
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun Application.module(services: Services) {
    val log = LoggerFactory.getLogger("Application")
    install(ContentNegotiation) { json(json) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.message ?: "Error")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request")) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }
    install(Authentication) {
        jwt("user") {
            verifier(services.jwt.verifier)
            validate { cred -> cred.payload.subject?.toLongOrNull()?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ -> throw Unauthorized() }
        }
    }
    routes(services)
}

private fun Application.startSyncScheduler(config: AppConfig, services: Services) {
    val banking = services.banking ?: return
    val interval = config.enableBanking!!.syncInterval
    val log = LoggerFactory.getLogger("SyncScheduler")
    val job = launch {
        delay(30_000) // let the server come up first
        while (isActive) {
            log.info("Scheduled bank sync starting")
            runCatching { banking.syncAll() }.onFailure { log.error("Scheduled sync failed", it) }
            delay(interval.toMillis())
        }
    }
    monitor.subscribe(ApplicationStopping) { job.cancel() }
}
