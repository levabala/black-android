package com.levabala.blackandroid

import kotlin.math.max

data class BlackSettings(
    val intervalMillis: Long = 20 * 60 * 1000L,
    val warningMillis: Long = 10 * 1000L,
    val blackoutMillis: Long = 20 * 1000L,
    val pauseForMicrophone: Boolean = true,
)

enum class Phase {
    STOPPED,
    COUNTDOWN,
    WARNING,
    BLACKOUT,
    MIC_PAUSED,
    APP_PAUSED,
    LOCKED,
}

/** The timer uses elapsed realtime, so wall clock changes cannot move a blackout. */
class SchedulerCore(initialSettings: BlackSettings) {
    private companion object {
        const val LOCK_RESET_MILLIS = 60_000L
    }

    var settings: BlackSettings = initialSettings
        private set

    var phase: Phase = Phase.STOPPED
        private set

    var deadlineMillis: Long = 0
        private set

    private var savedRemainingMillis = initialSettings.intervalMillis
    private var microphoneActive = false
    private var exceptionActive = false
    private var screenAvailable = true
    private var lockedAtMillis: Long? = null
    private var nextBlackoutMillisOverride: Long? = null
    private var exceptionResumePhase = Phase.COUNTDOWN

    fun start(
        now: Long,
        screenAvailable: Boolean,
        microphoneActive: Boolean,
        exceptionActive: Boolean = false,
    ) {
        this.screenAvailable = screenAvailable
        this.microphoneActive = microphoneActive
        this.exceptionActive = exceptionActive
        lockedAtMillis = if (screenAvailable) null else now
        nextBlackoutMillisOverride = null
        exceptionResumePhase = Phase.COUNTDOWN
        savedRemainingMillis = settings.intervalMillis
        phase = when {
            !screenAvailable -> Phase.LOCKED
            exceptionActive -> Phase.APP_PAUSED
            settings.pauseForMicrophone && microphoneActive -> Phase.MIC_PAUSED
            else -> Phase.COUNTDOWN
        }
        deadlineMillis = if (phase == Phase.COUNTDOWN) now + settings.intervalMillis else 0
    }

    fun stop() {
        phase = Phase.STOPPED
        deadlineMillis = 0
        lockedAtMillis = null
        nextBlackoutMillisOverride = null
        exceptionResumePhase = Phase.COUNTDOWN
    }

    fun updateSettings(newSettings: BlackSettings, now: Long) {
        settings = newSettings
        if (phase == Phase.STOPPED) return
        start(now, screenAvailable, microphoneActive, exceptionActive)
    }

    fun setMicrophoneActive(active: Boolean, now: Long) {
        if (microphoneActive == active) return
        microphoneActive = active
        if (phase == Phase.STOPPED || !settings.pauseForMicrophone) return

        if (active) {
            if (phase == Phase.APP_PAUSED &&
                (exceptionResumePhase == Phase.WARNING || exceptionResumePhase == Phase.BLACKOUT)) {
                savedRemainingMillis = settings.intervalMillis
                exceptionResumePhase = Phase.COUNTDOWN
                nextBlackoutMillisOverride = null
            } else if (phase != Phase.LOCKED && phase != Phase.APP_PAUSED) {
                savedRemainingMillis = if (phase == Phase.COUNTDOWN) {
                    max(0, deadlineMillis - now)
                } else {
                    // An interrupted warning or blackout is discarded.
                    settings.intervalMillis
                }
                if (phase == Phase.WARNING || phase == Phase.BLACKOUT) {
                    nextBlackoutMillisOverride = null
                }
                phase = Phase.MIC_PAUSED
                deadlineMillis = 0
            }
        } else if (phase == Phase.MIC_PAUSED && screenAvailable) {
            phase = Phase.COUNTDOWN
            deadlineMillis = now + savedRemainingMillis
        }
    }

