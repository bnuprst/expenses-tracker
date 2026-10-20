package com.bnuprst.expenses.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = text("email")
    val name = text("name")
    val passwordHash = text("password_hash")
    override val primaryKey = PrimaryKey(id)
}

object Wallets : Table("wallets") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val currency = varchar("currency", 3)
    override val primaryKey = PrimaryKey(id)
}

object WalletMembers : Table("wallet_members") {
    val walletId = long("wallet_id").references(Wallets.id)
    val userId = long("user_id").references(Users.id)
    val role = varchar("role", 16)
    override val primaryKey = PrimaryKey(walletId, userId)
}

object Categories : Table("categories") {
    val id = long("id").autoIncrement()
    val walletId = long("wallet_id").references(Wallets.id)
    val name = text("name")
    val kind = varchar("kind", 16)
    val parentId = long("parent_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object BankConnections : Table("bank_connections") {
    val id = long("id").autoIncrement()
    val walletId = long("wallet_id").references(Wallets.id)
    val createdBy = long("created_by").references(Users.id)
    val aspspName = text("aspsp_name")
    val country = varchar("country", 2)
    val sessionId = text("session_id").nullable()
    val authState = text("auth_state").nullable()
    val validUntil = timestampWithTimeZone("valid_until").nullable()
    val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}

object BankAccounts : Table("bank_accounts") {
    val id = long("id").autoIncrement()
    val connectionId = long("connection_id").references(BankConnections.id)
    val externalUid = text("external_uid")
    val identityKey = text("identity_key")
    val name = text("name").nullable()
    val ibanMasked = text("iban_masked").nullable()
    val currency = varchar("currency", 3).nullable()
    val lastBookingDate = date("last_booking_date").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Transactions : Table("transactions") {
    val id = long("id").autoIncrement()
    val walletId = long("wallet_id").references(Wallets.id)
    val bankAccountId = long("bank_account_id").nullable()
    val externalId = text("external_id").nullable()
    val bookingDate = date("booking_date")
    val amount = decimal("amount", 14, 2)
    val currency = varchar("currency", 3)
    val descriptionRaw = text("description_raw")
    val counterparty = text("counterparty").nullable()
    val merchantKey = text("merchant_key")
    val categoryId = long("category_id").nullable()
    val categorySource = varchar("category_source", 16).nullable()
    val confidence = double("confidence").nullable()
    val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)
}

object MerchantMemory : Table("merchant_memory") {
    val walletId = long("wallet_id").references(Wallets.id)
    val merchantKey = text("merchant_key")
    val categoryId = long("category_id")
    val hits = integer("hits")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(walletId, merchantKey)
}

object Rules : Table("rules") {
    val id = long("id").autoIncrement()
    val walletId = long("wallet_id").references(Wallets.id)
    val priority = integer("priority")
    val field = varchar("field", 16)
    val op = varchar("op", 16)
    val value = text("value")
    val categoryId = long("category_id")
    override val primaryKey = PrimaryKey(id)
}

object SyncRuns : Table("sync_runs") {
    val id = long("id").autoIncrement()
    val connectionId = long("connection_id").references(BankConnections.id)
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val fetched = integer("fetched")
    val inserted = integer("inserted")
    val error = text("error").nullable()
    override val primaryKey = PrimaryKey(id)
}
