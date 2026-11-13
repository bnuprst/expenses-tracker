package com.bnuprst.expenses.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import com.bnuprst.expenses.android.ui.App

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Both the in-app switcher and Settings -> App languages recreate the activity but keep the
        // retained ViewModel, so already-loaded server text (e.g. lastSyncError) must be refetched.
        vm.onUiLanguage(AppLocale.currentTag(this))
        enableEdgeToEdge()
        vm.openUrl = { url -> CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
        handleIntent(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "expenses" && data.host == "bank-connected") vm.onBankRedirect(data)
    }
}
