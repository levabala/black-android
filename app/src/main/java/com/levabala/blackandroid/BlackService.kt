package com.levabala.blackandroid

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.ceil

class BlackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var timer: SchedulerCore
    private lateinit var overlay: OverlayController
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private var lastPhase: Phase? = null
    private var lastStatus = ""
    private var warningNotificationSent = false
    private var lastHealthLoggedAt = 0L
    private var lastMicrophoneActive: Boolean? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val previousPhase = timer.phase
            val keyguard = getSystemService(KeyguardManager::class.java)
            val power = getSystemService(PowerManager::class.java)
            val available = power.isInteractive && !keyguard.isKeyguardLocked
            timer.setScreenAvailable(available, SystemClock.elapsedRealtime())
            AppLog.info("service.screen_availability_changed", mapOf(
                "broadcast_action" to (intent.action ?: "unknown"),
                "screen_available" to available,
                "phase_before" to previousPhase.name.lowercase(),
                "phase_after" to timer.phase.name.lowercase(),
            ))
            render()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            handler.post {
                val active = configs.isNotEmpty()
                timer.setMicrophoneActive(active, SystemClock.elapsedRealtime())
                if (lastMicrophoneActive != active) {
                    AppLog.info("service.microphone_activity_changed", mapOf(
                        "active" to active,
                        "recording_count" to configs.size,
                        "phase" to timer.phase.name.lowercase(),
                    ))
                    lastMicrophoneActive = active
                }
                render()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!store.enabled) {
                AppLog.info("service.stopping", mapOf("reason" to "schedule_disabled"))
                stopSelf()
                return
            }
            if (!Settings.canDrawOverlays(this@BlackService)) {
                AppLog.warn("service.stopping", mapOf("reason" to "overlay_permission_missing"))
                overlay.remove()
                store.status = "Overlay permission needed"
                stopSelf()
                return
            }
            timer.tick(SystemClock.elapsedRealtime())
            render()
            logHealthIfDue()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        timer = SchedulerCore(store.load())
        overlay = OverlayController(this) { cancel() }
        audio = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        AppLog.info("service.created")
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Black schedule", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(WARNING_CHANNEL, "Blackout warnings", NotificationManager.IMPORTANCE_HIGH)
        )
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        audio.registerAudioRecordingCallback(recordingCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.info("service.command_received", mapOf(
            "command" to (intent?.action ?: "sticky_restart"),
            "start_id" to startId,
            "enabled" to store.enabled,
        ))
        if (intent?.action == ACTION_STOP || !store.enabled) {
            AppLog.info("service.stop_command", mapOf(
                "explicit" to (intent?.action == ACTION_STOP),
            ))
            store.enabled = false
            store.status = "Stopped"
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground must be called promptly, including after a system restart.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val now = SystemClock.elapsedRealtime()
        if (timer.phase == Phase.STOPPED) {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            timer.start(now, power.isInteractive && !keyguard.isKeyguardLocked,
                audio.activeRecordingConfigurations.isNotEmpty())
            AppLog.info("service.scheduler_started", mapOf(
                "screen_available" to (power.isInteractive && !keyguard.isKeyguardLocked),
                "microphone_active" to audio.activeRecordingConfigurations.isNotEmpty(),
            ))
            handler.post(ticker)
        }
        when (intent?.action) {
            ACTION_UPDATE -> {
                timer.updateSettings(store.load(), now)
                AppLog.info("service.settings_updated")
            }
            ACTION_CANCEL -> {
                timer.cancel(now)
                AppLog.info("user.blackout_cancel", mapOf("source" to "notification"))
            }
            ACTION_TEST -> {
                timer.testNow(now)
                AppLog.info("service.test_started")
            }
        }
        render()
        return START_STICKY
    }

    private fun cancel() {
        timer.cancel(SystemClock.elapsedRealtime())
        AppLog.info("user.blackout_cancel", mapOf("source" to "overlay"))
        render()
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        when (timer.phase) {
            Phase.WARNING -> overlay.showWarning(ceil(timer.remainingMillis(now) / 1000.0).toLong())
            Phase.BLACKOUT -> overlay.showBlackout()
            else -> overlay.remove()
        }
        val status = when (timer.phase) {
            Phase.STOPPED -> "Stopped"
            Phase.COUNTDOWN -> "Next warning in ${formatTime(timer.remainingMillis(now))}"
            Phase.WARNING -> "Blackout in ${formatTime(timer.remainingMillis(now))}"
            Phase.BLACKOUT -> "Blackout: ${formatTime(timer.remainingMillis(now))} left"
            Phase.MIC_PAUSED -> "Paused for microphone"
            Phase.LOCKED -> "Paused while locked: ${formatTime(timer.remainingMillis(now))} left"
        }
        if (status != lastStatus) {
            store.status = status
            lastStatus = status
        }
        if (timer.phase == Phase.WARNING && timer.remainingMillis(now) < 10_000 && !warningNotificationSent) {
            notifications.notify(WARNING_NOTIFICATION_ID, warningNotification())
            warningNotificationSent = true
            AppLog.info("service.warning_notification_posted")
        } else if (timer.phase != Phase.WARNING && warningNotificationSent) {
            notifications.cancel(WARNING_NOTIFICATION_ID)
            warningNotificationSent = false
            AppLog.info("service.warning_notification_removed")
        }
        if (timer.phase != lastPhase) {
            AppLog.info("service.phase_changed", mapOf(
                "phase_before" to (lastPhase?.name?.lowercase() ?: "none"),
                "phase_after" to timer.phase.name.lowercase(),
                "remaining_ms" to timer.remainingMillis(now),
            ))
            notifications.notify(NOTIFICATION_ID, notification())
            lastPhase = timer.phase
        }
    }

    private fun logHealthIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastHealthLoggedAt != 0L && now - lastHealthLoggedAt < HEALTH_LOG_INTERVAL_MS) return
        lastHealthLoggedAt = now
        AppLog.info("service.health", mapOf(
            "phase" to timer.phase.name.lowercase(),
            "remaining_ms" to timer.remainingMillis(now),
            "schedule_enabled" to store.enabled,
            "overlay_permission" to Settings.canDrawOverlays(this),
            "process_uptime_ms" to now,
        ))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, command(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Black")
            .setContentText("Timer running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return builder.build()
    }

    private fun warningNotification(): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 2, command(this, ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, WARNING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Blackout in 10 seconds")
            .setContentText("Tap Cancel to skip this blackout")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    override fun onDestroy() {
        AppLog.info("service.destroyed", mapOf("phase" to timer.phase.name.lowercase()))
        handler.removeCallbacks(ticker)
        audio.unregisterAudioRecordingCallback(recordingCallback)
        unregisterReceiver(screenReceiver)
        overlay.remove()
        notifications.cancel(WARNING_NOTIFICATION_ID)
        timer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "black_schedule"
        private const val WARNING_CHANNEL = "black_warning"
        private const val NOTIFICATION_ID = 1
        private const val WARNING_NOTIFICATION_ID = 2
        private const val HEALTH_LOG_INTERVAL_MS = 5 * 60 * 1_000L
        const val ACTION_START = "com.levabala.blackandroid.START"
        const val ACTION_STOP = "com.levabala.blackandroid.STOP"
        const val ACTION_UPDATE = "com.levabala.blackandroid.UPDATE"
        const val ACTION_CANCEL = "com.levabala.blackandroid.CANCEL"
        const val ACTION_TEST = "com.levabala.blackandroid.TEST"

        fun command(context: Context, action: String) = Intent(context, BlackService::class.java).setAction(action)
        fun formatTime(millis: Long): String {
            val seconds = ceil(millis / 1000.0).toLong()
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
