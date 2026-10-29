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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.shared.BankConnectionDto
import com.bnuprst.expenses.shared.ConnectionStatus
import com.bnuprst.expenses.shared.WalletRole

@Composable
fun BanksScreen(state: UiState, vm: AppViewModel) {
    var country by rememberSaveable { mutableStateOf("PL") }
    var bankQuery by rememberSaveable { mutableStateOf("") }
    var memberEmail by rememberSaveable { mutableStateOf("") }
    val banks = state.aspsps.filter { bankQuery.isBlank() || it.name.contains(bankQuery.trim(), ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Connected banks") }
        if (state.connections.isEmpty()) {
            item {
                Text(
                    if (state.loading) "Loading…" else "No banks connected yet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.connections, key = { it.id }) { c -> ConnectionCard(c, vm) }

        item { SectionTitle("Connect a bank", Modifier.padding(top = 12.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text("Country") }, singleLine = true, modifier = Modifier.width(110.dp))
                FilledTonalButton(onClick = { bankQuery = ""; vm.loadAspsps(country) }, enabled = country.length == 2) { Text("Find banks") }
            }
        }
        if (state.aspsps.size > 8) item { SearchField(bankQuery, { bankQuery = it }, "Search ${state.aspsps.size} banks") }
        if (banks.isNotEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    banks.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider()
                        ListItem(
                            headlineContent = { Text(a.name) },
                            supportingContent = a.maxConsentDays?.let { { Text("Consent up to $it days") } },
                            leadingContent = { CategoryAvatar(a.name, MaterialTheme.colorScheme.primary, 36.dp) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.connectBank(a) },
                        )
                    }
                }
            }
        }

        if (state.wallet?.role == WalletRole.OWNER) {
            item { SectionTitle("Share wallet", Modifier.padding(top = 12.dp)) }
            item {
                Text(
                    "Add a family member who already has an account on this server.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(memberEmail, { memberEmail = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.addMember(memberEmail); memberEmail = "" }, enabled = memberEmail.contains('@')) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Text("Add", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
private fun StatusPill(status: ConnectionStatus) {
    val (bg, fg) = when (status) {
        ConnectionStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.EXPIRED, ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp)) {
        Text(status.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun ConnectionCard(c: BankConnectionDto, vm: AppViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeConnection(c) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
            title = { Text("Remove ${c.aspspName}?") },
            text = {
                Text(
                    if (c.accounts.isEmpty()) "This connection was never authorised."
                    else "Syncing stops. Transactions already imported are kept."
                )
            },
        )
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(c.aspspName, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(c.country + (c.validUntil?.let { " · consent until ${it.take(10)}" } ?: "")) },
            leadingContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(painterResource(R.drawable.ic_account_balance), null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            },
            trailingContent = { StatusPill(c.status) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            c.accounts.forEach { a ->
                Text("${a.name ?: "Account"} ${a.ibanMasked.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            }
            c.lastSyncAt?.let {
                Text("Last sync ${it.take(16).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            c.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            if (c.renewSoon) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Consent expires soon; renew to keep syncing", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
                if (c.status == ConnectionStatus.ACTIVE) FilledTonalButton(onClick = { vm.sync(c) }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Text("Sync now", Modifier.padding(start = 8.dp))
                }
                if (c.renewSoon || c.status != ConnectionStatus.ACTIVE) Button(onClick = { vm.reauthorize(c) }) { Text("Renew consent") }
            }
        }
    }
}
