package com.bnuprst.expenses.categorization

import com.bnuprst.expenses.db.MerchantMemory
import com.bnuprst.expenses.db.Rules
import com.bnuprst.expenses.db.dbQuery
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.RuleDto
import com.bnuprst.expenses.shared.RuleField
import com.bnuprst.expenses.shared.RuleOp
import com.bnuprst.expenses.wallets.WalletService
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager

class DbCategorizationStore : CategorizationStore {
    override suspend fun memoryLookup(walletId: Long, merchantKey: String): Long? = dbQuery {
        MerchantMemory.selectAll()
            .where { (MerchantMemory.walletId eq walletId) and (MerchantMemory.merchantKey eq merchantKey) }
            .singleOrNull()?.get(MerchantMemory.categoryId)
    }

    override suspend fun rules(walletId: Long): List<RuleDto> = dbQuery {
        Rules.selectAll().where { Rules.walletId eq walletId }.map {
            RuleDto(it[Rules.id], it[Rules.priority], RuleField.valueOf(it[Rules.field]), RuleOp.valueOf(it[Rules.op]), it[Rules.value], it[Rules.categoryId])
        }
    }

    override suspend fun categories(walletId: Long): List<CategoryDto> = WalletService.categoriesOf(walletId)

    companion object {
        /** Records a user's decision. Must run inside a transaction. */
        fun learn(walletId: Long, merchantKey: String, categoryId: Long) {
            TransactionManager.current().exec(
                """
                INSERT INTO merchant_memory (wallet_id, merchant_key, category_id, hits, updated_at)
                VALUES (?, ?, ?, 1, now())
                ON CONFLICT (wallet_id, merchant_key) DO UPDATE SET
                    hits = CASE WHEN merchant_memory.category_id = EXCLUDED.category_id THEN merchant_memory.hits + 1 ELSE 1 END,
                    category_id = EXCLUDED.category_id,
                    updated_at = now()
                """.trimIndent(),
                listOf(LongColumnType() to walletId, TextColumnType() to merchantKey, LongColumnType() to categoryId),
            )
        }
    }
}
