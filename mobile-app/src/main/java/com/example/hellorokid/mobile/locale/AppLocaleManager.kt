package com.example.hellorokid.mobile.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocaleManager {

    private const val PREFS = "app_locale_prefs"
    private const val KEY_LANGUAGE = "language_tag"

    const val LANG_ZH = "zh"
    const val LANG_EN = "en"
    const val LANG_JA = "ja"

    val supportedLanguages = listOf(LANG_ZH, LANG_EN, LANG_JA)

    fun getLanguageTag(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_ZH)
            ?: LANG_ZH
    }

    fun setLanguageTag(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, tag)
            .apply()
        applyAppLocale(tag)
    }

    fun applySavedLocale(context: Context) {
        applyAppLocale(getLanguageTag(context))
    }

    fun applyAppLocale(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun toApiLocale(tag: String): String {
        return when (tag) {
            LANG_ZH -> "zh-CN"
            LANG_EN -> "en"
            LANG_JA -> "ja"
            else -> tag
        }
    }

    fun displayName(context: Context, tag: String): String {
        return when (tag) {
            LANG_ZH -> context.getString(com.example.hellorokid.mobile.R.string.language_chinese)
            LANG_EN -> context.getString(com.example.hellorokid.mobile.R.string.language_english)
            LANG_JA -> context.getString(com.example.hellorokid.mobile.R.string.language_japanese)
            else -> tag
        }
    }
}
