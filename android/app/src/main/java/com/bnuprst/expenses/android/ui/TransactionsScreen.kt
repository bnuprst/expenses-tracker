package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.DateRange
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.TxnFilter
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.TransactionDto
import com.bnuprst.expenses.shared.TxnSort
import com.bnuprst.expenses.shared.TxnStatus
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TransactionsScreen(state: UiState, vm: AppViewModel, onPick: (TransactionDto) -> Unit) {
    val listState = rememberLazyListState()
    // A new search or filter starts from the top.
    val filterKey = state.filter.copy(query = "")
    LaunchedEffect(filterKey, state.filter.query) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        SearchField(state.filter.query, vm::setQuery, "Search transactions", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        FilterRow(state.filter, state.categories, vm::setFilter)
        val empty: @Composable () -> Unit = {
            when {
                state.loading -> Unit
                state.filter.isActive ->
                    EmptyState(painterResource(R.drawable.ic_receipt_long), "No matching transactions", "Try another search or clear the filters.")
                else ->
                    EmptyState(painterResource(R.drawable.ic_receipt_long), "No transactions yet", "Connect a bank in the Banks tab to start syncing.")
            }
        }
        TransactionList(
            items = state.transactions,
            categories = state.categories,
            listState = listState,
            groupByDate = state.filter.sort == TxnSort.DATE_DESC || state.filter.sort == TxnSort.DATE_ASC,
            hasMore = state.hasMore,
            loadingMore = state.loadingMore,
            onLoadMore = vm::loadMore,
            empty = empty,
            onClick = onPick,
        )
    }
}

@Composable
fun ReviewScreen(state: UiState, onPick: (TransactionDto) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (state.review.isNotEmpty()) {
            Text(
                "Confirm suggested categories or pick one. Your choice also applies to other transactions from the same merchant.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        TransactionList(
            items = state.review, categories = state.categories, listState = rememberLazyListState(), groupByDate = true,
            empty = { if (!state.loading) EmptyState(painterResource(R.drawable.ic_inbox), "All caught up", "Nothing needs your review.") },
            onClick = onPick,
        )
    }
}

// --- filters ---

private enum class PeriodPreset(val label: String) {
    THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_3_MONTHS("Last 3 months"), THIS_YEAR("This year");

    fun range(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        THIS_MONTH -> DateRange(today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth())
        LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
        LAST_3_MONTHS -> DateRange(YearMonth.from(today).minusMonths(2).atDay(1), YearMonth.from(today).atEndOfMonth())
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))
    }
}

private val TxnSort.label get() = when (this) {
    TxnSort.DATE_DESC -> "Newest first"
    TxnSort.DATE_ASC -> "Oldest first"
    TxnSort.AMOUNT_ASC -> "Largest expense"
    TxnSort.AMOUNT_DESC -> "Largest income"
}

private val shortDate = DateTimeFormatter.ofPattern("d MMM")
private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy")

