package com.bnuprst.expenses.android.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bnuprst.expenses.android.AppLocale
import com.bnuprst.expenses.android.R

/** Picks the UI language. Writes the one store in AppLocale; on API < 33 it recreates the activity. */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved = AppLocale.saved(context)
    val options = listOf(
        AppLocale.SYSTEM to R.string.language_system,
        "en" to R.string.language_en,
        "uk" to R.string.language_uk,
        "pl" to R.string.language_pl,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        // Deliberately icon-less: NewWalletDialog's icon is wallet-specific, and there is no
        // language drawable, so inventing an unrelated glyph here would not be consistent.
        title = { Text(stringResource(R.string.action_language)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { (tag, res) ->
                    ListItem(
                        headlineContent = { Text(stringResource(res), maxLines = 1) },
                        trailingContent = if (tag == saved) ({ Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            if (tag != saved && AppLocale.save(context, tag)) (context as? Activity)?.recreate()
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
