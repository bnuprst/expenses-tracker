package com.bnuprst.expenses.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Money colors the M3 scheme has no role for. */
@Immutable
data class AmountColors(val income: Color, val expense: Color)

val LocalAmountColors = staticCompositionLocalOf { AmountColors(Color.Unspecified, Color.Unspecified) }

/**
 * Wallpaper-based dynamic color on Android 12+, otherwise the M3 baseline scheme, whose primary is the
 * launcher background (#6750A4).
 */
@Composable
fun ExpensesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val amounts = if (dark) AmountColors(income = Color(0xFF7DDC8C), expense = Color(0xFFFFB4AB))
    else AmountColors(income = Color(0xFF1E6B32), expense = Color(0xFFBA1A1A))
    CompositionLocalProvider(LocalAmountColors provides amounts) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Distinct hues for categories in charts and avatars; readable on light and dark surfaces. */
val CategoryPalette = listOf(
    Color(0xFF7E57C2), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFFD81B60),
    Color(0xFF00ACC1), Color(0xFF8D6E63), Color(0xFFFDD835), Color(0xFF5C6BC0), Color(0xFF7CB342),
)
