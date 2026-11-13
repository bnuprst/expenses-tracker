package com.bnuprst.expenses.wallets

import com.bnuprst.expenses.BadRequest
import com.bnuprst.expenses.Conflict
import com.bnuprst.expenses.Forbidden
import com.bnuprst.expenses.NotFound
import com.bnuprst.expenses.db.Categories
import com.bnuprst.expenses.db.Users
import com.bnuprst.expenses.db.WalletMembers
import com.bnuprst.expenses.db.Wallets
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.CreateWalletRequest
import com.bnuprst.expenses.shared.MemberDto
import com.bnuprst.expenses.shared.WalletDto
import com.bnuprst.expenses.shared.WalletRole
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

class WalletService {

    suspend fun list(userId: Long): List<WalletDto> = dbQuery {
        (Wallets innerJoin WalletMembers).selectAll()
            .where { WalletMembers.userId eq userId }
            .orderBy(Wallets.id)
            .map { WalletDto(it[Wallets.id], it[Wallets.name], it[Wallets.currency], WalletRole.valueOf(it[WalletMembers.role])) }
    }

    suspend fun create(userId: Long, req: CreateWalletRequest): WalletDto {
        if (req.name.isBlank()) throw BadRequest("wallet.name_required")
        val currency = req.currency.uppercase()
        if (currency.length != 3) throw BadRequest("wallet.currency_invalid")
        return dbQuery {
            val id = Wallets.insert {
                it[name] = req.name.trim()
                it[Wallets.currency] = currency
            }[Wallets.id]
            WalletMembers.insert {
                it[walletId] = id
                it[WalletMembers.userId] = userId
                it[role] = WalletRole.OWNER.name
            }
            Categories.batchInsert(DEFAULT_CATEGORIES) { seed ->
                this[Categories.walletId] = id
                this[Categories.name] = seed.name
                this[Categories.slug] = seed.slug
                this[Categories.kind] = seed.kind.name
            }
            WalletDto(id, req.name.trim(), currency, WalletRole.OWNER)
        }
    }

    suspend fun members(userId: Long, walletId: Long): List<MemberDto> {
        requireRole(userId, walletId)
        return dbQuery {
            (WalletMembers innerJoin Users).selectAll()
                .where { WalletMembers.walletId eq walletId }
                .map { MemberDto(it[Users.id], it[Users.email], it[Users.name], WalletRole.valueOf(it[WalletMembers.role])) }
        }
    }

    /** Adds an existing user to the wallet. Only owners may add members. */
    suspend fun addMember(userId: Long, walletId: Long, email: String): MemberDto {
        if (requireRole(userId, walletId) != WalletRole.OWNER) throw Forbidden("wallet.owner_only_members")
        return dbQuery {
            val user = Users.selectAll().where { Users.email eq email.trim().lowercase() }.singleOrNull()
                ?: throw NotFound("wallet.user_not_found")
            val already = WalletMembers.selectAll()
                .where { (WalletMembers.walletId eq walletId) and (WalletMembers.userId eq user[Users.id]) }.any()
            if (already) throw Conflict("wallet.member_exists")
            WalletMembers.insert {
                it[WalletMembers.walletId] = walletId
                it[WalletMembers.userId] = user[Users.id]
                it[role] = WalletRole.MEMBER.name
            }
            MemberDto(user[Users.id], user[Users.email], user[Users.name], WalletRole.MEMBER)
        }
    }

    suspend fun categories(userId: Long, walletId: Long): List<CategoryDto> {
        requireRole(userId, walletId)
        return categoriesOf(walletId)
    }

    /** Returns the caller's role, or throws 404 so wallet ids aren't enumerable. */
    suspend fun requireRole(userId: Long, walletId: Long): WalletRole = dbQuery {
        WalletMembers.selectAll()
            .where { (WalletMembers.walletId eq walletId) and (WalletMembers.userId eq userId) }
            .singleOrNull()?.let { WalletRole.valueOf(it[WalletMembers.role]) }
    } ?: throw NotFound("wallet.not_found")

    companion object {
        suspend fun categoriesOf(walletId: Long): List<CategoryDto> = dbQuery {
            Categories.selectAll().where { Categories.walletId eq walletId }.orderBy(Categories.name)
                .map { CategoryDto(it[Categories.id], it[Categories.name], CategoryKind.valueOf(it[Categories.kind]), it[Categories.parentId], it[Categories.slug]) }
        }

        data class SeedCategory(val slug: String, val name: String, val kind: CategoryKind)

        val DEFAULT_CATEGORIES = listOf(
            SeedCategory("groceries", "Groceries", CategoryKind.EXPENSE),
            SeedCategory("restaurants", "Restaurants & Cafes", CategoryKind.EXPENSE),
            SeedCategory("transport", "Transport", CategoryKind.EXPENSE),
            SeedCategory("fuel", "Fuel", CategoryKind.EXPENSE),
            SeedCategory("housing", "Housing & Rent", CategoryKind.EXPENSE),
            SeedCategory("utilities", "Utilities", CategoryKind.EXPENSE),
            SeedCategory("phone_internet", "Phone & Internet", CategoryKind.EXPENSE),
            SeedCategory("health", "Health & Pharmacy", CategoryKind.EXPENSE),
            SeedCategory("kids", "Kids", CategoryKind.EXPENSE),
            SeedCategory("clothing", "Clothing", CategoryKind.EXPENSE),
            SeedCategory("household", "Household", CategoryKind.EXPENSE),
            SeedCategory("entertainment", "Entertainment", CategoryKind.EXPENSE),
            SeedCategory("subscriptions", "Subscriptions", CategoryKind.EXPENSE),
            SeedCategory("travel", "Travel", CategoryKind.EXPENSE),
            SeedCategory("education", "Education", CategoryKind.EXPENSE),
            SeedCategory("gifts", "Gifts & Donations", CategoryKind.EXPENSE),
            SeedCategory("insurance", "Insurance", CategoryKind.EXPENSE),
            SeedCategory("taxes", "Taxes & Fees", CategoryKind.EXPENSE),
            SeedCategory("other", "Other", CategoryKind.EXPENSE),
            SeedCategory("salary", "Salary", CategoryKind.INCOME),
            SeedCategory("other_income", "Other Income", CategoryKind.INCOME),
            SeedCategory("transfers", "Transfers", CategoryKind.TRANSFER),
        )
    }
}
