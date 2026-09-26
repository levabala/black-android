package com.levabala.blackandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = SettingsStore(context)
        AppLog.info("service.restart_broadcast", mapOf(
            "broadcast_action" to (intent.action ?: "unknown"),
            "schedule_enabled" to store.enabled,
            "overlay_permission" to Settings.canDrawOverlays(context),
        ))
        if (store.enabled && Settings.canDrawOverlays(context)) {
            AppLog.info("service.restart_requested", mapOf("source" to (intent.action ?: "unknown")))
            context.startForegroundService(BlackService.command(context, BlackService.ACTION_START))
        } else if (store.enabled) {
            AppLog.warn("service.restart_blocked", mapOf("reason" to "overlay_permission_missing"))
            store.status = context.getString(R.string.status_overlay_permission)
        }
    }
}
