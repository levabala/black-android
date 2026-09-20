package com.levabala.blackandroid

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("black", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var status: String
        get() = prefs.getString("status", "Stopped") ?: "Stopped"
        set(value) = prefs.edit().putString("status", value).apply()

    fun load(): BlackSettings = BlackSettings(
        intervalMillis = prefs.getLong("intervalMillis", 20 * 60 * 1000L),
        warningMillis = prefs.getLong("warningMillis", 10 * 1000L),
        blackoutMillis = prefs.getLong("blackoutMillis", 20 * 1000L),
        pauseForMicrophone = prefs.getBoolean("pauseForMicrophone", true),
        resetAfterLock = prefs.getBoolean("resetAfterLock", true),
    )

    fun save(settings: BlackSettings) {
        prefs.edit()
            .putLong("intervalMillis", settings.intervalMillis)
            .putLong("warningMillis", settings.warningMillis)
            .putLong("blackoutMillis", settings.blackoutMillis)
            .putBoolean("pauseForMicrophone", settings.pauseForMicrophone)
            .putBoolean("resetAfterLock", settings.resetAfterLock)
            .apply()
    }
}
