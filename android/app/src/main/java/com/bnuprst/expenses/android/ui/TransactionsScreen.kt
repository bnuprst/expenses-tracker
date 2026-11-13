package com.bnuprst.expenses.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import java.util.Locale

@Composable
fun TransactionsScreen(state: UiState, vm: AppViewModel, onPick: (TransactionDto) -> Unit) {
    val listState = rememberLazyListState()
    // A new search or filter starts from the top.
    val filterKey = state.filter.copy(query = "")
    LaunchedEffect(filterKey, state.filter.query) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        SearchField(state.filter.query, vm::setQuery, stringResource(R.string.search_transactions), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        FilterRow(state.filter, state.categories, vm::setFilter)
        val empty: @Composable () -> Unit = {
            when {
                state.loading -> Unit
                state.filter.isActive ->
                    EmptyState(painterResource(R.drawable.ic_receipt_long), stringResource(R.string.txn_empty_filtered_title), stringResource(R.string.txn_empty_filtered_body))
                else ->
                    EmptyState(painterResource(R.drawable.ic_receipt_long), stringResource(R.string.txn_empty_title), stringResource(R.string.txn_empty_body))
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
            showDailyMovements = true,
            baseCurrency = state.wallet?.currency,
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
                stringResource(R.string.review_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        TransactionList(
            items = state.review, categories = state.categories, listState = rememberLazyListState(), groupByDate = true,
            empty = { if (!state.loading) EmptyState(painterResource(R.drawable.ic_inbox), stringResource(R.string.review_empty_title), stringResource(R.string.review_empty_body)) },
            onClick = onPick,
        )
    }
}

// --- filters ---

private enum class PeriodPreset(@StringRes val labelRes: Int) {
    THIS_MONTH(R.string.period_this_month),
    LAST_MONTH(R.string.period_last_month),
    LAST_3_MONTHS(R.string.period_last_3_months),
    THIS_YEAR(R.string.period_this_year);

    fun range(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        THIS_MONTH -> DateRange(today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth())
        LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { DateRange(it.atDay(1), it.atEndOfMonth()) }
        LAST_3_MONTHS -> DateRange(YearMonth.from(today).minusMonths(2).atDay(1), YearMonth.from(today).atEndOfMonth())
        THIS_YEAR -> DateRange(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))
    }
}

private val TxnSort.labelRes get() = when (this) {
    TxnSort.DATE_DESC -> R.string.sort_newest
    TxnSort.DATE_ASC -> R.string.sort_oldest
    TxnSort.AMOUNT_ASC -> R.string.sort_largest_expense
    TxnSort.AMOUNT_DESC -> R.string.sort_largest_income
}

internal fun shortDate(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
internal fun longDate(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

@Composable
fun DateRange.label(): String {
    PeriodPreset.entries.firstOrNull { it.range() == this }?.let { return stringResource(it.labelRes) }
    val ym = YearMonth.from(from)
    if (from == ym.atDay(1) && to == ym.atEndOfMonth()) return from.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
    if (from.dayOfYear == 1 && to == from.withDayOfYear(from.lengthOfYear())) return from.year.toString()
    return if (from.year == to.year) "${from.format(shortDate())} – ${to.format(longDate())}" else "${from.format(longDate())} – ${to.format(longDate())}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(filter: TxnFilter, categories: List<CategoryDto>, onChange: (TxnFilter) -> Unit) {
    var pickingCategory by rememberSaveable { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    var customRange by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dims.Screen, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val categoryName = when {
            filter.uncategorized -> stringResource(R.string.category_uncategorized)
            filter.categoryId != null -> categories.firstOrNull { it.id == filter.categoryId }?.let { categoryDisplayName(it) } ?: stringResource(R.string.filter_category)
            else -> null
        }
        FilterChip(
            selected = categoryName != null,
            onClick = { pickingCategory = true },
            label = { Text(categoryName ?: stringResource(R.string.filter_category), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.widthIn(max = 220.dp),
        )
        Box {
            FilterChip(
                selected = filter.period != null,
                onClick = { periodMenu = true },
                label = { Text(filter.period?.label() ?: stringResource(R.string.filter_any_time), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.widthIn(max = 220.dp),
            )
            DropdownMenu(expanded = periodMenu, onDismissRequest = { periodMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.filter_any_time)) }, onClick = { periodMenu = false; onChange(filter.copy(period = null)) },
                    trailingIcon = { if (filter.period == null) Icon(Icons.Default.Check, null) })
                PeriodPreset.entries.forEach { p ->
                    val range = p.range()
                    DropdownMenuItem(text = { Text(stringResource(p.labelRes)) }, onClick = { periodMenu = false; onChange(filter.copy(period = range)) },
                        trailingIcon = { if (filter.period == range) Icon(Icons.Default.Check, null) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.filter_custom_range)) }, onClick = { periodMenu = false; customRange = true })
            }
        }
        Box {
            FilterChip(
                selected = filter.sort != TxnSort.DATE_DESC,
                onClick = { sortMenu = true },
                label = { Text(stringResource(filter.sort.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_sort), null, Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.widthIn(max = 220.dp),
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                TxnSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(stringResource(s.labelRes)) }, onClick = { sortMenu = false; onChange(filter.copy(sort = s)) },
                        trailingIcon = { if (filter.sort == s) Icon(Icons.Default.Check, null) })
                }
            }
        }
        if (filter.isActive) {
            TextButton(onClick = { onChange(TxnFilter()) }) {
                Icon(Icons.Default.Close, null, Modifier.size(FilterChipDefaults.IconSize))
                Text(stringResource(R.string.action_clear), Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
    }

    if (pickingCategory) {
        CategoryPickerSheet(
            title = stringResource(R.string.filter_by_category),
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
                ) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = { TextButton(onClick = { customRange = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DateRangePicker(pickerState, Modifier.weight(1f), title = { Text(stringResource(R.string.date_picker_title), Modifier.padding(start = 24.dp, top = 16.dp)) })
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
    showDailyMovements: Boolean = false,
    baseCurrency: String? = null,
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
                val totals = if (showDailyMovements) dailyTotals(dayItems, byId, baseCurrency) else null
                stickyHeader(key = "h-$date") {
                    DayHeader(
                        date = LocalDate.parse(date),
                        totals = totals,
                        // Pagination appends at the displayed tail in both date sorts, so the last
                        // booking date is the potentially incomplete group. `hasMore` is conservative.
                        partial = showDailyMovements &&
                            hasMore &&
                            date == items.lastOrNull()?.bookingDate,
                    )
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHeader(
    date: LocalDate,
    totals: DailyTotals? = null,
    partial: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Dims.Screen, end = Dims.Screen, top = Dims.ItemGap, bottom = 4.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    formatDay(date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                totals?.base?.let { MovementPair(it, it.converted) }
                totals?.unconverted?.forEach { MovementPair(it, approximate = false) }
            }
            val excluded = totals?.excludedTransfers ?: 0
            if (excluded > 0) {
                Text(
                    pluralStringResource(R.plurals.day_transfers_excluded, excluded, excluded),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (!totals?.unconverted.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.day_unconverted),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (partial) {
                Text(
                    stringResource(R.string.day_partial),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MovementPair(movement: DailyMovements, approximate: Boolean) {
    val amountColors = LocalAmountColors.current
    val prefix = if (approximate) "≈" else ""
    Text(
        prefix + formatMoney(movement.incoming, movement.currency, showPlus = true),
        style = MaterialTheme.typography.labelLarge,
        color = if (movement.incoming.signum() > 0) amountColors.income else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        prefix + formatMoney(movement.outgoing, movement.currency, showPlus = false),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TransactionRow(t: TransactionDto, categories: Map<Long, CategoryDto>, showDate: Boolean, onClick: (TransactionDto) -> Unit) {
    val category = t.categoryId?.let { categories[it] }
    val categoryName = category?.let { categoryDisplayName(it) }
    // Money in names the payer ("From ACME Oy"); money out keeps just the payee, as most rows are purchases.
    val title = (if (t.isIncoming()) t.partyLabel() else t.counterparty) ?: t.description
    val amount = t.amount.toBigDecimalOrNull()
    val amountColors = LocalAmountColors.current
    ListItem(
        modifier = Modifier.clickable { onClick(t) },
        leadingContent = { CategoryAvatar(categoryName ?: title, if (category != null) categoryColor(category.id) else MaterialTheme.colorScheme.outline, Dims.AvatarDefault) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val (text, color) = when (t.status) {
                TxnStatus.REVIEW -> stringResource(R.string.txn_suggested, category?.let { categoryDisplayName(it) } ?: stringResource(R.string.label_unknown)) to MaterialTheme.colorScheme.tertiary
                TxnStatus.UNCATEGORIZED -> stringResource(R.string.txn_uncategorized_tap) to MaterialTheme.colorScheme.error
                TxnStatus.CATEGORIZED -> (categoryName ?: stringResource(R.string.category_uncategorized)) to MaterialTheme.colorScheme.onSurfaceVariant
            }
            val date = if (showDate) "${formatDay(LocalDate.parse(t.bookingDate))} · " else ""
            Text(date + text, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Text(
                formatMoney(t.amount, t.currency, showPlus = true),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = if (amount != null && amount.signum() > 0) amountColors.income else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        },
    )
}

private const val LOAD_MORE_THRESHOLD = 20
