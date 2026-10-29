package com.bnuprst.expenses.android

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnuprst.expenses.shared.AspspDto
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.StatsDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnSort
import com.bnuprst.expenses.shared.WalletDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

enum class WalletTab { TRANSACTIONS, REVIEW, STATS, BANKS }

data class DateRange(val from: LocalDate, val to: LocalDate)

data class TxnFilter(
    val query: String = "",
    val categoryId: Long? = null,
    val uncategorized: Boolean = false,
    val period: DateRange? = null,
    val sort: TxnSort = TxnSort.DATE_DESC,
) {
    val isActive get() = query.isNotBlank() || categoryId != null || uncategorized || period != null || sort != TxnSort.DATE_DESC
}

enum class StatsGranularity { MONTH, YEAR }

data class StatsPeriod(val granularity: StatsGranularity = StatsGranularity.MONTH, val anchor: LocalDate = LocalDate.now()) {
    val from: LocalDate get() = when (granularity) {
        StatsGranularity.MONTH -> anchor.withDayOfMonth(1)
        StatsGranularity.YEAR -> anchor.withDayOfYear(1)
    }
    val to: LocalDate get() = when (granularity) {
        StatsGranularity.MONTH -> YearMonth.from(anchor).atEndOfMonth()
        StatsGranularity.YEAR -> anchor.withDayOfYear(anchor.lengthOfYear())
    }
    fun shift(n: Long) = copy(anchor = if (granularity == StatsGranularity.MONTH) anchor.plusMonths(n) else anchor.plusYears(n))
}

