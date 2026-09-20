package com.levabala.blackandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = SettingsStore(context)
        if (store.enabled && Settings.canDrawOverlays(context)) {
            context.startForegroundService(BlackService.command(context, BlackService.ACTION_START))
        } else if (store.enabled) {
            store.status = "Overlay permission needed"
        }
    }
}
