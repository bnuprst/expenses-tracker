package com.bnuprst.expenses.shared

import kotlinx.serialization.Serializable

// Dates are ISO-8601 strings (yyyy-MM-dd), instants are ISO-8601 UTC, amounts are decimal strings.

@Serializable
enum class CategoryKind { EXPENSE, INCOME, TRANSFER }

@Serializable
enum class TxnStatus { CATEGORIZED, REVIEW, UNCATEGORIZED }

@Serializable
enum class CategorySource { MEMORY, RULE, LLM, MANUAL }

@Serializable
enum class WalletRole { OWNER, MEMBER }

@Serializable
enum class ConnectionStatus { PENDING, ACTIVE, EXPIRED, ERROR }

@Serializable
enum class RuleField { DESCRIPTION, MERCHANT, COUNTERPARTY, AMOUNT }

@Serializable
enum class RuleOp { CONTAINS, EQUALS, REGEX, GT, LT }

// --- auth ---

@Serializable
data class RegisterRequest(val email: String, val password: String, val name: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(val id: Long, val email: String, val name: String)

// --- wallets ---

@Serializable
data class WalletDto(val id: Long, val name: String, val currency: String, val role: WalletRole)

@Serializable
data class CreateWalletRequest(val name: String, val currency: String = "EUR")

@Serializable
data class AddMemberRequest(val email: String)

@Serializable
data class MemberDto(val userId: Long, val email: String, val name: String, val role: WalletRole)

// --- categories & rules ---

@Serializable
data class CategoryDto(val id: Long, val name: String, val kind: CategoryKind, val parentId: Long? = null)

@Serializable
data class RuleDto(
    val id: Long,
    val priority: Int,
    val field: RuleField,
    val op: RuleOp,
    val value: String,
    val categoryId: Long,
)

@Serializable
data class CreateRuleRequest(
    val priority: Int = 100,
    val field: RuleField,
    val op: RuleOp,
    val value: String,
    val categoryId: Long,
)

// --- transactions ---

@Serializable
data class TransactionDto(
    val id: Long,
    val walletId: Long,
    val bankAccountId: Long? = null,
    val bookingDate: String,
    val amount: String,
    val currency: String,
    val description: String,
    val counterparty: String? = null,
    val merchantKey: String,
    val categoryId: Long? = null,
    val categorySource: CategorySource? = null,
    val confidence: Double? = null,
    val status: TxnStatus,
)

@Serializable
data class CreateTransactionRequest(
    val bookingDate: String,
    val amount: String,
    val currency: String? = null,
    val description: String,
    val counterparty: String? = null,
    val categoryId: Long? = null,
)

@Serializable
data class UpdateTransactionRequest(val categoryId: Long)

// --- banking ---

@Serializable
data class AspspDto(val name: String, val country: String, val logo: String? = null, val maxConsentDays: Int? = null)

@Serializable
data class CreateBankConnectionRequest(val aspspName: String, val country: String)

@Serializable
data class BankAuthResponse(val connectionId: Long, val authUrl: String)

@Serializable
data class BankAccountDto(val id: Long, val name: String?, val ibanMasked: String?, val currency: String?)

@Serializable
data class BankConnectionDto(
    val id: Long,
    val walletId: Long,
    val aspspName: String,
    val country: String,
    val status: ConnectionStatus,
    val validUntil: String? = null,
    val renewSoon: Boolean,
    val lastSyncAt: String? = null,
    val lastSyncError: String? = null,
    val accounts: List<BankAccountDto> = emptyList(),
)

@Serializable
data class SyncResultDto(val fetched: Int, val inserted: Int, val error: String? = null)

@Serializable
data class ErrorResponse(val error: String)
