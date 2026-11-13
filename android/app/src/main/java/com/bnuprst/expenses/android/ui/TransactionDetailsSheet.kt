package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategorySource
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnStatus
import java.time.LocalDate

/** "From ACME Oy" for money in, "To K-Market" for money out; null when the bank named no one. */
@Composable
fun TransactionDto.partyLabel(): String? = counterparty?.let {
    if (isIncoming()) stringResource(R.string.party_from, it) else stringResource(R.string.party_to, it)
}

fun TransactionDto.isIncoming() = (amount.toBigDecimalOrNull()?.signum() ?: 0) > 0

private fun formatIban(iban: String) = iban.chunked(4).joinToString(" ")

@Composable
private fun CategorySource.label(confidence: Double?): String = when (this) {
    CategorySource.MANUAL -> stringResource(R.string.source_manual)
    CategorySource.MEMORY -> stringResource(R.string.source_memory)
    CategorySource.RULE -> stringResource(R.string.source_rule)
    CategorySource.LLM -> confidence?.let { stringResource(R.string.source_llm_confidence, (it * 100).toInt()) } ?: stringResource(R.string.source_llm)
}

/**
 * Everything known about one transaction, with its category actions: confirm a pending suggestion, or
 * pick another category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailsSheet(
    txn: TransactionDto,
    categories: List<CategoryDto>,
    onDismiss: () -> Unit,
    onConfirm: (categoryId: Long) -> Unit,
    onChangeCategory: () -> Unit,
) {
    val category = txn.categoryId?.let { id -> categories.firstOrNull { it.id == id } }
    val categoryName = category?.let { categoryDisplayName(it) }
    val booked = LocalDate.parse(txn.bookingDate)
    val incoming = txn.isIncoming()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryAvatar(categoryName ?: txn.counterparty ?: txn.description, if (category != null) categoryColor(category.id) else MaterialTheme.colorScheme.outline, 56.dp)
                Text(
                    formatMoney(txn.amount, txn.currency, showPlus = true),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (incoming) LocalAmountColors.current.income else MaterialTheme.colorScheme.onSurface,
                )
                Text(txn.counterparty ?: txn.description, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(if (incoming) R.string.money_in else R.string.money_out),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Name and IBAN are independently optional; show whichever the bank sent.
                    listOfNotNull(txn.counterparty, txn.counterpartyIban?.let(::formatIban)).takeIf { it.isNotEmpty() }?.let { party ->
                        Detail(stringResource(if (incoming) R.string.detail_from else R.string.detail_to), party.joinToString("\n"))
                    }
                    if (txn.description != txn.counterparty) Detail(stringResource(R.string.detail_description), txn.description)
                    txn.paymentType?.let { Detail(stringResource(R.string.detail_type), it) }
                    txn.transactionDate?.let(LocalDate::parse)?.takeIf { it != booked }?.let { Detail(stringResource(R.string.detail_purchased), formatDay(it)) }
                    Detail(stringResource(R.string.detail_booked), formatDay(booked))
                    txn.valueDate?.let(LocalDate::parse)?.takeIf { it != booked }?.let { Detail(stringResource(R.string.detail_value_date), formatDay(it)) }
                    txn.account?.let { Detail(stringResource(R.string.detail_account), it) }
                    Detail(
                        stringResource(R.string.detail_category),
                        when (txn.status) {
                            TxnStatus.UNCATEGORIZED -> stringResource(R.string.category_uncategorized)
                            TxnStatus.REVIEW -> stringResource(
                                R.string.category_suggested_unconfirmed,
                                category?.let { categoryDisplayName(it) } ?: stringResource(R.string.label_unknown),
                            )
                            TxnStatus.CATEGORIZED -> categoryName ?: stringResource(R.string.category_uncategorized)
                        } + (txn.categorySource?.let { "\n" + it.label(txn.confidence) } ?: ""),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (txn.status == TxnStatus.REVIEW && category != null) {
                    Button(onClick = { onConfirm(category.id) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_confirm_category, categoryDisplayName(category))) }
                    OutlinedButton(onClick = onChangeCategory, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_choose_another_category)) }
                } else {
                    FilledTonalButton(onClick = onChangeCategory, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (txn.status == TxnStatus.UNCATEGORIZED) R.string.action_choose_category else R.string.action_change_category))
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
