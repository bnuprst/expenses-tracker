package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.shared.WalletRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletsScreen(state: UiState, vm: AppViewModel) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var creating by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var language by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text(if (state.userName.isNotBlank()) stringResource(R.string.wallets_greeting, state.userName) else stringResource(R.string.wallets_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = vm::loadWallets) { Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_sign_out)) },
                                onClick = { menu = false; vm.logout() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_language)) },
                                onClick = { menu = false; language = true },
                            )
                        }
                    },
                    scrollBehavior = scroll,
                )
                TopProgress(state.loading)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.wallets_new)) },
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        if (state.wallets.isEmpty() && !state.loading) {
            EmptyState(
                painterResource(R.drawable.ic_wallet), stringResource(R.string.wallets_empty_title),
                stringResource(R.string.wallets_empty_body),
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Dims.Screen, end = Dims.Screen, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + Dims.FabClearance),
                verticalArrangement = Arrangement.spacedBy(Dims.ItemGap),
            ) {
                item { Text(stringResource(R.string.wallets_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(state.wallets, key = { it.id }) { w ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { vm.openWallet(w) }) {
                        ListItem(
                            headlineContent = { Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(R.string.wallet_subtitle, w.currency, stringResource(if (w.role == WalletRole.OWNER) R.string.role_owner else R.string.role_member)), maxLines = 1) },
                            leadingContent = {
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Icon(painterResource(R.drawable.ic_wallet), null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
    if (creating) NewWalletDialog(onDismiss = { creating = false }) { name, currency ->
        creating = false
        vm.createWallet(name, currency)
    }
    if (language) LanguageDialog { language = false }
}

@Composable
private fun NewWalletDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("EUR") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_wallet), null) },
        title = { Text(stringResource(R.string.wallets_new)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(currency, { currency = it.take(3).uppercase() }, label = { Text(stringResource(R.string.field_currency)) }, singleLine = true, modifier = Modifier.width(Dims.CurrencyField))
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), currency) }, enabled = name.isNotBlank() && currency.length == 3) { Text(stringResource(R.string.action_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
