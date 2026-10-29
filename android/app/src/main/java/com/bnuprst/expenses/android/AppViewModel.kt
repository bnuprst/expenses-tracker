package com.bnuprst.expenses.android

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnuprst.expenses.shared.AspspDto
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.WalletDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WalletTab { TRANSACTIONS, REVIEW, BANKS }

data class UiState(
    val serverUrl: String = "",
    val loggedIn: Boolean = false,
    val userName: String = "",
    val loading: Boolean = false,
    val message: String? = null,
    val wallets: List<WalletDto> = emptyList(),
    val wallet: WalletDto? = null,
    val tab: WalletTab = WalletTab.TRANSACTIONS,
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val review: List<TransactionDto> = emptyList(),
    val connections: List<BankConnectionDto> = emptyList(),
    val aspsps: List<AspspDto> = emptyList(),
)

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

    init {
        if (_state.value.loggedIn) loadWallets()
    }

    private fun launchTask(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                block()
            } catch (e: Exception) {
                if (e.message == "Unauthorized") logout()
                _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
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
        _state.update { it.copy(wallet = wallet, tab = WalletTab.TRANSACTIONS, transactions = emptyList(), review = emptyList(), connections = emptyList()) }
        if (wallet != null) refreshWallet()
    }

    fun selectTab(tab: WalletTab) {
        _state.update { it.copy(tab = tab) }
        refreshWallet()
    }

    fun refreshWallet() {
        val wallet = _state.value.wallet ?: return
        launchTask {
            val categories = api.categories(wallet.id)
            _state.update { it.copy(categories = categories) }
            when (_state.value.tab) {
                WalletTab.TRANSACTIONS -> api.transactions(wallet.id).let { t -> _state.update { it.copy(transactions = t) } }
                WalletTab.REVIEW -> api.review(wallet.id).let { r -> _state.update { it.copy(review = r) } }
                WalletTab.BANKS -> api.connections(wallet.id).let { c -> _state.update { it.copy(connections = c) } }
            }
        }
    }

    fun setCategory(txn: TransactionDto, categoryId: Long) = launchTask {
        api.setCategory(txn.id, categoryId)
        // The server applies the decision to other pending transactions of the same merchant too.
        _state.value.wallet?.let { w ->
            val transactions = api.transactions(w.id)
            val review = api.review(w.id)
            _state.update { it.copy(transactions = transactions, review = review) }
        }
    }

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
        _state.value.wallet?.let { w -> api.connections(w.id).let { c -> _state.update { it.copy(connections = c) } } }
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
