package com.levabala.blackandroid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

internal object ExitDiagnostics {
    fun record(context: Context) {
        Thread {
            runCatching {
                val store = SettingsStore(context)
                val manager = context.getSystemService(ActivityManager::class.java)
                val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    .filter { it.timestamp > store.lastLoggedExit }
                    .sortedBy { it.timestamp }
                for (exit in exits) {
                    AppLog.info("process.previous_exit", mapOf(
                        "timestamp" to exit.timestamp, "reason" to exit.reason,
                        "status" to exit.status, "importance" to exit.importance,
                        "pss_kb" to exit.pss, "rss_kb" to exit.rss,
                        "last_service_heartbeat" to store.lastServiceHeartbeat,
                        "schedule_enabled" to store.enabled,
                    ))
                    store.lastLoggedExit = exit.timestamp
                }
                AppLog.info("recovery.device_state", mapOf(
                    "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                    "android_api" to Build.VERSION.SDK_INT,
                    "battery_exempt" to BackgroundRecovery.isExempt(context),
                    "power_save" to context.getSystemService(PowerManager::class.java).isPowerSaveMode,
                    "background_restricted" to manager.isBackgroundRestricted,
                ))
            }.onFailure { AppLog.error("process.exit_diagnostics_failed", it) }
        }.start()
    }
}
