package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dims.Screen, end = Dims.Screen, top = Dims.Screen, bottom = Dims.Screen + Dims.ListBottom),
        verticalArrangement = Arrangement.spacedBy(Dims.ItemGap),
    ) {
        item { SectionTitle(stringResource(R.string.banks_connected_title)) }
        if (state.connections.isEmpty()) {
            item {
                Text(
                    stringResource(if (state.loading) R.string.label_loading else R.string.banks_none_connected),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.connections, key = { it.id }) { c -> ConnectionCard(c, vm) }

        item { SectionTitle(stringResource(R.string.banks_connect_title), Modifier.padding(top = Dims.SectionGap - Dims.ItemGap)) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country, { country = it.take(2).uppercase() }, label = { Text(stringResource(R.string.field_country)) }, singleLine = true, modifier = Modifier.width(Dims.CountryField))
                FilledTonalButton(
                    onClick = { bankQuery = ""; vm.loadAspsps(country) },
                    enabled = country.length == 2,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = Dims.Control),
                ) { Text(stringResource(R.string.action_find_banks), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (state.aspsps.size > 8) item { SearchField(bankQuery, { bankQuery = it }, pluralStringResource(R.plurals.banks_search_hint, state.aspsps.size, state.aspsps.size)) }
        if (banks.isNotEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    banks.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = Dims.ListDividerInset))
                        ListItem(
                            headlineContent = { Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = a.maxConsentDays?.let { days -> { Text(pluralStringResource(R.plurals.banks_consent_days, days, days)) } },
                            leadingContent = { CategoryAvatar(a.name, MaterialTheme.colorScheme.primary, Dims.AvatarSmall) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.connectBank(a) },
                        )
                    }
                }
            }
        }

        if (state.wallet?.role == WalletRole.OWNER) {
            item { SectionTitle(stringResource(R.string.banks_share_title), Modifier.padding(top = Dims.SectionGap - Dims.ItemGap)) }
            item {
                Text(
                    stringResource(R.string.banks_share_body),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(memberEmail, { memberEmail = it }, label = { Text(stringResource(R.string.field_email)) }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { vm.addMember(memberEmail); memberEmail = "" },
                        enabled = memberEmail.contains('@'),
                        modifier = Modifier.defaultMinSize(minHeight = Dims.Control),
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(Dims.ButtonIcon))
                        Text(stringResource(R.string.action_add), Modifier.padding(start = 8.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier, maxLines = 2)
}

@Composable
private fun StatusPill(status: ConnectionStatus) {
    val (bg, fg) = when (status) {
        ConnectionStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.PENDING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.EXPIRED, ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        val labelRes = when (status) {
            ConnectionStatus.ACTIVE -> R.string.status_active
            ConnectionStatus.PENDING -> R.string.status_pending
            ConnectionStatus.EXPIRED -> R.string.status_expired
            ConnectionStatus.ERROR -> R.string.status_error
        }
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(c: BankConnectionDto, vm: AppViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeConnection(c) }) { Text(stringResource(R.string.action_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.banks_remove_title, c.aspspName)) },
            text = {
                Text(
                    stringResource(if (c.accounts.isEmpty()) R.string.banks_remove_never_authorised else R.string.banks_remove_body)
                )
            },
        )
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(c.aspspName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(c.validUntil?.let { stringResource(R.string.banks_country_consent, c.country, it.take(10)) } ?: c.country, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(painterResource(R.drawable.ic_account_balance), null, Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            },
            trailingContent = { StatusPill(c.status) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Column(Modifier.padding(start = Dims.Screen, end = Dims.Screen, bottom = Dims.ItemGap), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (c.accounts.isNotEmpty()) {
                Text(stringResource(R.string.banks_accounts_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            c.accounts.forEach { a ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.name ?: stringResource(R.string.banks_account_fallback),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    a.ibanMasked?.let {
                        Text(
                            it, Modifier.widthIn(max = Dims.IbanMax),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                        )
                    }
                }
            }
            c.lastSyncAt?.let {
                Text(stringResource(R.string.banks_last_sync, formatDateTime(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            c.lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            if (c.renewSoon) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, null, Modifier.size(Dims.InlineIcon), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.banks_renew_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.action_remove), maxLines = 1) }
                if (c.status == ConnectionStatus.ACTIVE) FilledTonalButton(onClick = { vm.sync(c) }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(Dims.ButtonIcon))
                    Text(stringResource(R.string.action_sync_now), Modifier.padding(start = 8.dp), maxLines = 1)
                }
                if (c.renewSoon || c.status != ConnectionStatus.ACTIVE) Button(onClick = { vm.reauthorize(c) }) {
                    Text(stringResource(R.string.action_renew_consent), maxLines = 1)
                }
            }
        }
    }
}
