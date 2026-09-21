package com.levabala.blackandroid

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings

data class InstalledApp(val label: String, val packageName: String)

object AppCatalog {
    fun usageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun label(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    fun launchableApps(context: Context): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).map {
            InstalledApp(
                it.loadLabel(context.packageManager).toString(),
                it.activityInfo.packageName,
            )
        }.filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}

class ForegroundAppTracker(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryWallTime = 0L
    private var lastForegroundPackage: String? = null

    fun currentPackage(): String? {
        if (!AppCatalog.usageAccessGranted(context)) return null
        val now = System.currentTimeMillis()
        val start = if (lastQueryWallTime == 0L) now - INITIAL_LOOKBACK_MILLIS else lastQueryWallTime - 1_000L
        val events = usage.queryEvents(start, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                when (event.packageName) {
                    SYSTEM_UI_PACKAGE -> Unit
                    context.packageName -> lastForegroundPackage = null
                    else -> lastForegroundPackage = event.packageName
                }
            }
        }
        lastQueryWallTime = now
        return lastForegroundPackage
    }

    private companion object {
        const val INITIAL_LOOKBACK_MILLIS = 24 * 60 * 60 * 1_000L
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
