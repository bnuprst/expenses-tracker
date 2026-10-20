package com.bnuprst.expenses.banking

import com.bnuprst.expenses.BadRequest
import com.bnuprst.expenses.NotFound
import com.bnuprst.expenses.config.EnableBankingConfig
import com.bnuprst.expenses.db.BankAccounts
import com.bnuprst.expenses.db.BankConnections
import com.bnuprst.expenses.db.SyncRuns
import com.bnuprst.expenses.db.WalletMembers
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.AspspDto
import com.bnuprst.expenses.shared.BankAccountDto
import com.bnuprst.expenses.shared.BankAuthResponse
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.SyncResultDto
import com.bnuprst.expenses.transactions.TransactionService
import com.bnuprst.expenses.wallets.WalletService
import io.ktor.http.URLBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class BankingService(
    private val api: EnableBankingApi,
    private val config: EnableBankingConfig,
    private val wallets: WalletService,
    private val transactions: TransactionService,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(BankingService::class.java)
    private val random = SecureRandom()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun aspsps(country: String?): List<AspspDto> =
        api.aspsps(country?.uppercase()).map {
            AspspDto(it.name, it.country, it.logo, it.maximumConsentValidity?.let { s -> (s / 86_400).toInt() })
        }

    /** Creates a pending connection and returns the bank's authorisation URL. */
    suspend fun connect(userId: Long, walletId: Long, aspspName: String, country: String): BankAuthResponse {
        wallets.requireRole(userId, walletId)
        val id = dbQuery {
            BankConnections.insert {
                it[BankConnections.walletId] = walletId
                it[createdBy] = userId
                it[BankConnections.aspspName] = aspspName
                it[BankConnections.country] = country.uppercase()
                it[status] = ConnectionStatus.PENDING.name
            }[BankConnections.id]
        }
        return BankAuthResponse(id, startAuth(id, aspspName, country.uppercase()))
    }

    /** Renews consent for an existing connection; accounts and transaction history are kept. */
    suspend fun reauthorize(userId: Long, connectionId: Long): BankAuthResponse {
        val row = requireConnection(userId, connectionId)
        return BankAuthResponse(connectionId, startAuth(connectionId, row[BankConnections.aspspName], row[BankConnections.country]))
    }

    private suspend fun startAuth(connectionId: Long, aspspName: String, country: String): String {
        val aspsp = api.aspsps(country).firstOrNull { it.name == aspspName }
            ?: throw BadRequest("Unknown bank '$aspspName' in $country")
        var validity = Duration.ofDays(config.consentDays)
        aspsp.maximumConsentValidity?.let { validity = minOf(validity, Duration.ofSeconds(it)) }
        val validUntil = OffsetDateTime.ofInstant(clock().plus(validity).minusSeconds(60), ZoneOffset.UTC)
        val state = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        dbQuery { BankConnections.update({ BankConnections.id eq connectionId }) { it[authState] = state } }
        return api.startAuth(
            EbAuthRequest(EbAccess(validUntil.toString()), EbAspspRef(aspspName, country), state, config.redirectUrl)
        ).url
    }

    /**
     * Handles the bank redirect. Returns the URL to send the browser to (the app's deep link) and the
     * connection id to sync, if the authorisation succeeded.
     */
    suspend fun handleCallback(state: String?, code: String?, error: String?, errorDescription: String?): Pair<String, Long?> {
        val row = state?.let { s -> dbQuery { BankConnections.selectAll().where { BankConnections.authState eq s }.singleOrNull() } }
            ?: return appRedirect(null, "error", "Unknown or expired authorisation state") to null
        val id = row[BankConnections.id]
        if (error != null || code == null) {
            dbQuery {
                BankConnections.update({ BankConnections.id eq id }) {
                    it[authState] = null
                    if (row[BankConnections.status] == ConnectionStatus.PENDING.name) it[status] = ConnectionStatus.ERROR.name
                }
            }
            return appRedirect(id, "error", errorDescription ?: error ?: "Authorisation cancelled") to null
        }
        return try {
            val (session, accounts) = api.createSession(code)
            dbQuery {
                BankConnections.update({ BankConnections.id eq id }) {
                    it[sessionId] = session.sessionId
                    it[validUntil] = session.access?.validUntil?.let(OffsetDateTime::parse)
                    it[status] = ConnectionStatus.ACTIVE.name
                    it[authState] = null
                }
                for (a in accounts) upsertAccount(id, a)
            }
            appRedirect(id, "ok", null) to id
        } catch (e: Exception) {
            log.warn("Creating Enable Banking session failed for connection {}", id, e)
            dbQuery { BankConnections.update({ BankConnections.id eq id }) { it[authState] = null } }
            appRedirect(id, "error", "Could not complete bank authorisation") to null
        }
    }

    private fun upsertAccount(connectionId: Long, a: EbAccount) {
        val iban = a.accountId?.iban
        val identity = a.identificationHash ?: iban ?: a.accountId?.other?.identification ?: a.uid
        val masked = iban?.let { if (it.length > 4) "•••• " + it.takeLast(4) else it }
        val existing = BankAccounts.selectAll()
            .where { (BankAccounts.connectionId eq connectionId) and (BankAccounts.identityKey eq identity) }
            .singleOrNull()
        if (existing == null) {
            BankAccounts.insert {
                it[BankAccounts.connectionId] = connectionId
                it[externalUid] = a.uid
                it[identityKey] = identity
                it[name] = a.name
                it[ibanMasked] = masked
                it[currency] = a.currency
            }
        } else {
            BankAccounts.update({ BankAccounts.id eq existing[BankAccounts.id] }) {
                it[externalUid] = a.uid
                it[name] = a.name ?: existing[BankAccounts.name]
                it[currency] = a.currency ?: existing[BankAccounts.currency]
            }
        }
    }

    private fun appRedirect(connectionId: Long?, status: String, message: String?): String =
        URLBuilder(config.appRedirectUrl).apply {
            parameters.append("status", status)
            connectionId?.let { parameters.append("connection", it.toString()) }
            message?.let { parameters.append("message", it) }
        }.buildString()

    suspend fun list(userId: Long, walletId: Long?): List<BankConnectionDto> = dbQuery {
        val rows = BankConnections.join(WalletMembers, JoinType.INNER, BankConnections.walletId, WalletMembers.walletId).selectAll().where {
            var c = WalletMembers.userId eq userId
            walletId?.let { c = c and (BankConnections.walletId eq it) }
            c
        }.orderBy(BankConnections.id)
        rows.map { row ->
            val id = row[BankConnections.id]
            val accounts = BankAccounts.selectAll().where { BankAccounts.connectionId eq id }.map {
                BankAccountDto(it[BankAccounts.id], it[BankAccounts.name], it[BankAccounts.ibanMasked], it[BankAccounts.currency])
            }
            val lastRun = SyncRuns.selectAll().where { SyncRuns.connectionId eq id }
                .orderBy(SyncRuns.startedAt, SortOrder.DESC).limit(1).singleOrNull()
            row.toDto(accounts, lastRun)
        }
    }

    private fun ResultRow.toDto(accounts: List<BankAccountDto>, lastRun: ResultRow?): BankConnectionDto {
        val validUntil = this[BankConnections.validUntil]
        val status = ConnectionStatus.valueOf(this[BankConnections.status])
        return BankConnectionDto(
            id = this[BankConnections.id],
            walletId = this[BankConnections.walletId],
            aspspName = this[BankConnections.aspspName],
            country = this[BankConnections.country],
            status = status,
            validUntil = validUntil?.toInstant()?.toString(),
            renewSoon = status == ConnectionStatus.EXPIRED ||
                (validUntil != null && validUntil.toInstant().isBefore(clock().plus(RENEW_WARNING))),
            lastSyncAt = lastRun?.get(SyncRuns.finishedAt)?.toInstant()?.toString(),
            lastSyncError = lastRun?.get(SyncRuns.error),
            accounts = accounts,
        )
    }

    suspend fun syncForUser(userId: Long, connectionId: Long): SyncResultDto {
        requireConnection(userId, connectionId)
        return sync(connectionId)
    }

    /** Scheduled entry point: syncs every active connection, expiring those past their consent. */
    suspend fun syncAll() {
        val ids = dbQuery {
            BankConnections.select(BankConnections.id)
                .where { BankConnections.status eq ConnectionStatus.ACTIVE.name }
                .map { it[BankConnections.id] }
        }
        for (id in ids) {
            runCatching { sync(id) }.onFailure { log.error("Sync of connection {} failed", id, it) }
        }
    }

    suspend fun sync(connectionId: Long): SyncResultDto = locks.computeIfAbsent(connectionId) { Mutex() }.withLock {
        val conn = dbQuery { BankConnections.selectAll().where { BankConnections.id eq connectionId }.single() }
        val walletId = conn[BankConnections.walletId]
        if (conn[BankConnections.status] != ConnectionStatus.ACTIVE.name) {
            return SyncResultDto(0, 0, "Connection is ${conn[BankConnections.status]}")
        }
        if (conn[BankConnections.validUntil]?.toInstant()?.isBefore(clock()) == true) {
            setStatus(connectionId, ConnectionStatus.EXPIRED)
            return SyncResultDto(0, 0, "Consent expired; re-authorise the connection")
        }
        val runId = dbQuery {
            SyncRuns.insert {
                it[SyncRuns.connectionId] = connectionId
                it[startedAt] = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
                it[fetched] = 0
                it[inserted] = 0
            }[SyncRuns.id]
        }
        var fetched = 0
        var inserted = 0
        var error: String? = null
        try {
            val accounts = dbQuery { BankAccounts.selectAll().where { BankAccounts.connectionId eq connectionId }.toList() }
            val today = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
            for (acc in accounts) {
                // Re-fetch a week of overlap: banks book late and dedup makes this safe.
                val from = acc[BankAccounts.lastBookingDate]?.minusDays(7) ?: today.minusDays(config.initialHistoryDays)
                var key: String? = null
                var maxDate = acc[BankAccounts.lastBookingDate]
                do {
                    val page = api.transactions(acc[BankAccounts.externalUid], from, key)
                    val mapped = TransactionMapper.map(page.transactions, today)
                    fetched += mapped.size
                    inserted += transactions.importBankTransactions(walletId, acc[BankAccounts.id], mapped)
                    mapped.maxOfOrNull { it.bookingDate }?.let { d -> if (maxDate == null || d > maxDate) maxDate = d }
                    key = page.continuationKey?.takeIf { it.isNotBlank() }
                } while (key != null)
                dbQuery { BankAccounts.update({ BankAccounts.id eq acc[BankAccounts.id] }) { it[lastBookingDate] = maxDate } }
            }
        } catch (e: EnableBankingException) {
            error = e.message
            if (e.isConsentProblem) setStatus(connectionId, ConnectionStatus.EXPIRED)
        } catch (e: Exception) {
            log.error("Sync failed for connection {}", connectionId, e)
            error = e.message ?: e.javaClass.simpleName
        }
        dbQuery {
            SyncRuns.update({ SyncRuns.id eq runId }) {
                it[finishedAt] = OffsetDateTime.ofInstant(clock(), ZoneOffset.UTC)
                it[SyncRuns.fetched] = fetched
                it[SyncRuns.inserted] = inserted
                it[SyncRuns.error] = error
            }
        }
        SyncResultDto(fetched, inserted, error)
    }

    private suspend fun setStatus(connectionId: Long, status: ConnectionStatus) = dbQuery {
        BankConnections.update({ BankConnections.id eq connectionId }) { it[BankConnections.status] = status.name }
    }

    private suspend fun requireConnection(userId: Long, connectionId: Long): ResultRow {
        val row = dbQuery { BankConnections.selectAll().where { BankConnections.id eq connectionId }.singleOrNull() }
            ?: throw NotFound("Connection not found")
        try {
            wallets.requireRole(userId, row[BankConnections.walletId])
        } catch (e: NotFound) {
            throw NotFound("Connection not found")
        }
        return row
    }

    companion object {
        val RENEW_WARNING: Duration = Duration.ofDays(7)
    }
}
