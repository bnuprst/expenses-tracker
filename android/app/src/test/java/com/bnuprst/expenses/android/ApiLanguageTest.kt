package com.bnuprst.expenses.android

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiLanguageTest {
    private fun api(tag: String, status: HttpStatusCode, body: String): Pair<Api, MockEngine> {
        val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        return Api({ "https://example.test" }, { "t" }, { tag }, engine) to engine
    }

    @Test
    fun `every request carries the chosen language`() = runBlocking {
        val (api, engine) = api("uk", HttpStatusCode.OK, "[]")
        api.wallets()
        assertEquals("uk", engine.requestHistory.single().headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun `a 401 is recognised by status in every language`() = runBlocking {
        for ((tag, text) in listOf("en" to "Unauthorized", "uk" to "Не авторизовано", "pl" to "Brak autoryzacji")) {
            val (api, _) = api(tag, HttpStatusCode.Unauthorized, """{"error":"$text"}""")
            val e = runCatching { api.wallets() }.exceptionOrNull()
            assertTrue("$tag: $e", e is ApiException)
            assertEquals(401, (e as ApiException).status)
            assertEquals(text, e.message)
        }
    }
}
