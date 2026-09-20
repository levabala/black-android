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

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val keyguard = getSystemService(KeyguardManager::class.java)
            val power = getSystemService(PowerManager::class.java)
            val available = power.isInteractive && !keyguard.isKeyguardLocked
            timer.setScreenAvailable(available, SystemClock.elapsedRealtime())
            render()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            handler.post {
                timer.setMicrophoneActive(configs.isNotEmpty(), SystemClock.elapsedRealtime())
                render()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!store.enabled) {
                stopSelf()
                return
            }
            if (!Settings.canDrawOverlays(this@BlackService)) {
                overlay.remove()
                store.status = "Overlay permission needed"
                stopSelf()
                return
            }
            timer.tick(SystemClock.elapsedRealtime())
            render()
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
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Black schedule", NotificationManager.IMPORTANCE_LOW)
        )
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        audio.registerAudioRecordingCallback(recordingCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !store.enabled) {
            store.enabled = false
            store.status = "Stopped"
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground must be called promptly, including after a system restart.
        startForeground(NOTIFICATION_ID, notification("Running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val now = SystemClock.elapsedRealtime()
        if (timer.phase == Phase.STOPPED) {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            timer.start(now, power.isInteractive && !keyguard.isKeyguardLocked,
                audio.activeRecordingConfigurations.isNotEmpty())
            handler.post(ticker)
        }
        when (intent?.action) {
            ACTION_UPDATE -> timer.updateSettings(store.load(), now)
            ACTION_CANCEL -> timer.cancel(now)
            ACTION_TEST -> timer.testNow(now)
        }
        render()
        return START_STICKY
    }

    private fun cancel() {
        timer.cancel(SystemClock.elapsedRealtime())
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
            Phase.LOCKED -> "Paused while locked"
        }
        if (status != lastStatus) {
            store.status = status
            lastStatus = status
        }
        if (timer.phase != lastPhase) {
            notifications.notify(NOTIFICATION_ID, notification(status))
            lastPhase = timer.phase
        }
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, command(this, ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Black")
            .setContentText(status)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        if (timer.phase == Phase.WARNING) {
            val cancel = PendingIntent.getService(this, 2, command(this, ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        audio.unregisterAudioRecordingCallback(recordingCallback)
        unregisterReceiver(screenReceiver)
        overlay.remove()
        timer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "black_schedule"
        private const val NOTIFICATION_ID = 1
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
