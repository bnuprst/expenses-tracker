package com.bnuprst.expenses.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bnuprst.expenses.android.R
import com.bnuprst.expenses.shared.CategoryDto
import com.bnuprst.expenses.shared.CategoryKind
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No SnackbarHostState provided") }

fun formatMoney(amount: BigDecimal, currency: String, showPlus: Boolean = false): String {
    val nf = NumberFormat.getCurrencyInstance()
    runCatching { nf.currency = Currency.getInstance(currency) }
    val s = nf.format(amount)
    return if (showPlus && amount.signum() > 0) "+$s" else s
}

fun formatMoney(amount: String, currency: String, showPlus: Boolean = false): String =
    amount.toBigDecimalOrNull()?.let { formatMoney(it, currency, showPlus) } ?: "$amount $currency"

internal fun dayFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
internal fun dayYearFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

internal fun dateTimeFormat(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())

/** An ISO-8601 instant from the API, rendered in the device timezone and UI locale; the raw text if unparseable. */
fun formatDateTime(iso: String): String = runCatching {
    java.time.Instant.parse(iso)
        .atZone(java.time.ZoneId.systemDefault())
        .format(dateTimeFormat())
}.recoverCatching {
    // tolerate a zone-less local date-time, should the API ever drop the offset
    java.time.LocalDateTime.parse(iso)
        .atZone(java.time.ZoneId.systemDefault())
        .format(dateTimeFormat())
}.getOrDefault(iso)

@Composable
fun formatDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> stringResource(R.string.label_today)
    today.minusDays(1) -> stringResource(R.string.label_yesterday)
    else -> date.format(if (date.year == today.year) dayFormat() else dayYearFormat())
}

/** A stable color per category, so the same category looks the same across lists. */
fun categoryColor(id: Long?): Color = id?.let { CategoryPalette[(it % CategoryPalette.size).toInt()] } ?: Color.Gray

/** Seeded categories carry a stable slug; their names are shown in the UI language. Names the user
 *  created (slug == null) are shown exactly as typed, even when they read like a default name. */
private val SEEDED_CATEGORY_NAMES: Map<String, Int> = mapOf(
    "groceries" to R.string.cat_groceries,
    "restaurants" to R.string.cat_restaurants,
    "transport" to R.string.cat_transport,
    "fuel" to R.string.cat_fuel,
    "housing" to R.string.cat_housing,
    "utilities" to R.string.cat_utilities,
    "phone_internet" to R.string.cat_phone_internet,
    "health" to R.string.cat_health,
    "kids" to R.string.cat_kids,
    "clothing" to R.string.cat_clothing,
    "household" to R.string.cat_household,
    "entertainment" to R.string.cat_entertainment,
    "subscriptions" to R.string.cat_subscriptions,
    "travel" to R.string.cat_travel,
    "education" to R.string.cat_education,
    "gifts" to R.string.cat_gifts,
    "insurance" to R.string.cat_insurance,
    "taxes" to R.string.cat_taxes,
    "other" to R.string.cat_other,
    "salary" to R.string.cat_salary,
    "other_income" to R.string.cat_other_income,
    "transfers" to R.string.cat_transfers,
)

@StringRes
internal fun seededCategoryNameRes(slug: String?): Int? = slug?.let { SEEDED_CATEGORY_NAMES[it] }

@Composable
fun categoryDisplayName(name: String, slug: String?): String =
    seededCategoryNameRes(slug)?.let { stringResource(it) } ?: name

@Composable
fun categoryDisplayName(category: CategoryDto): String = categoryDisplayName(category.name, category.slug)

/** Search must match what the user sees, so filtering runs over display names. Extracted for tests. */
fun filterCategories(categories: List<CategoryDto>, query: String, display: (CategoryDto) -> String): List<CategoryDto> {
    val q = query.trim()
    return if (q.isBlank()) categories else categories.filter { display(it).contains(q, ignoreCase = true) }
}

@Composable
fun CategoryAvatar(name: String, color: Color, size: Dp = Dims.AvatarDefault) {
    Box(Modifier.size(size).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: stringResource(R.string.label_unknown), color = color, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

/** Indeterminate bar under the top app bar while a request runs; keeps its height so content doesn't jump. */
@Composable
fun TopProgress(visible: Boolean) {
    Box(Modifier.fillMaxWidth().height(4.dp)) {
        if (visible) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Scrollable so pull-to-refresh still works on an empty screen. */
@Composable
fun EmptyState(icon: Painter, title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(top = 48.dp)) {
            Icon(icon, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

/** Pill-shaped search input in the style of the M3 search bar. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    TextField(
        value, onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) { Icon(Icons.Default.Clear, stringResource(R.string.action_clear_search)) } },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** What a category picker returned: a category, "uncategorized", or "any" (filter cleared). */
sealed interface CategoryChoice {
    data class Category(val category: CategoryDto) : CategoryChoice
    data object Uncategorized : CategoryChoice
    data object Any : CategoryChoice
}

/**
 * Bottom sheet listing the wallet's categories by kind, with a search box. [forFilter] adds the
 * "All categories" and "Uncategorized" choices used by the transaction filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    title: String,
    categories: List<CategoryDto>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (CategoryChoice) -> Unit,
    forFilter: Boolean = false,
    uncategorizedSelected: Boolean = false,
    subtitle: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val display = categories.associateWith { categoryDisplayName(it) }
    val matching = filterCategories(categories, query) { display.getValue(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SearchField(query, { query = it }, stringResource(R.string.search_categories), Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (forFilter && query.isBlank()) {
                item(key = "any") {
                    PickerRow(stringResource(R.string.filter_all_categories), null, selected = selectedId == null && !uncategorizedSelected) { onPick(CategoryChoice.Any) }
                }
                item(key = "none") {
                    PickerRow(stringResource(R.string.category_uncategorized), Color.Gray, selected = uncategorizedSelected) { onPick(CategoryChoice.Uncategorized) }
                }
            }
            CategoryKind.entries.forEach { kind ->
                val group = matching.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                item(key = "h-$kind") {
                    Text(
                        stringResource(kind.labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(group, key = { it.id }) { c ->
                    PickerRow(display.getValue(c), categoryColor(c.id), selected = c.id == selectedId) { onPick(CategoryChoice.Category(c)) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        leadingContent = color?.let { { CategoryAvatar(name, it, 36.dp) } },
        trailingContent = if (selected) ({ Icon(Icons.Default.Check, stringResource(R.string.action_selected), tint = MaterialTheme.colorScheme.primary) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

val CategoryKind.labelRes: Int
    @StringRes get() = when (this) {
        CategoryKind.EXPENSE -> R.string.kind_expenses
        CategoryKind.INCOME -> R.string.kind_income
        CategoryKind.TRANSFER -> R.string.kind_transfers
    }
