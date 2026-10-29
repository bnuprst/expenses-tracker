package com.bnuprst.expenses.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bnuprst.expenses.android.AppViewModel

/** Each screen owns its Scaffold (so insets are handled once); they share one snackbar host state. */
@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ExpensesTheme {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
        }
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            when {
                !state.loggedIn -> LoginScreen(state, vm)
                state.wallet == null -> WalletsScreen(state, vm)
                else -> WalletScreen(state, vm)
            }
        }
    }
}
