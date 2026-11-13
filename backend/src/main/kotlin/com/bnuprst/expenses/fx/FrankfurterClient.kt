package com.bnuprst.expenses.fx

import com.bnuprst.expenses.config.FxConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

@Serializable
internal data class FrankfurterRange(
    val base: String = "",
    val rates: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * Daily ECB reference rates through Frankfurter (https://frankfurter.dev). ECB publishes on business
 * days only; an empty `rates` map is a valid answer meaning "nothing published in that range".
 */
class FrankfurterClient(private val http: HttpClient, private val config: FxConfig) : FxProvider {
    override val source = "frankfurter-ecb"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun rates(from: LocalDate, to: LocalDate, base: String): List<FxDay> {
        val url = "${config.apiBase}/$from..$to?base=$base"
        val response = try {
            http.get(url)
        } catch (e: Exception) {
            throw FxUnavailable("FX request to $url failed", e)
        }
        if (!response.status.isSuccess()) {
            throw FxUnavailable("FX provider answered ${response.status} for $url")
        }
        val body = try {
            json.decodeFromString<FrankfurterRange>(response.bodyAsText())
        } catch (e: Exception) {
            throw FxUnavailable("FX provider sent an unparseable body for $url", e)
        }
        val providerBase = body.base.ifBlank { base }
        // A malformed date or rate is a provider contract violation, not "no rate for that day".
        // Silently dropping it would let FxService mark the day published with the rate missing,
        // and the day would never be retried. Reject the whole response instead (no coverage rows).
        return body.rates.map { (date, quotes) ->
            val asOf = runCatching { LocalDate.parse(date) }.getOrElse { e ->
                throw FxUnavailable("FX provider sent an invalid rate date '$date' for $url", e)
            }
            val values = quotes.map { (currency, value) ->
                val rate = value.toBigDecimalOrNull() ?: throw FxUnavailable(
                    "FX provider sent an unparseable rate '$value' for $currency on $asOf for $url",
                )
                currency.uppercase() to rate
            }.toMap()
            FxDay(asOf, providerBase, values)
        }.sortedBy { it.asOf }
    }
}
