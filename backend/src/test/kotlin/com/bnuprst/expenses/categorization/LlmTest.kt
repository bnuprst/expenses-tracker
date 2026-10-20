package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.categorization.llm.LlmCategorizer
import com.bnuprst.expenses.categorization.llm.LlmSuggestion
import com.bnuprst.expenses.categorization.llm.OpenAiCompatibleProvider
import com.bnuprst.expenses.config.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LlmTest {
    private val config = LlmConfig(true, "http://llm.local/v1", "sk-test", "test-model", 0.7, Duration.ofSeconds(5))

    @Test
    fun `parses tolerant json`() {
        assertEquals(LlmSuggestion(3, 0.8), LlmCategorizer.parse("""```json
            {"category_id": "3", "confidence": 0.8}
            ```""", setOf(3)))
        assertEquals(LlmSuggestion(3, 1.0), LlmCategorizer.parse("""{"category_id": 3, "confidence": 7}""", setOf(3)))
        assertNull(LlmCategorizer.parse("not json", setOf(3)))
    }

    @Test
    fun `openai compatible request and response`() = runTest {
        var seenAuth: String? = null
        var seenBody = ""
        var seenUrl = ""
        val engine = MockEngine { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            seenUrl = req.url.toString()
            seenBody = (req.body as TextContent).text
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"{\"category_id\":1,\"confidence\":0.9}"}}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = OpenAiCompatibleProvider(HttpClient(engine) { install(HttpTimeout) }, config)
        assertEquals("""{"category_id":1,"confidence":0.9}""", provider.completeJson("sys", "usr"))
        assertEquals("Bearer sk-test", seenAuth)
        assertEquals("http://llm.local/v1/chat/completions", seenUrl)
        val body = Json.parseToJsonElement(seenBody).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(body["temperature"], "reasoning models reject non-default temperature; only send when configured")
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `optional sampling parameters are sent when configured`() = runTest {
        var seenBody = ""
        val engine = MockEngine { req ->
            seenBody = (req.body as TextContent).text
            respond("""{"choices":[{"message":{"content":"{}"}}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val provider = OpenAiCompatibleProvider(HttpClient(engine) { install(HttpTimeout) }, config.copy(temperature = 0.0, reasoningEffort = "low"))
        provider.completeJson("s", "u")
        val body = Json.parseToJsonElement(seenBody).jsonObject
        assertEquals("0.0", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no auth header without key and errors surface`() = runTest {
        var seenAuth: String? = "unset"
        val engine = MockEngine { req ->
            seenAuth = req.headers[HttpHeaders.Authorization]
            respond("model not found", HttpStatusCode.NotFound)
        }
        val provider = OpenAiCompatibleProvider(HttpClient(engine) { install(HttpTimeout) }, config.copy(apiKey = null))
        assertFailsWith<IllegalStateException> { provider.completeJson("s", "u") }
        assertNull(seenAuth)
    }
}
