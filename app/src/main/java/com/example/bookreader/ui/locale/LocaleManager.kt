package com.example.bookreader.ui.locale

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val code: String, val displayName: String) {
    SYSTEM("system", "跟随系统 / Follow System / Suivre le système"),
    ZH("zh", "简体中文"),
    EN("en", "English"),
    FR("fr", "Français");

    companion object {
        fun fromCode(code: String): AppLanguage {
            return values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
        }
    }
}

object LocaleManager {
    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "app_language"

    private val _currentLanguage = MutableStateFlow(AppLanguage.SYSTEM)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        val lang = AppLanguage.fromCode(saved)
        _currentLanguage.value = lang
        applyLocaleToConfiguration(context, lang)
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        _currentLanguage.value = language
        applyLocaleToConfiguration(context, language)
    }

    fun getEffectiveLanguage(language: AppLanguage = _currentLanguage.value): AppLanguage {
        if (language != AppLanguage.SYSTEM) return language
        val sysLang = Locale.getDefault().language.lowercase()
        return when {
            sysLang.startsWith("fr") -> AppLanguage.FR
            sysLang.startsWith("zh") -> AppLanguage.ZH
            sysLang.startsWith("en") -> AppLanguage.EN
            else -> AppLanguage.EN
        }
    }

    private fun applyLocaleToConfiguration(context: Context, language: AppLanguage) {
        val effective = getEffectiveLanguage(language)
        val targetLocale = when (effective) {
            AppLanguage.FR -> Locale.FRENCH
            AppLanguage.EN -> Locale.ENGLISH
            AppLanguage.ZH, AppLanguage.SYSTEM -> Locale.SIMPLIFIED_CHINESE
        }
        Locale.setDefault(targetLocale)
        try {
            val resources = context.resources
            val config = resources.configuration
            config.setLocale(targetLocale)
            resources.updateConfiguration(config, resources.displayMetrics)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getStrings(language: AppLanguage = _currentLanguage.value): AppStrings {
        return when (getEffectiveLanguage(language)) {
            AppLanguage.FR -> FrStrings
            AppLanguage.EN -> EnStrings
            AppLanguage.ZH, AppLanguage.SYSTEM -> ZhStrings
        }
    }
}

val LocalAppStrings = compositionLocalOf { ZhStrings }
val LocalAppLanguage = compositionLocalOf { AppLanguage.SYSTEM }
