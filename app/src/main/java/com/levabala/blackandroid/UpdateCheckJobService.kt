package com.levabala.blackandroid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ActivityOptions
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicBoolean

/** Android runs this network check approximately once a day, even when Black is closed. */
class UpdateCheckJobService : JobService() {
    private var cancelled = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runCancelled = AtomicBoolean(false)
        cancelled = runCancelled
        worker = Thread {
            try {
                AppLog.info("update.background_check_started")
                val release = AppUpdater(applicationContext).latestUpdate()
                if (!runCancelled.get()) {
                    if (release == null) {
                        UpdateNotifications.clear(applicationContext)
                        AppLog.info("update.background_up_to_date")
                    } else {
                        UpdateNotifications.show(applicationContext, release)
                    }
                }
            } catch (error: Exception) {
                if (!runCancelled.get()) AppLog.error("update.background_check_failed", error)
            } finally {
                if (!runCancelled.get()) jobFinished(params, false)
            }
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled.set(true)
        worker?.interrupt()
        worker = null
        AppLog.warn("update.background_check_stopped")
        return true
    }

    companion object {
        const val JOB_ID = 1401
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(DAY_MS, DAY_MS)
                    .build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
                    AppLog.info("update.daily_check_scheduled")
                } else {
                    AppLog.warn("update.daily_check_schedule_failed")
                }
            } catch (error: RuntimeException) {
                AppLog.error("update.daily_check_schedule_failed", error)
            }
        }
    }
}

object UpdateNotifications {
    private const val CHANNEL_ID = "black_updates"
    private const val NOTIFICATION_ID = 3
    private const val REQUEST_CODE = 3

    fun show(context: Context, release: ApkRelease) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            AppLog.info("update.notification_unavailable")
            return
        }
        val store = SettingsStore(context)
        val version = release.version.toString()
        if (store.notifiedUpdateVersion == version) return
        val launchOptions = ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }.toBundle()
        val updateIntent = PendingIntent.getActivity(context, REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_UPDATE_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, launchOptions)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Black $version is available")
            .setContentText("Tap Update to download and install it.")
            .setContentIntent(updateIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Update", updateIntent).build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        store.notifiedUpdateVersion = version
        AppLog.info("update.notification_posted", mapOf("target_version" to version))
    }

    fun clear(context: Context) {
        dismiss(context)
        SettingsStore(context).notifiedUpdateVersion = null
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun clearIfInstalled(context: Context) {
        val notified = ReleaseVersion.parse(SettingsStore(context).notifiedUpdateVersion ?: return) ?: return
        val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME) ?: return
        if (installed >= notified) clear(context)
    }
}
