package com.bnuprst.expenses.categorization.llm

import com.bnuprst.expenses.config.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Chat completion returning the assistant's raw JSON text. */
interface LlmProvider {
    suspend fun completeJson(system: String, user: String): String
}

/**
 * Works with any OpenAI-compatible `/chat/completions` endpoint: OpenAI, Ollama (`http://ollama:11434/v1`),
 * OpenRouter, LM Studio, vLLM, Groq, Mistral, etc. Configure via LLM_BASE_URL / LLM_API_KEY / LLM_MODEL.
 */
class OpenAiCompatibleProvider(
    private val http: HttpClient,
    private val config: LlmConfig,
) : LlmProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun completeJson(system: String, user: String): String {
        val body = buildJsonObject {
            put("model", config.model)
            config.temperature?.let { put("temperature", it) }
            config.reasoningEffort?.let { put("reasoning_effort", it) }
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
        }
        val response = http.post("${config.baseUrl}/chat/completions") {
            config.apiKey?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            timeout { requestTimeoutMillis = config.timeout.toMillis() }
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) error("LLM HTTP ${response.status.value}: ${text.take(300)}")
        return json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
    }

    companion object {
        fun httpClient() = HttpClient(io.ktor.client.engine.cio.CIO) { install(HttpTimeout) }
    }
}
