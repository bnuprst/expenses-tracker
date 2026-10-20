package com.bnuprst.expenses.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppConfigTest {
    private fun llmEnabled(vararg env: Pair<String, String>) = AppConfig.fromEnv(mapOf("JWT_SECRET" to "x", *env)).llm.enabled

    @Test
    fun `hosted openai without a key is off`() {
        assertFalse(llmEnabled())
        assertFalse(llmEnabled("LLM_BASE_URL" to "https://api.openai.com/v1"))
        assertFalse(llmEnabled("LLM_API_KEY" to "  "))
    }

    @Test
    fun `a key or a self-hosted endpoint turns it on`() {
        assertTrue(llmEnabled("LLM_API_KEY" to "sk-x"))
        assertTrue(llmEnabled("LLM_BASE_URL" to "http://ollama:11434/v1"))
        assertFalse(llmEnabled("LLM_API_KEY" to "sk-x", "LLM_ENABLED" to "false"))
    }
}
