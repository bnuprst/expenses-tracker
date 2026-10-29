package com.bnuprst.expenses.android

import com.bnuprst.expenses.shared.AddMemberRequest
import com.bnuprst.expenses.shared.AspspDto
import com.bnuprst.expenses.shared.AuthResponse
import com.bnuprst.expenses.shared.BankAuthResponse
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CreateBankConnectionRequest
import com.bnuprst.expenses.shared.CreateWalletRequest
import com.bnuprst.expenses.shared.ErrorResponse
import com.bnuprst.expenses.shared.LoginRequest
import com.bnuprst.expenses.shared.MemberDto
import com.bnuprst.expenses.shared.RegisterRequest
import com.bnuprst.expenses.shared.SyncResultDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.UpdateTransactionRequest
import com.bnuprst.expenses.shared.WalletDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiException(message: String) : Exception(message)

class Api(private val baseUrl: () -> String, private val token: () -> String?) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout)
    }

    private fun url(path: String) = baseUrl().trimEnd('/') + "/api/v1" + path

    private fun HttpRequestBuilder.authed() {
        token()?.let { bearerAuth(it) }
    }

    private inline fun <reified T : Any> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> HttpResponse.ok(): T {
        if (!status.isSuccess()) {
            val text = bodyAsText()
            val msg = runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull()
            throw ApiException(msg ?: "HTTP ${status.value}")
        }
        return body()
    }

    suspend fun login(email: String, password: String): AuthResponse =
        http.post(url("/auth/login")) { jsonBody(LoginRequest(email, password)) }.ok()

    suspend fun register(email: String, password: String, name: String): AuthResponse =
        http.post(url("/auth/register")) { jsonBody(RegisterRequest(email, password, name)) }.ok()

    suspend fun wallets(): List<WalletDto> = http.get(url("/wallets")) { authed() }.ok()

    suspend fun createWallet(name: String, currency: String): WalletDto =
        http.post(url("/wallets")) { authed(); jsonBody(CreateWalletRequest(name, currency)) }.ok()

    suspend fun addMember(walletId: Long, email: String): MemberDto =
        http.post(url("/wallets/$walletId/members")) { authed(); jsonBody(AddMemberRequest(email)) }.ok()

    suspend fun categories(walletId: Long): List<CategoryDto> =
        http.get(url("/wallets/$walletId/categories")) { authed() }.ok()

    suspend fun transactions(walletId: Long): List<TransactionDto> =
        http.get(url("/wallets/$walletId/transactions")) { authed(); parameter("limit", 300) }.ok()

    suspend fun review(walletId: Long): List<TransactionDto> =
        http.get(url("/wallets/$walletId/review")) { authed() }.ok()

    suspend fun setCategory(txnId: Long, categoryId: Long): TransactionDto =
        http.patch(url("/transactions/$txnId")) { authed(); jsonBody(UpdateTransactionRequest(categoryId)) }.ok()

    suspend fun aspsps(country: String): List<AspspDto> =
        http.get(url("/banking/aspsps")) { authed(); parameter("country", country) }.ok()

    suspend fun connectBank(walletId: Long, aspspName: String, country: String): BankAuthResponse =
        http.post(url("/wallets/$walletId/bank-connections")) { authed(); jsonBody(CreateBankConnectionRequest(aspspName, country)) }.ok()

    suspend fun connections(walletId: Long): List<BankConnectionDto> =
        http.get(url("/bank-connections")) { authed(); parameter("wallet", walletId) }.ok()

    suspend fun sync(connectionId: Long): SyncResultDto =
        http.post(url("/bank-connections/$connectionId/sync")) {
            authed()
            // The server answers only after fetching from the bank and categorizing every new transaction.
            timeout { requestTimeoutMillis = SYNC_TIMEOUT_MS; socketTimeoutMillis = SYNC_TIMEOUT_MS }
        }.ok()

    suspend fun reauthorize(connectionId: Long): BankAuthResponse =
        http.post(url("/bank-connections/$connectionId/reauthorize")) { authed() }.ok()
}

private const val SYNC_TIMEOUT_MS = 5 * 60_000L
