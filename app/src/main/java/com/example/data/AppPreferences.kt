package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Gestor de preferencias de configuración y estado inicial (Onboarding Setup).
 * Permite gestionar:
 * - Setup completado
 * - Tema visual: SYSTEM (0), LIGHT (1), DARK (2)
 * - Idioma: "es" (Español), "en" (Inglés), "system" (Predeterminado)
 */
class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSetupCompleted(): Boolean {
        return prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    /**
     * Retorna el modo de tema guardado:
     * THEME_SYSTEM = 0
     * THEME_LIGHT = 1
     * THEME_DARK = 2
     */
    fun getThemeMode(): Int {
        return prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
    }

    fun setThemeMode(mode: Int) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun applySavedTheme() {
        applyTheme(getThemeMode())
    }

    /**
     * Retorna el código de idioma guardado: "system", "es", "en"
     */
    fun getLanguageCode(): String {
        return prefs.getString(KEY_LANGUAGE_CODE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguageCode(langCode: String) {
        prefs.edit().putString(KEY_LANGUAGE_CODE, langCode).apply()
        applyLanguage(langCode)
    }

    fun applySavedLanguage() {
        applyLanguage(getLanguageCode())
    }

    /**
     * Récord del mini-juego Easter Egg (Padlock Runner).
     */
    fun getRunnerHighScore(): Int {
        return prefs.getInt(KEY_RUNNER_HIGH_SCORE, 0)
    }

    fun setRunnerHighScore(highScore: Int) {
        prefs.edit().putInt(KEY_RUNNER_HIGH_SCORE, highScore).apply()
    }

    companion object {
        private const val PREFS_NAME = "taglock_app_preferences"
        private const val KEY_SETUP_COMPLETED = "pref_setup_completed"
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_LANGUAGE_CODE = "pref_language_code"
        private const val KEY_RUNNER_HIGH_SCORE = "pref_runner_high_score"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val LANG_SYSTEM = "system"
        const val LANG_ES = "es"
        const val LANG_EN = "en"

        @Volatile
        private var instance: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context).also { instance = it }
            }
        }

        fun applyTheme(mode: Int) {
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        fun applyLanguage(langCode: String) {
            if (langCode == LANG_SYSTEM) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            } else {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
            }
        }
    }
}
