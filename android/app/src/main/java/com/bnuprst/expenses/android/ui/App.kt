package com.bnuprst.expenses.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.android.WalletTab
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    !state.loggedIn -> LoginScreen(state, vm)
                    state.wallet == null -> WalletsScreen(state, vm)
                    else -> WalletScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: UiState, vm: AppViewModel) {
    var server by remember { mutableStateOf(state.serverUrl.ifEmpty { "https://" }) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (register) "Create account" else "Sign in", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (register) OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(onClick = { vm.authenticate(server, email, password, name, register) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "Register" else "Sign in")
        }
        TextButton(onClick = { register = !register }) { Text(if (register) "Have an account? Sign in" else "New here? Create an account") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletsScreen(state: UiState, vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EUR") }
    Column {
        TopAppBar(
            title = { Text("Wallets") },
            actions = {
                IconButton(onClick = vm::loadWallets) { Icon(Icons.Default.Refresh, "Refresh") }
                TextButton(onClick = vm::logout) { Text("Sign out") }
            },
        )
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.wallets, key = { it.id }) { w ->
                Card(Modifier.fillMaxWidth().clickable { vm.openWallet(w) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(w.name, style = MaterialTheme.typography.titleMedium)
                        Text("${w.currency} · ${w.role.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New wallet", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(currency, { currency = it.take(3).uppercase() }, label = { Text("Cur.") }, singleLine = true, modifier = Modifier.width(80.dp))
                }
                Button(onClick = { vm.createWallet(name, currency); name = "" }, enabled = name.isNotBlank()) { Text("Create") }
              }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletScreen(state: UiState, vm: AppViewModel) {
    val wallet = state.wallet ?: return
    BackHandler { vm.openWallet(null) }
    var picking by remember { mutableStateOf<TransactionDto?>(null) }
    Column {
        TopAppBar(
            title = { Text(wallet.name) },
            navigationIcon = { IconButton(onClick = { vm.openWallet(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = vm::refreshWallet) { Icon(Icons.Default.Refresh, "Refresh") } },
        )
        TabRow(selectedTabIndex = state.tab.ordinal) {
            WalletTab.entries.forEach { tab ->
                Tab(selected = state.tab == tab, onClick = { vm.selectTab(tab) }, text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        when (state.tab) {
            WalletTab.TRANSACTIONS -> TransactionList(state.transactions, state.categories, empty = "No transactions yet") { picking = it }
            WalletTab.REVIEW -> TransactionList(state.review, state.categories, empty = "Nothing to review 🎉") { picking = it }
            WalletTab.BANKS -> BanksTab(state, vm)
        }
    }
    picking?.let { txn ->
        CategoryPicker(txn, state.categories, onDismiss = { picking = null }) { cat ->
            vm.setCategory(txn, cat.id)
            picking = null
        }
    }
}

@Composable
private fun TransactionList(items: List<TransactionDto>, categories: List<CategoryDto>, empty: String, onClick: (TransactionDto) -> Unit) {
    if (items.isEmpty()) {
        Text(empty, Modifier.padding(16.dp))
        return
    }
    val byId = categories.associateBy { it.id }
    LazyColumn {
        items(items, key = { it.id }) { t ->
          Column {
            Row(Modifier.fillMaxWidth().clickable { onClick(t) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.counterparty ?: t.description, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    val cat = t.categoryId?.let { byId[it]?.name } ?: "Uncategorized"
                    val suffix = when (t.status) {
                        TxnStatus.REVIEW -> " · suggested, tap to confirm"
                        else -> t.categorySource?.let { " · ${it.name.lowercase()}" } ?: ""
                    }
                    Text("${t.bookingDate} · $cat$suffix", style = MaterialTheme.typography.bodySmall,
                        color = if (t.status == TxnStatus.CATEGORIZED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                val negative = t.amount.startsWith("-")
                Text("${t.amount} ${t.currency}", color = if (negative) MaterialTheme.colorScheme.onSurface else Color(0xFF2E7D32))
            }
            HorizontalDivider()
          }
        }
    }
}

@Composable
private fun CategoryPicker(txn: TransactionDto, categories: List<CategoryDto>, onDismiss: () -> Unit, onPick: (CategoryDto) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(txn.counterparty ?: txn.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn {
                items(categories, key = { it.id }) { c ->
                    val current = c.id == txn.categoryId
                    Text(
                        (if (current) "✓ " else "") + c.name,
                        Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp),
                        style = if (current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
    )
}

@Composable
private fun BanksTab(state: UiState, vm: AppViewModel) {
    var country by remember { mutableStateOf("FI") }
    var memberEmail by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.connections, key = { it.id }) { c -> ConnectionCard(c, vm) }
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect a bank", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Country") }, singleLine = true, modifier = Modifier.width(100.dp))
                OutlinedButton(onClick = { vm.loadAspsps(country) }) { Text("Find banks") }
            }
          }
        }
        items(state.aspsps, key = { it.country + it.name }) { a ->
            Column {
                Text(a.name, Modifier.fillMaxWidth().clickable { vm.connectBank(a) }.padding(vertical = 10.dp))
                HorizontalDivider()
            }
        }
        if (state.wallet?.role == com.bnuprst.expenses.shared.WalletRole.OWNER) {
            item {
              Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share wallet", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(memberEmail, { memberEmail = it }, label = { Text("Family member's email") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.addMember(memberEmail); memberEmail = "" }, enabled = memberEmail.contains('@')) { Text("Add") }
                }
              }
            }
        }
    }
}

@Composable
private fun ConnectionCard(c: BankConnectionDto, vm: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${c.aspspName} (${c.country})", style = MaterialTheme.typography.titleMedium)
            Text("Status: ${c.status.name.lowercase()}" + (c.validUntil?.let { " · consent until ${it.take(10)}" } ?: ""))
            c.accounts.forEach { a -> Text("• ${a.name ?: "Account"} ${a.ibanMasked.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            c.lastSyncAt?.let { Text("Last sync ${it.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) }
            c.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            if (c.renewSoon) Text("Consent expires soon; renew to keep syncing", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.status == ConnectionStatus.ACTIVE) OutlinedButton(onClick = { vm.sync(c) }) { Text("Sync now") }
                if (c.renewSoon || c.status != ConnectionStatus.ACTIVE) Button(onClick = { vm.reauthorize(c) }) { Text("Renew consent") }
            }
        }
    }
}
