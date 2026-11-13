package com.bnuprst.expenses.android

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The UI language. There is exactly one store:
 *  - API 33+: the framework per-app locale (LocaleManager). The system "App languages" screen and the
 *    in-app switcher write the same place, so they can never disagree, and a system change recreates the
 *    activity by itself.
 *  - API < 33: Android has no per-app language setting at all, so the choice lives in the existing
 *    "session" preferences and is applied in MainActivity.attachBaseContext.
 */
object AppLocale {
    /** Follow the system language. */
    const val SYSTEM = ""
    val SUPPORTED = listOf("en", "uk", "pl")

    private val perAppApi get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun prefs(context: Context) = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** The saved choice: a language tag, or [SYSTEM] when following the system language. */
    fun saved(context: Context): String =
        if (perAppApi) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.language ?: SYSTEM
        } else {
            prefs(context).getString("lang", SYSTEM) ?: SYSTEM
        }

    /**
     * Stores the choice. Returns true when the caller must recreate the activity itself (API < 33);
     * on API 33+ the framework does it.
     */
    fun save(context: Context, tag: String): Boolean =
        if (perAppApi) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            false
        } else {
            prefs(context).edit().putString("lang", tag).apply()
            true
        }

    /** The locale the UI is actually running in right now. */
    fun current(context: Context): Locale {
        val saved = saved(context)
        if (saved != SYSTEM) return Locale.forLanguageTag(saved)
        return context.resources.configuration.locales[0]
    }

    fun currentTag(context: Context): String = current(context).toLanguageTag()

    /**
     * A context whose resources use [current], and the matching JVM default locale so NumberFormat and
     * DateTimeFormatter format money and dates in the same language as the strings. On API 33+ the
     * framework has already applied the per-app locale to [base], so only the JVM default is set.
     */
    fun wrap(base: Context): Context {
        val locale = current(base)
        Locale.setDefault(locale)
        if (perAppApi) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
