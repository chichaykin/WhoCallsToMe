package com.whocalltome.app.data.settings

import android.content.Context

class AppPreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.fromStored(preferences.getString(KEY_THEME_MODE, null))
        set(value) = preferences.edit().putString(KEY_THEME_MODE, value.id).apply()

    var lookupProvider: String
        get() = preferences.getString(KEY_LOOKUP_PROVIDER, DEFAULT_LOOKUP_PROVIDER) ?: DEFAULT_LOOKUP_PROVIDER
        set(value) = preferences.edit().putString(KEY_LOOKUP_PROVIDER, value).apply()

    fun isSecretSuppressed(key: String): Boolean =
        preferences.getStringSet(KEY_SUPPRESSED_SECRETS, emptySet()).orEmpty().contains(key)

    fun setSecretSuppressed(key: String, suppressed: Boolean) {
        val values = preferences.getStringSet(KEY_SUPPRESSED_SECRETS, emptySet()).orEmpty().toMutableSet()
        if (suppressed) values += key else values -= key
        preferences.edit().putStringSet(KEY_SUPPRESSED_SECRETS, values).apply()
    }

    companion object {
        const val DEFAULT_LOOKUP_PROVIDER = "ipqs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOOKUP_PROVIDER = "lookup_provider"
        private const val KEY_SUPPRESSED_SECRETS = "suppressed_default_secrets"
    }
}

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Как в системе"),
    LIGHT("light", "Светлая"),
    DARK("dark", "Тёмная");

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.id == value } ?: SYSTEM
    }
}
