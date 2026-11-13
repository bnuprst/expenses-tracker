package com.bnuprst.expenses.transactions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes the writers that can change a wallet's transfer pairing: TransferScanService.scan and
 * TransactionService.setCategory. ONE instance per process, injected into both — two registries
 * would serialize nothing.
 *
 * The mutex is NOT reentrant: nothing invoked while holding it may re-enter withWallet for the same
 * wallet, and in particular no FX fetch inside it may notify a rescan (see
 * FxService.ensureCovered `notify = false` and TransferScanService steps 2/6).
 *
 * Lock order, where more than one lock is held: BankingService's per-connection mutex (outer) ->
 * WalletLocks (inner). No code path takes them the other way round, so there is no cycle.
 */
class WalletLocks {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withWallet(walletId: Long, block: suspend () -> T): T =
        locks.computeIfAbsent(walletId) { Mutex() }.withLock { block() }
}
