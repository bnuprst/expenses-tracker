package com.bnuprst.expenses.banking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Subset of the Enable Banking API (https://enablebanking.com/docs/api/reference/). Unknown fields are ignored.

@Serializable
data class EbAspspList(val aspsps: List<EbAspsp> = emptyList())

@Serializable
data class EbAspsp(
    val name: String,
    val country: String,
    val logo: String? = null,
    /** Seconds. */
    @SerialName("maximum_consent_validity") val maximumConsentValidity: Long? = null,
)

@Serializable
data class EbAuthRequest(
    val access: EbAccess,
    val aspsp: EbAspspRef,
    val state: String,
    @SerialName("redirect_url") val redirectUrl: String,
    @SerialName("psu_type") val psuType: String = "personal",
)

@Serializable
data class EbAccess(@SerialName("valid_until") val validUntil: String)

@Serializable
data class EbAspspRef(val name: String, val country: String)

@Serializable
data class EbAuthResponse(val url: String, @SerialName("authorization_id") val authorizationId: String? = null)

@Serializable
data class EbSessionRequest(val code: String)

@Serializable
data class EbSessionResponse(
    @SerialName("session_id") val sessionId: String,
    /** Either account objects or bare uids depending on API version; see [EnableBankingClient.createSession]. */
    val accounts: List<JsonElement> = emptyList(),
    val access: EbAccess? = null,
)

@Serializable
data class EbAccount(
    val uid: String,
    @SerialName("account_id") val accountId: EbAccountId? = null,
    @SerialName("identification_hash") val identificationHash: String? = null,
    val name: String? = null,
    val currency: String? = null,
)

@Serializable
data class EbAccountId(val iban: String? = null, val other: EbOtherId? = null)

@Serializable
data class EbOtherId(val identification: String? = null)

@Serializable
data class EbTransactionsPage(
    val transactions: List<EbTransaction> = emptyList(),
    @SerialName("continuation_key") val continuationKey: String? = null,
)

@Serializable
data class EbTransaction(
    @SerialName("entry_reference") val entryReference: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("transaction_amount") val transactionAmount: EbAmount,
    @SerialName("credit_debit_indicator") val creditDebitIndicator: String,
    val status: String? = null,
    @SerialName("booking_date") val bookingDate: String? = null,
    @SerialName("value_date") val valueDate: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    val creditor: EbParty? = null,
    val debtor: EbParty? = null,
    @SerialName("creditor_account") val creditorAccount: EbAccountId? = null,
    @SerialName("debtor_account") val debtorAccount: EbAccountId? = null,
    @SerialName("remittance_information") val remittanceInformation: List<String>? = null,
    @SerialName("bank_transaction_code") val bankTransactionCode: EbBankTxnCode? = null,
    val note: String? = null,
)

@Serializable
data class EbAmount(val currency: String, val amount: String)

@Serializable
data class EbParty(val name: String? = null)

@Serializable
data class EbBankTxnCode(val description: String? = null)

class EnableBankingException(val status: Int, val body: String) : RuntimeException("Enable Banking HTTP $status: ${body.take(300)}") {
    /** Session no longer usable; the user must re-authorise. */
    val isConsentProblem: Boolean
        get() = status == 401 || status == 403 ||
            (status in 400..499 && SESSION_ERRORS.any { body.contains(it, ignoreCase = true) })

    /** Enable Banking's own error message (e.g. "Redirect URI not allowed"), safe to show to the user. */
    val apiMessage: String
        get() = runCatching { Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: "HTTP $status"

    companion object {
        private val SESSION_ERRORS = listOf("EXPIRED_SESSION", "CLOSED_SESSION", "REVOKED_SESSION", "SESSION_DOES_NOT_EXIST")
    }
}