fun DateRange.label(): String {
    PeriodPreset.entries.firstOrNull { it.range() == this }?.let { return it.label }
    val ym = YearMonth.from(from)
    if (from == ym.atDay(1) && to == ym.atEndOfMonth()) return from.format(DateTimeFormatter.ofPattern("LLLL yyyy"))
    if (from.dayOfYear == 1 && to == from.withDayOfYear(from.lengthOfYear())) return from.year.toString()
    return if (from.year == to.year) "${from.format(shortDate)} – ${to.format(longDate)}" else "${from.format(longDate)} – ${to.format(longDate)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(filter: TxnFilter, categories: List<CategoryDto>, onChange: (TxnFilter) -> Unit) {
    var pickingCategory by rememberSaveable { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var customRange by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val categoryName = when {
            filter.uncategorized -> "Uncategorized"
            filter.categoryId != null -> categories.firstOrNull { it.id == filter.categoryId }?.name ?: "Category"
            else -> null
        }
        FilterChip(
            selected = categoryName != null,
            onClick = { pickingCategory = true },
            label = { Text(categoryName ?: "Category") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
        Box {
            FilterChip(
                selected = filter.period != null,
                onClick = { periodMenu = true },
                label = { Text(filter.period?.label() ?: "Any time") },
                leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                DropdownMenuItem(text = { Text("Any time") }, onClick = { periodMenu = false; onChange(filter.copy(period = null)) },
                    trailingIcon = { if (filter.period == null) Icon(Icons.Default.Check, null) })
                PeriodPreset.entries.forEach { p ->
                    val range = p.range()
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { periodMenu = false; onChange(filter.copy(period = range)) },
                        trailingIcon = { if (filter.period == range) Icon(Icons.Default.Check, null) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Custom range…") }, onClick = { periodMenu = false; customRange = true })
            }
        }
        Box {
            FilterChip(
                selected = filter.sort != TxnSort.DATE_DESC,
                onClick = { sortMenu = true },
                label = { Text(filter.sort.label) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                TxnSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(s.label) }, onClick = { sortMenu = false; onChange(filter.copy(sort = s)) },
                        trailingIcon = { if (filter.sort == s) Icon(Icons.Default.Check, null) })
                }
            }
        }
        if (filter.isActive) {
            TextButton(onClick = { onChange(TxnFilter()) }) {
                Icon(Icons.Default.Close, null, Modifier.size(FilterChipDefaults.IconSize))
                Text("Clear", Modifier.padding(start = 4.dp))
            }
        }
    }

    if (pickingCategory) {
        CategoryPickerSheet(
            title = "Filter by category",
            categories = categories,
            selectedId = filter.categoryId,
            uncategorizedSelected = filter.uncategorized,
            forFilter = true,
            onDismiss = { pickingCategory = false },
            onPick = { choice ->
                pickingCategory = false
                onChange(
                    when (choice) {
                        CategoryChoice.Any -> filter.copy(categoryId = null, uncategorized = false)
                        CategoryChoice.Uncategorized -> filter.copy(categoryId = null, uncategorized = true)
                        is CategoryChoice.Category -> filter.copy(categoryId = choice.category.id, uncategorized = false)
                    }
                )
            },
        )
    }

    if (customRange) {
        fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        fun Long.date() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.period?.from?.millis(),
            initialSelectedEndDateMillis = filter.period?.to?.millis(),
        )
        DatePickerDialog(
            onDismissRequest = { customRange = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedStartDateMillis != null,
                    onClick = {
                        val start = pickerState.selectedStartDateMillis!!.date()
                        val end = pickerState.selectedEndDateMillis?.date() ?: start
                        customRange = false
                        onChange(filter.copy(period = DateRange(start, end)))
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { customRange = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(pickerState, Modifier.weight(1f), title = { Text("Select dates", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

// --- list ---

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionList(
    items: List<TransactionDto>,
    categories: List<CategoryDto>,
    listState: LazyListState,
    groupByDate: Boolean,
    empty: @Composable () -> Unit,
    onClick: (TransactionDto) -> Unit,
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    if (items.isEmpty()) {
        empty()
        return
    }
    val byId = remember(categories) { categories.associateBy { it.id } }
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, items.size, hasMore) { if (nearEnd && hasMore) onLoadMore() }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (groupByDate) {
            items.groupBy { it.bookingDate }.forEach { (date, dayItems) ->
                stickyHeader(key = "h-$date") { DayHeader(LocalDate.parse(date)) }
                items(dayItems, key = { it.id }) { t -> TransactionRow(t, byId, showDate = false, onClick) }
            }
        } else {
            items(items, key = { it.id }) { t -> TransactionRow(t, byId, showDate = true, onClick) }
        }
        if (hasMore || loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            formatDay(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun TransactionRow(t: TransactionDto, categories: Map<Long, CategoryDto>, showDate: Boolean, onClick: (TransactionDto) -> Unit) {
    val category = t.categoryId?.let { categories[it] }
    // Money in names the payer ("From ACME Oy"); money out keeps just the payee, as most rows are purchases.
    val title = (if (t.isIncoming()) t.partyLabel() else t.counterparty) ?: t.description
    val amount = t.amount.toBigDecimalOrNull()
    val amountColors = LocalAmountColors.current
    ListItem(
        modifier = Modifier.clickable { onClick(t) },
        leadingContent = { CategoryAvatar(category?.name ?: title, if (category != null) categoryColor(category.id) else MaterialTheme.colorScheme.outline) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val (text, color) = when (t.status) {
                TxnStatus.REVIEW -> "Suggested: ${category?.name ?: "?"} · tap to confirm" to MaterialTheme.colorScheme.tertiary
                TxnStatus.UNCATEGORIZED -> "Uncategorized · tap to choose" to MaterialTheme.colorScheme.error
                TxnStatus.CATEGORIZED -> (category?.name ?: "Uncategorized") to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val date = if (showDate) "${formatDay(LocalDate.parse(t.bookingDate))} · " else ""
            Text(date + text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(
                formatMoney(t.amount, t.currency, showPlus = true),
                style = MaterialTheme.typography.titleSmall,
                color = if (amount != null && amount.signum() > 0) amountColors.income else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

private const val LOAD_MORE_THRESHOLD = 20
