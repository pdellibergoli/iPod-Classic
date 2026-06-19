package com.train.ipodclassicemulator.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Gestisce il tema corrente dell'app e lo salva in SharedPreferences
 * così la scelta dell'utente sopravvive alla chiusura dell'app.
 */
class ThemeManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentTheme by mutableStateOf(loadTheme())
        private set

    private fun loadTheme(): IPodThemeType {
        val saved = prefs.getString(KEY_THEME, null) ?: return IPodThemeType.CLASSIC_WHITE
        return try {
            IPodThemeType.valueOf(saved)
        } catch (e: IllegalArgumentException) {
            IPodThemeType.CLASSIC_WHITE
        }
    }

    fun setTheme(theme: IPodThemeType) {
        currentTheme = theme
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "ipod_theme_prefs"
        private const val KEY_THEME = "selected_theme"
    }
}