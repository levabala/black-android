package com.levabala.blackandroid

import android.content.Context

enum class Appearance { SYSTEM, LIGHT, DARK }

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("black", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var status: String
        get() = prefs.getString("status", "Stopped") ?: "Stopped"
        set(value) = prefs.edit().putString("status", value).apply()

    var appearance: Appearance
        get() = runCatching {
            Appearance.valueOf(prefs.getString("appearance", Appearance.SYSTEM.name) ?: Appearance.SYSTEM.name)
        }.getOrDefault(Appearance.SYSTEM)
        set(value) = prefs.edit().putString("appearance", value.name).apply()

    var updateResult: String?
        get() = prefs.getString("updateResult", null)
        set(value) = prefs.edit().putString("updateResult", value).apply()

    var exceptionPackages: Set<String>
        get() = prefs.getStringSet("exceptionPackages", emptySet())?.toSet() ?: emptySet()
        private set(value) = prefs.edit().putStringSet("exceptionPackages", value).apply()

    fun addException(packageName: String): Boolean {
        val updated = exceptionPackages + packageName
        if (updated == exceptionPackages) return false
        exceptionPackages = updated
        return true
    }

    fun removeException(packageName: String): Boolean {
        val updated = exceptionPackages - packageName
        if (updated == exceptionPackages) return false
        exceptionPackages = updated
        return true
    }

    fun load(): BlackSettings = BlackSettings(
        intervalMillis = prefs.getLong("intervalMillis", 20 * 60 * 1000L),
        warningMillis = prefs.getLong("warningMillis", 10 * 1000L).coerceAtLeast(10 * 1000L),
        blackoutMillis = prefs.getLong("blackoutMillis", 20 * 1000L),
        pauseForMicrophone = prefs.getBoolean("pauseForMicrophone", true),
    )

    fun save(settings: BlackSettings) {
        prefs.edit()
            .putLong("intervalMillis", settings.intervalMillis)
            .putLong("warningMillis", settings.warningMillis)
            .putLong("blackoutMillis", settings.blackoutMillis)
            .putBoolean("pauseForMicrophone", settings.pauseForMicrophone)
            .remove("resetAfterLock")
            .apply()
    }
}
