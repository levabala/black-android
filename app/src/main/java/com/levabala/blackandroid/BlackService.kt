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
    private enum class TestStep {
        NOTIFICATION_CANCEL,
        WARNING_CANCEL,
        BLACKOUT_CANCEL,
    }

    private enum class CancelSource { NOTIFICATION, OVERLAY }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var timer: SchedulerCore
    private lateinit var overlay: OverlayController
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var foregroundApps: ForegroundAppTracker
    private var lastPhase: Phase? = null
    private var lastStatus = ""
    private var warningNotificationSent = false
    private var warningNotificationTarget: String? = null
    private var lastHealthLoggedAt = 0L
    private var lastMicrophoneActive: Boolean? = null
    private var testStep: TestStep? = null
    private var testPassedUntil = 0L
    private var currentForegroundPackage: String? = null
    private var currentExceptionPackage: String? = null
    private val startTestStep = Runnable {
        val now = SystemClock.elapsedRealtime()
        if (testStep != null && timer.phase == Phase.COUNTDOWN) {
            timer.testNow(now, TEST_WARNING_MILLIS, TEST_BLACKOUT_MILLIS)
            AppLog.info("service.test_step_started", mapOf("step" to testStepName()))
            render()
        }
    }

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
            resumeTestStepIfReady()
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
                resumeTestStepIfReady()
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
            val now = SystemClock.elapsedRealtime()
            updateForegroundException(now)
            val phaseBefore = timer.phase
            timer.tick(now)
            keepTestMoving(phaseBefore, now)
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
        foregroundApps = ForegroundAppTracker(this)
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
            currentForegroundPackage = foregroundApps.currentPackage()
            currentExceptionPackage = currentForegroundPackage
                ?.takeIf { it in store.exceptionPackages }
            timer.start(
                now,
                power.isInteractive && !keyguard.isKeyguardLocked,
                audio.activeRecordingConfigurations.isNotEmpty(),
                currentExceptionPackage != null,
            )
            AppLog.info("service.scheduler_started", mapOf(
                "screen_available" to (power.isInteractive && !keyguard.isKeyguardLocked),
                "microphone_active" to audio.activeRecordingConfigurations.isNotEmpty(),
            ))
            handler.post(ticker)
        }
        when (intent?.action) {
            ACTION_UPDATE -> {
                endTest(now, passed = false)
                timer.updateSettings(store.load(), now)
                AppLog.info("service.settings_updated")
            }
            ACTION_CANCEL -> {
                cancel(CancelSource.NOTIFICATION)
            }
            ACTION_TEST -> {
                startGuidedTest(now)
            }
            ACTION_ADD_EXCEPTION -> {
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!targetPackage.isNullOrBlank() && targetPackage != packageName) {
                    endTest(now, passed = false)
                    if (timer.phase == Phase.WARNING || timer.phase == Phase.BLACKOUT) {
                        timer.cancel(now)
                    }
                    val added = store.addException(targetPackage)
                    AppLog.info("user.exception_added", mapOf(
                        "source" to "warning_notification",
                        "package_name" to targetPackage,
                        "already_present" to !added,
                    ))
                    updateForegroundException(now)
                } else {
                    AppLog.warn("user.exception_add_failed", mapOf("reason" to "missing_current_app"))
                }
            }
        }
        render()
        return START_STICKY
    }

    private fun cancel() {
        cancel(CancelSource.OVERLAY)
    }

    private fun cancel(source: CancelSource) {
        val now = SystemClock.elapsedRealtime()
        val phaseBefore = timer.phase
        val canceled = timer.cancel(now)
        AppLog.info("user.blackout_cancel", mapOf(
            "source" to source.name.lowercase(),
            "phase" to phaseBefore.name.lowercase(),
            "test_step" to (testStepName()),
            "accepted" to canceled,
        ))
        val stepPassed = when (testStep) {
            TestStep.NOTIFICATION_CANCEL -> source == CancelSource.NOTIFICATION && phaseBefore == Phase.WARNING
            TestStep.WARNING_CANCEL -> source == CancelSource.OVERLAY && phaseBefore == Phase.WARNING
            TestStep.BLACKOUT_CANCEL -> source == CancelSource.OVERLAY && phaseBefore == Phase.BLACKOUT
            null -> false
        }
        if (stepPassed) {
            AppLog.info("service.test_step_passed", mapOf("step" to testStepName()))
            when (testStep) {
                TestStep.NOTIFICATION_CANCEL -> advanceTest(TestStep.WARNING_CANCEL)
                TestStep.WARNING_CANCEL -> advanceTest(TestStep.BLACKOUT_CANCEL)
                TestStep.BLACKOUT_CANCEL -> endTest(now, passed = true)
                null -> Unit
            }
        } else if (testStep != null && canceled) {
            AppLog.warn("service.test_wrong_action", mapOf(
                "step" to testStepName(),
                "source" to source.name.lowercase(),
                "phase" to phaseBefore.name.lowercase(),
            ))
            scheduleTestStep()
        }
        render()
    }

    private fun startGuidedTest(now: Long) {
        handler.removeCallbacks(startTestStep)
        testPassedUntil = 0
        testStep = TestStep.NOTIFICATION_CANCEL
        if (timer.phase == Phase.WARNING || timer.phase == Phase.BLACKOUT) timer.cancel(now)
        if (timer.phase == Phase.COUNTDOWN) {
            timer.testNow(now, TEST_WARNING_MILLIS, TEST_BLACKOUT_MILLIS)
        }
        AppLog.info("service.test_started", mapOf("step" to testStepName()))
    }

    private fun advanceTest(next: TestStep) {
        testStep = next
        scheduleTestStep()
    }

    private fun scheduleTestStep() {
        handler.removeCallbacks(startTestStep)
        handler.postDelayed(startTestStep, TEST_STEP_GAP_MILLIS)
    }

    private fun resumeTestStepIfReady() {
        if (testStep != null && timer.phase == Phase.COUNTDOWN) scheduleTestStep()
    }

    private fun keepTestMoving(phaseBefore: Phase, now: Long) {
        val step = testStep ?: return
        val missedWarningAction = step != TestStep.BLACKOUT_CANCEL && timer.phase == Phase.BLACKOUT
        val missedBlackoutAction = step == TestStep.BLACKOUT_CANCEL &&
            phaseBefore == Phase.BLACKOUT && timer.phase == Phase.COUNTDOWN
        if (missedWarningAction || missedBlackoutAction) {
            AppLog.warn("service.test_step_timed_out", mapOf("step" to testStepName()))
            if (timer.phase == Phase.BLACKOUT) timer.cancel(now)
            scheduleTestStep()
        }
    }

    private fun endTest(now: Long, passed: Boolean) {
        if (testStep == null) return
        handler.removeCallbacks(startTestStep)
        AppLog.info(if (passed) "service.test_passed" else "service.test_stopped")
        testStep = null
        if (passed) testPassedUntil = now + TEST_PASSED_STATUS_MILLIS
    }

    private fun testStepName(): String = testStep?.name?.lowercase() ?: "none"

    private fun testInstruction(): String? = when (testStep) {
        TestStep.NOTIFICATION_CANCEL -> "Test 1/3: open the notification and tap Cancel"
        TestStep.WARNING_CANCEL -> "Test 2/3: tap Cancel blackout below"
        TestStep.BLACKOUT_CANCEL -> "Test 3/3: wait for black, then tap it three times"
        null -> null
    }

    private fun updateForegroundException(now: Long) {
        val previousPackage = currentForegroundPackage
        val detected = foregroundApps.currentPackage()
        currentForegroundPackage = if (AppCatalog.usageAccessGranted(this)) detected else null
        if (previousPackage != currentForegroundPackage) {
            AppLog.info("service.foreground_app_changed", mapOf(
                "package_name" to (currentForegroundPackage ?: "unknown"),
            ))
        }
        val excepted = currentForegroundPackage?.takeIf { it in store.exceptionPackages }
        if (excepted != currentExceptionPackage) {
            currentExceptionPackage = excepted
            timer.setExceptionActive(excepted != null, now)
            AppLog.info("service.exception_pause_changed", mapOf(
                "active" to (excepted != null),
                "package_name" to (excepted ?: currentForegroundPackage ?: "unknown"),
            ))
        }
    }

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        when (timer.phase) {
            Phase.WARNING -> overlay.showWarning(
                ceil(timer.remainingMillis(now) / 1000.0).toLong(), testInstruction())
            Phase.BLACKOUT -> overlay.showBlackout()
            else -> overlay.remove()
        }
        val baseStatus = when (timer.phase) {
            Phase.STOPPED -> "Stopped"
            Phase.COUNTDOWN -> "Next warning in ${formatTime(timer.remainingMillis(now))}"
            Phase.WARNING -> "Blackout in ${formatTime(timer.remainingMillis(now))}"
            Phase.BLACKOUT -> "Blackout: ${formatTime(timer.remainingMillis(now))} left"
            Phase.MIC_PAUSED -> "Paused for microphone"
            Phase.APP_PAUSED -> "Paused for ${currentExceptionPackage?.let { AppCatalog.label(this, it) } ?: "app exception"}"
            Phase.LOCKED -> "Paused while locked: ${formatTime(timer.remainingMillis(now))} left"
        }
        val status = when {
            testStep != null && timer.phase == Phase.COUNTDOWN ->
                "${testInstruction()} · starting shortly"
            testStep != null -> "${testInstruction()} · $baseStatus"
            now < testPassedUntil && timer.phase == Phase.COUNTDOWN -> "$baseStatus · Test passed"
            else -> baseStatus
        }
        if (status != lastStatus) {
            store.status = status
            lastStatus = status
        }
        val warningNotificationNeeded = timer.phase == Phase.WARNING &&
            timer.remainingMillis(now) < 10_000 &&
            (testStep == null || testStep == TestStep.NOTIFICATION_CANCEL)
        if (warningNotificationNeeded &&
            (!warningNotificationSent || warningNotificationTarget != currentForegroundPackage)) {
            notifications.notify(WARNING_NOTIFICATION_ID, warningNotification())
            warningNotificationSent = true
            warningNotificationTarget = currentForegroundPackage
            AppLog.info("service.warning_notification_posted")
        } else if (!warningNotificationNeeded && warningNotificationSent) {
            notifications.cancel(WARNING_NOTIFICATION_ID)
            warningNotificationSent = false
            warningNotificationTarget = null
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
            "usage_access" to AppCatalog.usageAccessGranted(this),
            "exception_count" to store.exceptionPackages.size,
            "exception_active" to (currentExceptionPackage != null),
            "foreground_package" to (currentForegroundPackage ?: "unknown"),
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
            .setContentText(testInstruction() ?: "Timer running")
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
        val builder = Notification.Builder(this, WARNING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (testStep == TestStep.NOTIFICATION_CANCEL) "Test 1/3: notification Cancel" else "Blackout in 10 seconds")
            .setContentText(if (testStep == TestStep.NOTIFICATION_CANCEL) "Tap Cancel to pass this step" else "Tap Cancel to skip this blackout")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        currentForegroundPackage?.takeIf { it != packageName }?.let { foregroundPackage ->
            val addException = PendingIntent.getService(
                this,
                ADD_EXCEPTION_REQUEST,
                command(this, ACTION_ADD_EXCEPTION).putExtra(EXTRA_PACKAGE_NAME, foregroundPackage),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(
                null,
                "Except ${AppCatalog.label(this, foregroundPackage)}",
                addException,
            ).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        AppLog.info("service.destroyed", mapOf("phase" to timer.phase.name.lowercase()))
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(startTestStep)
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
        private const val TEST_WARNING_MILLIS = 10_000L
        private const val TEST_BLACKOUT_MILLIS = 10_000L
        private const val TEST_STEP_GAP_MILLIS = 1_500L
        private const val TEST_PASSED_STATUS_MILLIS = 5_000L
        private const val ADD_EXCEPTION_REQUEST = 4
        private const val EXTRA_PACKAGE_NAME = "package_name"
        const val ACTION_START = "com.levabala.blackandroid.START"
        const val ACTION_STOP = "com.levabala.blackandroid.STOP"
        const val ACTION_UPDATE = "com.levabala.blackandroid.UPDATE"
        const val ACTION_CANCEL = "com.levabala.blackandroid.CANCEL"
        const val ACTION_TEST = "com.levabala.blackandroid.TEST"
        const val ACTION_ADD_EXCEPTION = "com.levabala.blackandroid.ADD_EXCEPTION"

        fun command(context: Context, action: String) = Intent(context, BlackService::class.java).setAction(action)
        fun formatTime(millis: Long): String {
            val seconds = ceil(millis / 1000.0).toLong()
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
