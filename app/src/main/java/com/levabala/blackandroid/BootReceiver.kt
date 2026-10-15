package com.levabala.blackandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        BackgroundRecovery.schedule(context)
        val pending = goAsync()
        BackgroundRecovery.check(context, intent.action ?: "boot", bootExemption = true) { pending.finish() }
    }
}
