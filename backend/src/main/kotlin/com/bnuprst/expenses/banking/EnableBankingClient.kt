package com.bnuprst.expenses.banking

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.bnuprst.expenses.config.EnableBankingConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.File
import java.io.StringReader
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Date

/** Abstraction over the Enable Banking API so the banking service can be tested without network. */
interface EnableBankingApi {
    suspend fun aspsps(country: String?): List<EbAspsp>
    suspend fun startAuth(request: EbAuthRequest): EbAuthResponse
    /** Exchanges the redirect `code` for a session; accounts are always returned fully populated. */
    suspend fun createSession(code: String): Pair<EbSessionResponse, List<EbAccount>>
    /** [psu] marks the request as made while the user is present; banks don't count those against the PSD2 daily limit. */
    suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?, psu: PsuContext? = null): EbTransactionsPage
}

class EnableBankingClient(
    private val http: HttpClient,
    private val config: EnableBankingConfig,
    private val signer: EnableBankingJwtSigner = EnableBankingJwtSigner(config.appId, loadPrivateKey(File(config.privateKeyPath).readText())),
) : EnableBankingApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    override suspend fun aspsps(country: String?): List<EbAspsp> =
        call<EbAspspList>(http.get("${config.apiBase}/aspsps") { auth(); country?.let { parameter("country", it) } }).aspsps

    override suspend fun startAuth(request: EbAuthRequest): EbAuthResponse =
        call(http.post("${config.apiBase}/auth") { auth(); jsonBody(json.encodeToString(EbAuthRequest.serializer(), request)) })

    override suspend fun createSession(code: String): Pair<EbSessionResponse, List<EbAccount>> {
        val session = call<EbSessionResponse>(http.post("${config.apiBase}/sessions") {
            auth(); jsonBody(json.encodeToString(EbSessionRequest.serializer(), EbSessionRequest(code)))
        })
        val accounts = session.accounts.map { el ->
            if (el is JsonPrimitive) call<EbAccount>(http.get("${config.apiBase}/accounts/${el.content}/details") { auth() })
                .let { if (it.uid.isBlank()) it.copy(uid = el.content) else it }
            else json.decodeFromJsonElement<EbAccount>(el)
        }
        return session to accounts
    }

    override suspend fun transactions(accountUid: String, dateFrom: LocalDate, continuationKey: String?, psu: PsuContext?): EbTransactionsPage =
        call(http.get("${config.apiBase}/accounts/$accountUid/transactions") {
            auth()
            psu?.let {
                header("Psu-Ip-Address", it.ipAddress)
                it.userAgent?.let { ua -> header("Psu-User-Agent", ua) }
            }
            parameter("date_from", dateFrom.toString())
            continuationKey?.let { parameter("continuation_key", it) }
        })

    private fun HttpRequestBuilder.auth() = bearerAuth(signer.token())

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> call(response: HttpResponse): T {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw EnableBankingException(response.status.value, text)
        return json.decodeFromString(text)
    }

    companion object {
        /** Accepts PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`) PEM. */
        fun loadPrivateKey(pem: String): RSAPrivateKey {
            val converter = JcaPEMKeyConverter()
            val key = when (val obj = PEMParser(StringReader(pem)).readObject()) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is PrivateKeyInfo -> converter.getPrivateKey(obj)
                else -> error("Unsupported private key format: ${obj?.javaClass?.simpleName}")
            }
            return key as? RSAPrivateKey ?: error("Enable Banking requires an RSA private key")
        }
    }
}

/** Application JWT: RS256, kid = application id, iss/aud fixed by Enable Banking, max lifetime 24h. */
class EnableBankingJwtSigner(
    private val appId: String,
    privateKey: RSAPrivateKey,
    private val lifetime: Duration = Duration.ofHours(1),
    private val clock: () -> Instant = Instant::now,
) {
    private val algorithm = Algorithm.RSA256(null, privateKey)
    @Volatile private var cached: Pair<String, Instant>? = null

    fun token(): String {
        val now = clock()
        cached?.let { (token, exp) -> if (exp.isAfter(now.plusSeconds(300))) return token }
        val exp = now.plus(lifetime)
        val token = JWT.create()
            .withKeyId(appId)
            .withHeader(mapOf("typ" to "JWT"))
            .withIssuer("enablebanking.com")
            .withAudience("api.enablebanking.com")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(exp))
            .sign(algorithm)
        cached = token to exp
        return token
    }
}
