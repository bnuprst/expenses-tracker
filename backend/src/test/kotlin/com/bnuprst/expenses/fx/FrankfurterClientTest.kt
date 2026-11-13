package com.bnuprst.expenses.fx

import com.bnuprst.expenses.config.FxConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrankfurterClientTest {
    private val config = FxConfig(
        enabled = true,
        apiBase = "https://fx.test/v1",
        base = "EUR",
        refreshInterval = Duration.ofHours(12),
        maxStaleDays = 7,
        backfillDays = 400,
        provisionalDays = 5,
    )

    private fun client(handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): FrankfurterClient {
        val engine = MockEngine { request -> handle(request) }
        return FrankfurterClient(HttpClient(engine), config)
    }

    @Test
    fun `parses a two day canned body into exact decimals`() = runTest {
        val body = """
            {"base":"EUR","rates":{
              "2024-01-02":{"USD":"1.0955","PLN":"4.35"},
              "2024-01-03":{"USD":"1.0960","PLN":"4.34"}
            }}
        """.trimIndent()
        val fx = client {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val days = fx.rates(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"), "EUR")
        assertEquals(2, days.size)
        assertEquals(LocalDate.parse("2024-01-02"), days[0].asOf)
        assertEquals("EUR", days[0].base)
        assertEquals(BigDecimal("1.0955"), days[0].quotes["USD"])
        assertEquals(BigDecimal("4.35"), days[0].quotes["PLN"])
        assertEquals(BigDecimal("1.0960"), days[1].quotes["USD"])
    }

    @Test
    fun `a non-2xx answer throws FxUnavailable`() = runTest {
        val fx = client { respond("try later", HttpStatusCode.ServiceUnavailable) }
        assertFailsWith<FxUnavailable> {
            fx.rates(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"), "EUR")
        }
    }

    @Test
    fun `a malformed rate value throws FxUnavailable instead of being dropped`() = runTest {
        val body = """
            {"base":"EUR","rates":{"2024-01-02":{"USD":"invalid","PLN":"4.35"}}}
        """.trimIndent()
        val fx = client {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertFailsWith<FxUnavailable> {
            fx.rates(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"), "EUR")
        }
    }

    @Test
    fun `a malformed date throws FxUnavailable instead of being dropped`() = runTest {
        val body = """
            {"base":"EUR","rates":{"not-a-date":{"USD":"1.0955"}}}
        """.trimIndent()
        val fx = client {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertFailsWith<FxUnavailable> {
            fx.rates(LocalDate.parse("2024-01-02"), LocalDate.parse("2024-01-03"), "EUR")
        }
    }

    @Test
    fun `an empty rates map is an empty result, not an error`() = runTest {
        val fx = client {
            respond("""{"base":"EUR","rates":{}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val days = fx.rates(LocalDate.parse("2024-01-06"), LocalDate.parse("2024-01-07"), "EUR")
        assertTrue(days.isEmpty())
    }
}
