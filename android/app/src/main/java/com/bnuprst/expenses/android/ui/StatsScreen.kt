package com.bnuprst.expenses.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.AppViewModel
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.android.StatsGranularity
import com.bnuprst.expenses.android.StatsPeriod
import com.bnuprst.expenses.android.UiState
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import com.bnuprst.expenses.shared.StatsDto
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** One ranked row of the breakdown, as a positive magnitude. */
private data class Share(val categoryId: Long?, val name: String, val amount: BigDecimal, val count: Int, val color: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(state: UiState, vm: AppViewModel) {
    var side by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }
    val stats = state.stats
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "period") { PeriodSelector(state.statsPeriod, vm::setStatsPeriod) }
        if (stats == null) {
            if (!state.loading) item { EmptyState(painterResource(R.drawable.ic_bar_chart), "No statistics yet") }
            return@LazyColumn
        }
        item(key = "summary") { Summary(stats) }
        item(key = "side") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(CategoryKind.EXPENSE, CategoryKind.INCOME).forEachIndexed { i, kind ->
                    SegmentedButton(
                        selected = side == kind, onClick = { side = kind },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(if (kind == CategoryKind.EXPENSE) "Spending" else "Income") }
                }
            }
        }
        val shares = shares(stats, side, state.categories)
        val total = shares.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount.max(BigDecimal.ZERO) }
        if (shares.isEmpty()) {
            item(key = "none") {
                EmptyState(
                    painterResource(R.drawable.ic_bar_chart),
                    if (side == CategoryKind.EXPENSE) "No spending in this period" else "No income in this period",
                    modifier = Modifier.height(240.dp),
                )
            }
        } else {
            item(key = "donut") { Breakdown(shares, total, stats.currency, side) }
            items(shares, key = { "c-${it.categoryId}" }) { s ->
                ShareRow(s, total, stats.currency) { vm.showCategoryTransactions(s.categoryId) }
            }
        }
        item(key = "trend") { Trend(stats) }
        if (stats.otherCurrencyCount > 0) {
            item(key = "fx") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${stats.otherCurrencyCount} transaction(s) in other currencies are not included.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun shares(stats: StatsDto, side: CategoryKind, categories: List<CategoryDto>): List<Share> {
    val byId = categories.associateBy { it.id }
    val rows = if (side == CategoryKind.EXPENSE) stats.expenses else stats.income
    return rows.mapIndexed { i, r ->
        val signed = r.total.toBigDecimalOrNull() ?: BigDecimal.ZERO
        Share(
            categoryId = r.categoryId,
            name = r.categoryId?.let { byId[it]?.name } ?: "Uncategorized",
            amount = if (side == CategoryKind.EXPENSE) signed.negate() else signed,
            count = r.count,
            color = if (r.categoryId == null) Color.Gray else CategoryPalette[i % CategoryPalette.size],
        )
    }
}

private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(period: StatsPeriod, onChange: (StatsPeriod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StatsGranularity.entries.forEachIndexed { i, g ->
                SegmentedButton(
                    selected = period.granularity == g,
                    onClick = { onChange(StatsPeriod(g, period.anchor)) },
                    shape = SegmentedButtonDefaults.itemShape(i, StatsGranularity.entries.size),
                ) { Text(if (g == StatsGranularity.MONTH) "Month" else "Year") }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(period.shift(-1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
            Text(
                if (period.granularity == StatsGranularity.MONTH) period.anchor.format(monthFormat) else period.anchor.year.toString(),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val atPresent = !period.to.isBefore(LocalDate.now())
            IconButton(onClick = { onChange(period.shift(1)) }, enabled = !atPresent) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
        }
    }
}

@Composable
private fun Summary(stats: StatsDto) {
    val spent = stats.totalExpenses.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val earned = stats.totalIncome.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val net = earned - spent
    val colors = LocalAmountColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryCard("Spent", formatMoney(spent, stats.currency), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        SummaryCard("Earned", formatMoney(earned, stats.currency), colors.income, Modifier.weight(1f))
        SummaryCard("Net", formatMoney(net, stats.currency, showPlus = true), if (net.signum() < 0) colors.expense else colors.income, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Breakdown(shares: List<Share>, total: BigDecimal, currency: String, side: CategoryKind) {
    // The ring shows the top categories; the rest are folded into "Other" so slices stay readable.
    val top = shares.filter { it.amount.signum() > 0 }.take(DONUT_SLICES)
    val rest = shares.filter { it.amount.signum() > 0 }.drop(DONUT_SLICES).fold(BigDecimal.ZERO) { acc, s -> acc + s.amount }
    val slices = top.map { Slice(it.amount.toFloat(), it.color) } +
        (if (rest.signum() > 0) listOf(Slice(rest.toFloat(), MaterialTheme.colorScheme.outlineVariant)) else emptyList())
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DonutChart(slices, Modifier.size(200.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (side == CategoryKind.EXPENSE) "Spent" else "Earned", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatMoney(total, currency), style = MaterialTheme.typography.titleLarge)
                    Text("${shares.size} categories", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ShareRow(s: Share, total: BigDecimal, currency: String, onClick: () -> Unit) {
    val fraction = if (total.signum() > 0) (s.amount.toFloat() / total.toFloat()).coerceAtLeast(0f) else 0f
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CategoryAvatar(s.name, s.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatMoney(s.amount, currency), style = MaterialTheme.typography.titleSmall)
            }
            ShareBar(fraction, s.color, Modifier.fillMaxWidth())
            Text(
                "${"%.1f".format(fraction * 100)}% · ${s.count} transaction${if (s.count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val shortMonth = DateTimeFormatter.ofPattern("LLL")

@Composable
private fun Trend(stats: StatsDto) {
    val colors = LocalAmountColors.current
    val expenseColor = MaterialTheme.colorScheme.primary
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Last 6 months", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(expenseColor, "Spending")
                LegendDot(colors.income, "Income")
            }
            GroupedBarChart(
                groups = stats.months.map { m ->
                    BarGroup(
                        YearMonth.parse(m.month).format(shortMonth),
                        listOf(m.expenses.toFloatOrNull() ?: 0f, m.income.toFloatOrNull() ?: 0f),
                    )
                },
                colors = listOf(expenseColor, colors.income),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

private const val DONUT_SLICES = 6
