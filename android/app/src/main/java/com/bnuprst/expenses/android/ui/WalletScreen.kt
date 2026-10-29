package com.bnuprst.expenses.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.android.WalletTab
import com.bnuprst.expenses.shared.TransactionDto

private val WalletTab.label get() = when (this) {
    WalletTab.TRANSACTIONS -> "Transactions"
    WalletTab.REVIEW -> "Review"
    WalletTab.STATS -> "Stats"
    WalletTab.BANKS -> "Banks"
}

private val WalletTab.icon get() = when (this) {
    WalletTab.TRANSACTIONS -> R.drawable.ic_receipt_long
    WalletTab.REVIEW -> R.drawable.ic_inbox
    WalletTab.STATS -> R.drawable.ic_bar_chart
    WalletTab.BANKS -> R.drawable.ic_account_balance
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(state: UiState, vm: AppViewModel) {
    val wallet = state.wallet ?: return
    BackHandler { if (state.tab != WalletTab.TRANSACTIONS) vm.selectTab(WalletTab.TRANSACTIONS) else vm.openWallet(null) }
    var picking by remember { mutableStateOf<TransactionDto?>(null) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(wallet.name, style = MaterialTheme.typography.titleLarge)
                            Text(state.tab.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { vm.openWallet(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "All wallets") } },
                )
                TopProgress(state.loading && !state.refreshing && !state.loadingMore)
            }
        },
        bottomBar = {
            NavigationBar {
                WalletTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        icon = {
                            val count = state.review.size
                            if (tab == WalletTab.REVIEW && count > 0) {
                                BadgedBox(badge = { Badge { Text(if (count > 99) "99+" else count.toString()) } }) { Icon(painterResource(tab.icon), null) }
                            } else {
                                Icon(painterResource(tab.icon), null)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (state.tab) {
                WalletTab.TRANSACTIONS -> TransactionsScreen(state, vm) { picking = it }
                WalletTab.REVIEW -> ReviewScreen(state) { picking = it }
                WalletTab.STATS -> StatsScreen(state, vm)
                WalletTab.BANKS -> BanksScreen(state, vm)
            }
        }
    }
    picking?.let { txn ->
        CategoryPickerSheet(
            title = txn.counterparty ?: txn.description,
            subtitle = "${formatMoney(txn.amount, txn.currency)} · ${formatDay(java.time.LocalDate.parse(txn.bookingDate))}",
            categories = state.categories,
            selectedId = txn.categoryId,
            onDismiss = { picking = null },
            onPick = { choice ->
                if (choice is CategoryChoice.Category) vm.setCategory(txn, choice.category.id)
                picking = null
            },
        )
    }
}