data class UiState(
    val serverUrl: String = "",
    val loggedIn: Boolean = false,
    val userName: String = "",
    /** Number of running tasks; a counter so a cancelled task can't clear another one's indicator. */
    val busy: Int = 0,
    val refreshing: Boolean = false,
    val message: String? = null,
    val wallets: List<WalletDto> = emptyList(),
    val wallet: WalletDto? = null,
    val tab: WalletTab = WalletTab.TRANSACTIONS,
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val filter: TxnFilter = TxnFilter(),
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val review: List<TransactionDto> = emptyList(),
    val statsPeriod: StatsPeriod = StatsPeriod(),
    val stats: StatsDto? = null,
    val connections: List<BankConnectionDto> = emptyList(),
    val aspsps: List<AspspDto> = emptyList(),
) {
    val loading get() = busy > 0
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            serverUrl = prefs.getString("server", "") ?: "",
            loggedIn = prefs.getString("token", null) != null,
            userName = prefs.getString("name", "") ?: "",
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Set by the activity to open bank authorisation pages in a Custom Tab. */
    var openUrl: (String) -> Unit = {}

    private val api = Api(baseUrl = { _state.value.serverUrl }, token = { prefs.getString("token", null) })

    private val searchInput = MutableStateFlow("")
    private var txnJob: Job? = null
    private var txnGeneration = 0

    init {
        if (_state.value.loggedIn) loadWallets()
        @OptIn(FlowPreview::class)
        viewModelScope.launch { searchInput.drop(1).debounce(SEARCH_DEBOUNCE_MS).collect { reloadTransactions() } }
    }

    private fun launchTask(block: suspend () -> Unit): Job = viewModelScope.launch {
        _state.update { it.copy(busy = it.busy + 1, message = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message == "Unauthorized") logout()
            _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
        } finally {
            _state.update { it.copy(busy = (it.busy - 1).coerceAtLeast(0)) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun authenticate(server: String, email: String, password: String, name: String?, register: Boolean) {
        val url = server.trim()
        // Release builds refuse cleartext anyway (no network security exception); say why up front instead
        // of failing with a cryptic network error - and never send the password over http.
        if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) {
            _state.update { it.copy(message = "The server URL must start with https://") }
            return
        }
        _state.update { it.copy(serverUrl = url) }
        launchTask {
            val res = if (register) api.register(email, password, name.orEmpty()) else api.login(email, password)
            prefs.edit().putString("server", url).putString("token", res.token).putString("name", res.user.name).apply()
            _state.update { it.copy(loggedIn = true, userName = res.user.name) }
            refreshWallets()
        }
    }

    fun logout() {
        prefs.edit().remove("token").apply()
        cancelTxnLoads()
        _state.update { UiState(serverUrl = it.serverUrl) }
    }

    fun loadWallets() = launchTask { refreshWallets() }

    private suspend fun refreshWallets() {
        val wallets = api.wallets()
        _state.update { s -> s.copy(wallets = wallets, wallet = s.wallet?.let { w -> wallets.find { it.id == w.id } }) }
    }

    fun createWallet(name: String, currency: String) = launchTask {
        api.createWallet(name, currency.ifBlank { "EUR" })
        refreshWallets()
    }

    fun openWallet(wallet: WalletDto?) {
        cancelTxnLoads()
        _state.update {
            it.copy(
                wallet = wallet, tab = WalletTab.TRANSACTIONS, transactions = emptyList(), filter = TxnFilter(), hasMore = false,
                review = emptyList(), stats = null, statsPeriod = StatsPeriod(), connections = emptyList(), aspsps = emptyList(),
            )
        }
        if (wallet != null) refreshWallet()
    }

    fun selectTab(tab: WalletTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
        refreshWallet()
    }

    /** Pull-to-refresh: same as [refreshWallet], but shows the pull indicator until done. */
    fun pullRefresh() {
        _state.update { it.copy(refreshing = true) }
        refreshWallet().invokeOnCompletion { _state.update { it.copy(refreshing = false) } }
    }

    fun refreshWallet(): Job {
        val wallet = _state.value.wallet ?: return Job().apply { complete() }
        return launchTask {
            val categories = api.categories(wallet.id)
            // Always loaded: the Review destination shows its size as a badge.
            val review = api.review(wallet.id)
            updateWallet(wallet.id) { it.copy(categories = categories, review = review) }
            if (_state.value.wallet?.id != wallet.id) return@launchTask
            when (_state.value.tab) {
                WalletTab.TRANSACTIONS -> launchTxnLoad { gen -> fetchTransactions(wallet.id, append = false, gen) }.join()
                WalletTab.REVIEW -> Unit
                WalletTab.STATS -> fetchStats(wallet.id)
                WalletTab.BANKS -> api.connections(wallet.id).let { c -> updateWallet(wallet.id) { it.copy(connections = c) } }
            }
        }
    }

    /** Applies [block] only while [walletId] is still open, so a slow response can't land in another wallet. */
    private fun updateWallet(walletId: Long, block: (UiState) -> UiState) =
        _state.update { if (it.wallet?.id == walletId) block(it) else it }

    // --- transactions: search, filters, paging ---

    fun setQuery(query: String) {
        _state.update { it.copy(filter = it.filter.copy(query = query)) }
        searchInput.value = query
    }

    fun setFilter(filter: TxnFilter) {
        _state.update { it.copy(filter = filter) }
        searchInput.value = filter.query
        reloadTransactions()
    }

    /**
     * Every load of the transaction list (reload, refresh, next page, refetch) runs through here: it cancels
     * the previous one and gets a new generation, and results from an older generation are dropped. So a
     * page fetched for an offset of a list that has since been replaced can never be appended to it.
     */
    private fun launchTxnLoad(block: suspend (generation: Int) -> Unit): Job {
        cancelTxnLoads()
        val generation = txnGeneration
        return launchTask { block(generation) }.also { txnJob = it }
    }

    private fun cancelTxnLoads() {
        txnJob?.cancel()
        txnGeneration++
    }

    private fun reloadTransactions() {
        val wallet = _state.value.wallet ?: return
        launchTxnLoad { gen -> fetchTransactions(wallet.id, append = false, gen) }
    }

    fun loadMore() {
        val s = _state.value
        val wallet = s.wallet ?: return
        if (!s.hasMore || txnJob?.isActive == true) return
        _state.update { it.copy(loadingMore = true) }
        launchTxnLoad { gen -> fetchTransactions(wallet.id, append = true, gen) }.invokeOnCompletion {
            _state.update { it.copy(loadingMore = false) }
        }
    }

    private suspend fun fetchTransactions(walletId: Long, append: Boolean, generation: Int) {
        val filter = _state.value.filter
        val offset = if (append) _state.value.transactions.size else 0
        val page = api.transactions(walletId, filter, offset, PAGE_SIZE)
        _state.update { s ->
            // Superseded by a newer load or filter, or (for a page) the list it continues has changed length.
            if (generation != txnGeneration || s.filter != filter || (append && s.transactions.size != offset)) return@update s
            s.copy(
                transactions = if (append) (s.transactions + page).distinctBy { it.id } else page,
                hasMore = page.size == PAGE_SIZE,
            )
        }
    }

    /**
     * Reloads as many rows as are already shown, in pages the server will serve in full (it caps a page at
     * [SERVER_MAX_PAGE]), so the list keeps its length and paging carries on where it was.
     */
    private suspend fun refetchLoaded(walletId: Long, generation: Int) {
        val filter = _state.value.filter
        val wanted = maxOf(PAGE_SIZE, _state.value.transactions.size)
        val rows = mutableListOf<TransactionDto>()
        var hasMore = true
        while (hasMore && rows.size < wanted) {
            val limit = (wanted - rows.size).coerceIn(PAGE_SIZE, SERVER_MAX_PAGE)
            val page = api.transactions(walletId, filter, rows.size, limit)
            rows += page
            hasMore = page.size == limit
        }
        _state.update { s ->
            if (generation != txnGeneration || s.filter != filter) s else s.copy(transactions = rows.distinctBy { it.id }, hasMore = hasMore)
        }
    }

    fun setCategory(txn: TransactionDto, categoryId: Long) = launchTask {
        api.setCategory(txn.id, categoryId)
        // The server applies the decision to other pending transactions of the same merchant too,
        // so reload everything currently shown.
        val w = _state.value.wallet?.takeIf { it.id == txn.walletId } ?: return@launchTask
        val review = api.review(w.id)
        updateWallet(w.id) { it.copy(review = review) }
        if (_state.value.wallet?.id != w.id) return@launchTask
        if (_state.value.transactions.isNotEmpty()) launchTxnLoad { gen -> refetchLoaded(w.id, gen) }.join()
        if (_state.value.stats != null) fetchStats(w.id)
    }

    // --- statistics ---

    fun setStatsPeriod(period: StatsPeriod) {
        // The old figures belong to another period; drop them so a failed load can't leave them on screen.
        _state.update { it.copy(statsPeriod = period, stats = null) }
        val wallet = _state.value.wallet ?: return
        launchTask { fetchStats(wallet.id) }
    }

    private suspend fun fetchStats(walletId: Long) {
        val period = _state.value.statsPeriod
        val stats = api.stats(walletId, period.from, period.to)
        updateWallet(walletId) { if (it.statsPeriod == period) it.copy(stats = stats) else it }
    }

    /** Drill-down from a statistics row: that category's transactions over the same period. */
    fun showCategoryTransactions(categoryId: Long?) {
        val period = _state.value.statsPeriod
        _state.update {
            it.copy(
                tab = WalletTab.TRANSACTIONS,
                filter = TxnFilter(categoryId = categoryId, uncategorized = categoryId == null, period = DateRange(period.from, period.to)),
                transactions = emptyList(),
            )
        }
        searchInput.value = ""
        reloadTransactions()
    }

    // --- sharing & banks ---

    fun addMember(email: String) {
        val wallet = _state.value.wallet ?: return
        launchTask {
            val m = api.addMember(wallet.id, email.trim())
            _state.update { it.copy(message = "${m.name} added to ${wallet.name}") }
        }
    }

    fun loadAspsps(country: String) = launchTask {
        val list = api.aspsps(country.trim().uppercase())
        _state.update { it.copy(aspsps = list) }
    }

    fun connectBank(aspsp: AspspDto) {
        val wallet = _state.value.wallet ?: return
        launchTask { openUrl(api.connectBank(wallet.id, aspsp.name, aspsp.country).authUrl) }
    }

    fun reauthorize(connection: BankConnectionDto) = launchTask { openUrl(api.reauthorize(connection.id).authUrl) }

    fun sync(connection: BankConnectionDto) = launchTask {
        val r = api.sync(connection.id)
        _state.update { it.copy(message = r.error ?: "Fetched ${r.fetched}, new ${r.inserted}") }
        _state.value.wallet?.let { w -> api.connections(w.id).let { c -> updateWallet(w.id) { it.copy(connections = c) } } }
    }

    fun removeConnection(connection: BankConnectionDto) = launchTask {
        api.deleteConnection(connection.id)
        _state.update { s -> s.copy(connections = s.connections.filter { it.id != connection.id }, message = "Removed ${connection.aspspName}") }
    }

    /** Deep link from the backend after bank authorisation. */
    fun onBankRedirect(uri: Uri) {
        val ok = uri.getQueryParameter("status") == "ok"
        _state.update {
            it.copy(
                tab = WalletTab.BANKS,
                message = if (ok) "Bank connected; first sync is running" else "Bank connection failed: ${uri.getQueryParameter("message") ?: "unknown error"}",
            )
        }
        refreshWallet()
    }
}

private const val PAGE_SIZE = 100
/** The backend's limit on one transactions page. */
private const val SERVER_MAX_PAGE = 1000
private const val SEARCH_DEBOUNCE_MS = 300L
