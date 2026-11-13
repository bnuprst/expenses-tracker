package com.bnuprst.expenses.config

import java.time.Duration

data class AppConfig(
    val port: Int,
    val publicBaseUrl: String,
    val db: DbConfig,
    val jwt: JwtConfig,
    val enableBanking: EnableBankingConfig?,
    val llm: LlmConfig,
    val fx: FxConfig,
) {
    companion object {
        /**
         * A key makes any provider usable. Without one, only an explicitly configured self-hosted endpoint
         * (Ollama, LM Studio) is - OpenAI's hosted API would answer every call with 401.
         */
        internal fun llmUsable(baseUrl: String?, apiKey: String?): Boolean =
            apiKey != null || (baseUrl != null && !baseUrl.contains("api.openai.com"))

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun get(key: String, default: String? = null): String =
                env[key]?.takeIf { it.isNotBlank() } ?: default ?: error("Missing required env var $key")
            fun opt(key: String): String? = env[key]?.takeIf { it.isNotBlank() }

            val publicBaseUrl = get("PUBLIC_BASE_URL", "http://localhost:8080").trimEnd('/')
            val ebAppId = opt("EB_APP_ID")
            return AppConfig(
                port = get("PORT", "8080").toInt(),
                publicBaseUrl = publicBaseUrl,
                db = DbConfig(
                    url = get("DB_URL", "jdbc:postgresql://localhost:5432/expenses"),
                    user = get("DB_USER", "expenses"),
                    password = get("DB_PASSWORD", "expenses"),
                ),
                jwt = JwtConfig(
                    secret = get("JWT_SECRET"),
                    issuer = publicBaseUrl,
                    ttl = Duration.ofDays(get("JWT_TTL_DAYS", "30").toLong()),
                ),
                enableBanking = ebAppId?.let {
                    EnableBankingConfig(
                        appId = it,
                        privateKeyPath = get("EB_PRIVATE_KEY_PATH"),
                        apiBase = get("EB_API_BASE", "https://api.enablebanking.com").trimEnd('/'),
                        redirectUrl = get("EB_REDIRECT_URL", "$publicBaseUrl/api/v1/banking/callback"),
                        appRedirectUrl = get("APP_REDIRECT_URL", "expenses://bank-connected"),
                        consentDays = get("EB_CONSENT_DAYS", "180").toLong(),
                        syncInterval = Duration.parse(get("SYNC_INTERVAL", "PT8H")),
                        initialHistoryDays = get("EB_INITIAL_HISTORY_DAYS", "90").toLong(),
                    )
                },
                llm = LlmConfig(
                    enabled = get("LLM_ENABLED", "true").toBoolean() && llmUsable(opt("LLM_BASE_URL"), opt("LLM_API_KEY")),
                    baseUrl = get("LLM_BASE_URL", "https://api.openai.com/v1").trimEnd('/'),
                    apiKey = opt("LLM_API_KEY"),
                    model = get("LLM_MODEL", "gpt-6-luna"),
                    confidenceThreshold = get("LLM_CONFIDENCE_THRESHOLD", "0.7").toDouble(),
                    timeout = Duration.ofSeconds(get("LLM_TIMEOUT_SECONDS", "30").toLong()),
                    temperature = opt("LLM_TEMPERATURE")?.toDouble(),
                    reasoningEffort = opt("LLM_REASONING_EFFORT"),
                ),
                fx = FxConfig(
                    enabled = get("FX_ENABLED", "true").toBoolean(),
                    apiBase = get("FX_API_BASE", "https://api.frankfurter.dev/v1").trimEnd('/'),
                    base = get("FX_BASE", "EUR").uppercase(),
                    refreshInterval = Duration.parse(get("FX_REFRESH_INTERVAL", "PT12H")),
                    maxStaleDays = get("FX_MAX_STALE_DAYS", "7").toLong(),
                    backfillDays = get("FX_BACKFILL_DAYS", "400").toLong(),
                    provisionalDays = get("FX_PROVISIONAL_DAYS", "5").toLong(),
                ),
            )
        }
    }
}

data class DbConfig(val url: String, val user: String, val password: String)

data class JwtConfig(val secret: String, val issuer: String, val ttl: Duration) {
    val audience = "expenses-app"
}

data class EnableBankingConfig(
    val appId: String,
    val privateKeyPath: String,
    val apiBase: String,
    /** Must be registered as a redirect URL for the application in the Enable Banking control panel. */
    val redirectUrl: String,
    /** Where the browser is sent after the callback is processed (Android deep link). */
    val appRedirectUrl: String,
    val consentDays: Long,
    val syncInterval: Duration,
    val initialHistoryDays: Long,
)

data class LlmConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val confidenceThreshold: Double,
    val timeout: Duration,
    /** Sent only when set: some reasoning models (GPT-5/6 family) reject non-default temperatures. */
    val temperature: Double? = null,
    /** OpenAI reasoning models: none/minimal/low/medium/high. Sent only when set. */
    val reasoningEffort: String? = null,
)

data class FxConfig(
    val enabled: Boolean,
    val apiBase: String,
    /** The provider's base currency; rates are stored against it and cross rates derived from it. */
    val base: String,
    val refreshInterval: Duration,
    /** How far back a lookup may reach for the last published rate (weekends, holidays). */
    val maxStaleDays: Long,
    /** How much history to fetch the first time, and the cap on any single catch-up fetch. */
    val backfillDays: Long,
    /**
     * Recent days with no publication yet are provisional and refetched (not more often than
     * [refreshInterval]); older unpublished days are final.
     */
    val provisionalDays: Long,
)
