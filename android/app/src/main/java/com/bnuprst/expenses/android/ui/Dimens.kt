package com.bnuprst.expenses.android.ui

import androidx.compose.ui.unit.dp

/** Spacing and control sizes shared by the screens, so paddings and heights match across tabs. */
object Dims {
    val Screen = 16.dp          // screen edge padding
    val ItemGap = 12.dp         // gap between cards / list items
    val SectionGap = 20.dp      // gap above a section title
    val ListBottom = 24.dp      // extra bottom contentPadding in scrollable screens
    val FabClearance = 88.dp    // bottom contentPadding when a FAB floats over the list
    val Control = 56.dp         // MINIMUM height of a text field / primary button; never a fixed height
    val ButtonIcon = 18.dp      // icon inside a button
    val InlineIcon = 16.dp      // icon inline with body text
    val AvatarSmall = 36.dp
    val AvatarDefault = 40.dp
    val AvatarLarge = 56.dp
    val CountryField = 120.dp
    val CurrencyField = 104.dp
    val DetailLabel = 128.dp
    val IbanMax = 160.dp        // cap for the masked IBAN column in a bank account row
    val ListDividerInset = 68.dp // AvatarSmall(36) + ListItem start(16) + gap(16)
}