    fun setExceptionActive(active: Boolean, now: Long) {
        if (exceptionActive == active) return
        exceptionActive = active
        if (phase == Phase.STOPPED) return

        if (active) {
            if (phase != Phase.LOCKED) {
                savedRemainingMillis = when (phase) {
                    Phase.COUNTDOWN, Phase.WARNING, Phase.BLACKOUT -> max(0, deadlineMillis - now)
                    Phase.MIC_PAUSED, Phase.APP_PAUSED -> savedRemainingMillis
                    else -> settings.intervalMillis
                }
                exceptionResumePhase = when (phase) {
                    Phase.COUNTDOWN, Phase.WARNING, Phase.BLACKOUT -> phase
                    else -> Phase.COUNTDOWN
                }
                phase = Phase.APP_PAUSED
                deadlineMillis = 0
            }
        } else if (phase == Phase.APP_PAUSED && screenAvailable) {
            phase = if (settings.pauseForMicrophone && microphoneActive) {
                Phase.MIC_PAUSED
            } else {
                exceptionResumePhase
            }
            deadlineMillis = if (phase == Phase.MIC_PAUSED) 0 else now + savedRemainingMillis
            exceptionResumePhase = Phase.COUNTDOWN
        }
    }

    fun setScreenAvailable(available: Boolean, now: Long) {
        if (screenAvailable == available) return
        screenAvailable = available
        if (phase == Phase.STOPPED) return

        if (!available) {
            savedRemainingMillis = when {
                phase == Phase.COUNTDOWN -> max(0, deadlineMillis - now)
                phase == Phase.MIC_PAUSED || phase == Phase.APP_PAUSED -> savedRemainingMillis
                else -> settings.intervalMillis
            }
            if (phase == Phase.WARNING || phase == Phase.BLACKOUT) {
                nextBlackoutMillisOverride = null
            }
            lockedAtMillis = now
            phase = Phase.LOCKED
            deadlineMillis = 0
        } else {
            if (lockedAtMillis?.let { now - it >= LOCK_RESET_MILLIS } == true) {
                savedRemainingMillis = settings.intervalMillis
                exceptionResumePhase = Phase.COUNTDOWN
                nextBlackoutMillisOverride = null
            }
            lockedAtMillis = null
            phase = when {
                exceptionActive -> Phase.APP_PAUSED
                settings.pauseForMicrophone && microphoneActive -> Phase.MIC_PAUSED
                else -> Phase.COUNTDOWN
            }
            if (phase != Phase.APP_PAUSED) exceptionResumePhase = Phase.COUNTDOWN
            deadlineMillis = if (phase == Phase.COUNTDOWN) now + savedRemainingMillis else 0
        }
    }

    fun tick(now: Long) {
        if (phase == Phase.LOCKED && lockedAtMillis?.let { now - it >= LOCK_RESET_MILLIS } == true) {
            savedRemainingMillis = settings.intervalMillis
            exceptionResumePhase = Phase.COUNTDOWN
            nextBlackoutMillisOverride = null
        }
        while (deadlineMillis != 0L && now >= deadlineMillis) {
            val previousDeadline = deadlineMillis
            phase = when (phase) {
                Phase.COUNTDOWN -> {
                    deadlineMillis = previousDeadline + settings.warningMillis
                    Phase.WARNING
                }
                Phase.WARNING -> {
                    deadlineMillis = previousDeadline +
                        (nextBlackoutMillisOverride ?: settings.blackoutMillis)
                    nextBlackoutMillisOverride = null
                    Phase.BLACKOUT
                }
                Phase.BLACKOUT -> {
                    deadlineMillis = previousDeadline + settings.intervalMillis
                    Phase.COUNTDOWN
                }
                else -> {
                    deadlineMillis = 0
                    break
                }
            }
        }
    }

    fun cancel(now: Long): Boolean {
        if (phase != Phase.WARNING && phase != Phase.BLACKOUT) return false
        phase = Phase.COUNTDOWN
        deadlineMillis = now + settings.intervalMillis
        nextBlackoutMillisOverride = null
        return true
    }

    fun testNow(
        now: Long,
        warningMillis: Long = settings.warningMillis,
        blackoutMillis: Long = settings.blackoutMillis,
    ): Boolean {
        if (phase != Phase.COUNTDOWN) return false
        phase = Phase.WARNING
        deadlineMillis = now + warningMillis
        nextBlackoutMillisOverride = blackoutMillis
        tick(now)
        return true
    }

    fun remainingMillis(now: Long): Long = when (phase) {
        Phase.COUNTDOWN, Phase.WARNING, Phase.BLACKOUT -> max(0, deadlineMillis - now)
        Phase.MIC_PAUSED, Phase.APP_PAUSED, Phase.LOCKED -> savedRemainingMillis
        else -> 0
    }
}
