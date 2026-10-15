package com.levabala.blackandroid

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings

object BackgroundRecovery {
    const val JOB_ID = 1402
    private const val NOTIFICATION_ID = 4
    private const val CHANNEL = "black_recovery"

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!SettingsStore(context).enabled) {
            cancel(context)
            return
        }
        if (jobs.getPendingJob(JOB_ID) != null) return
        runCatching {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecoveryJobService::class.java))
                .setPersisted(true)
                .setPeriodic(15 * 60_000L)
                .build()
            val accepted = jobs.schedule(job) == JobScheduler.RESULT_SUCCESS
            AppLog.info("recovery.check_scheduled", mapOf("accepted" to accepted))
        }.onFailure { AppLog.error("recovery.schedule_failed", it) }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        clearReminder(context)
    }

    fun clearReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        SettingsStore(context).recoveryNotified = false
    }

    fun check(context: Context, source: String, bootExemption: Boolean = false, completed: () -> Unit = {}) {
        val store = SettingsStore(context)
        AppLog.info("recovery.check", mapOf(
            "source" to source, "schedule_enabled" to store.enabled,
            "service_running" to BlackService.running, "battery_exempt" to isExempt(context),
            "power_save" to context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            "background_restricted" to context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            "last_heartbeat" to store.lastServiceHeartbeat,
        ))
        if (!store.enabled) { cancel(context); completed(); return }
        if (BlackService.running) { clearReminder(context); completed(); return }
        val reason = when {
            !Settings.canDrawOverlays(context) -> R.string.recovery_overlay_needed
            !bootExemption && !isExempt(context) -> R.string.recovery_battery_needed
            else -> null
        }
        if (reason != null) {
            remind(context, reason)
            completed()
            return
        }
        AppLog.info("recovery.restart_requested", mapOf("source" to source))
        ServiceCommands.send(context, BlackService.ACTION_START) { error ->
            if (error != null && store.enabled) {
                AppLog.warn("recovery.restart_blocked", mapOf("source" to source, "error" to error))
                remind(context, R.string.recovery_resume_needed)
            } else if (store.enabled) {
                AppLog.info("recovery.restart_succeeded", mapOf("source" to source))
            }
            completed()
        }
    }

    fun startBlocked(context: Context, error: Throwable) {
        AppLog.error("recovery.foreground_start_blocked", error)
        remind(context, R.string.recovery_resume_needed)
    }

    private fun remind(context: Context, reason: Int) {
        val store = SettingsStore(context)
        if (!store.enabled) return
        store.status = context.getString(R.string.recovery_waiting)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.recovery_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (store.recoveryNotified ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, ReliabilityActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val resume = PendingIntent.getForegroundService(context, NOTIFICATION_ID,
            BlackService.command(context, BlackService.ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.recovery_notification_title))
            .setContentText(context.getString(reason))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(if (reason == R.string.recovery_overlay_needed)
                R.string.recovery_setup else R.string.recovery_resume),
                if (reason == R.string.recovery_overlay_needed) open else resume).build())
            .build())
        store.recoveryNotified = true
        AppLog.info("recovery.resume_notification_posted")
    }
}

class RecoveryJobService : JobService() {
    private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        val run = ++generation
        // Completion can be synchronous. Post it so jobFinished follows this return.
        BackgroundRecovery.check(this, "periodic_job") {
            android.os.Handler(mainLooper).post {
                if (run == generation) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        generation++
        AppLog.info("recovery.check_deferred")
        return SettingsStore(this).enabled
    }
}
