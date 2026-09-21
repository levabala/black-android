package com.levabala.blackandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerCoreTest {
    private val settings = BlackSettings(intervalMillis = 20_000, warningMillis = 10_000, blackoutMillis = 20_000)

    @Test fun normalCycleAndCancel() {
        val timer = SchedulerCore(settings)
        timer.start(1_000, true, false)
        timer.tick(21_000)
        assertEquals(Phase.WARNING, timer.phase)
        assertEquals(10_000, timer.remainingMillis(21_000))
        timer.tick(31_000)
        assertEquals(Phase.BLACKOUT, timer.phase)
        assertTrue(timer.cancel(32_000))
        assertEquals(Phase.COUNTDOWN, timer.phase)
        assertEquals(20_000, timer.remainingMillis(32_000))
        assertFalse(timer.cancel(32_000))
    }

    @Test fun microphonePreservesCountdownButDiscardsWarning() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.setMicrophoneActive(true, 5_000)
        assertEquals(Phase.MIC_PAUSED, timer.phase)
        timer.tick(50_000)
        timer.setMicrophoneActive(false, 50_000)
        assertEquals(15_000, timer.remainingMillis(50_000))
        timer.tick(65_000)
        assertEquals(Phase.WARNING, timer.phase)
        timer.setMicrophoneActive(true, 66_000)
        timer.setMicrophoneActive(false, 100_000)
        assertEquals(20_000, timer.remainingMillis(100_000))
    }

    @Test fun shortLockPreservesRemainingCountdownAndBootCanStartLocked() {
        val timer = SchedulerCore(settings)
        timer.start(0, false, false)
        assertEquals(Phase.LOCKED, timer.phase)
        timer.setScreenAvailable(true, 30_000)
        assertEquals(20_000, timer.remainingMillis(30_000))
        timer.setScreenAvailable(false, 35_000)
        assertEquals(15_000, timer.remainingMillis(60_000))
        timer.setScreenAvailable(true, 94_999)
        assertEquals(15_000, timer.remainingMillis(94_999))
        timer.tick(109_999)
        assertEquals(Phase.WARNING, timer.phase)
    }

    @Test fun oneMinuteLockStartsFreshInterval() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.setScreenAvailable(false, 5_000)
        assertEquals(15_000, timer.remainingMillis(64_999))
        timer.tick(65_000)
        assertEquals(20_000, timer.remainingMillis(65_000))
        timer.setScreenAvailable(true, 65_000)
        assertEquals(20_000, timer.remainingMillis(65_000))
        timer.tick(80_000)
        assertEquals(Phase.COUNTDOWN, timer.phase)
    }

    @Test fun shortLockAlsoPreservesMicrophonePausedCountdown() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.setMicrophoneActive(true, 5_000)
        timer.setScreenAvailable(false, 6_000)
        timer.setScreenAvailable(true, 20_000)
        assertEquals(Phase.MIC_PAUSED, timer.phase)
        timer.setMicrophoneActive(false, 25_000)
        assertEquals(15_000, timer.remainingMillis(25_000))
    }

    @Test fun exceptionPausesAndPreservesCountdown() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.setExceptionActive(true, 5_000)
        assertEquals(Phase.APP_PAUSED, timer.phase)
        assertEquals(15_000, timer.remainingMillis(50_000))
        timer.setExceptionActive(false, 50_000)
        assertEquals(Phase.COUNTDOWN, timer.phase)
        assertEquals(15_000, timer.remainingMillis(50_000))
    }

    @Test fun exceptionFreezesWarningAndGetsPriorityOverMicrophone() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.tick(20_000)
        timer.setExceptionActive(true, 21_000)
        assertEquals(Phase.APP_PAUSED, timer.phase)
        assertEquals(9_000, timer.remainingMillis(21_000))
        timer.setMicrophoneActive(true, 22_000)
        assertEquals(Phase.APP_PAUSED, timer.phase)
        timer.setExceptionActive(false, 23_000)
        assertEquals(Phase.MIC_PAUSED, timer.phase)
    }

    @Test fun exceptionResumesWarningWhenMicrophoneIsIdle() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.tick(20_000)
        timer.setExceptionActive(true, 21_000)
        timer.setExceptionActive(false, 50_000)
        assertEquals(Phase.WARNING, timer.phase)
        assertEquals(9_000, timer.remainingMillis(50_000))
    }

    @Test fun exceptionRemainsPausedAcrossLockAndUnlock() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.setExceptionActive(true, 5_000)
        timer.setScreenAvailable(false, 6_000)
        assertEquals(Phase.LOCKED, timer.phase)
        timer.setScreenAvailable(true, 20_000)
        assertEquals(Phase.APP_PAUSED, timer.phase)
    }

    @Test fun testNowHonorsMicrophonePause() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, true)
        assertFalse(timer.testNow(0))
        timer.setMicrophoneActive(false, 1_000)
        assertTrue(timer.testNow(1_000))
        assertEquals(Phase.WARNING, timer.phase)
    }

    @Test fun testNowCanUseASeparateShortWarningWithoutChangingSettings() {
        val timer = SchedulerCore(settings.copy(warningMillis = 120_000))
        timer.start(0, true, false)
        assertTrue(timer.testNow(1_000, 10_000, 5_000))
        assertEquals(10_000, timer.remainingMillis(1_000))
        assertEquals(120_000, timer.settings.warningMillis)
        timer.tick(11_000)
        assertEquals(Phase.BLACKOUT, timer.phase)
        assertEquals(5_000, timer.remainingMillis(11_000))
    }

    @Test fun lateTickCatchesUpWithoutLengtheningBlackout() {
        val timer = SchedulerCore(settings)
        timer.start(1_000, true, false)
        timer.tick(32_000)
        assertEquals(Phase.BLACKOUT, timer.phase)
        assertEquals(19_000, timer.remainingMillis(32_000))
        timer.tick(52_000)
        assertEquals(Phase.COUNTDOWN, timer.phase)
        assertEquals(19_000, timer.remainingMillis(52_000))
    }

    @Test fun interruptedWarningIsDismissedOnLock() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, false)
        timer.tick(20_000)
        timer.setScreenAvailable(false, 21_000)
        timer.setScreenAvailable(true, 30_000)
        assertEquals(Phase.COUNTDOWN, timer.phase)
        assertEquals(20_000, timer.remainingMillis(30_000))
    }

    @Test fun shortIntervalSupportsFastManualCycles() {
        val timer = SchedulerCore(settings.copy(intervalMillis = 5_000))
        timer.start(1_000, true, false)
        timer.tick(6_000)
        assertEquals(Phase.WARNING, timer.phase)
        timer.tick(16_000)
        assertEquals(Phase.BLACKOUT, timer.phase)
        timer.tick(36_000)
        assertEquals(Phase.COUNTDOWN, timer.phase)
        assertEquals(5_000, timer.remainingMillis(36_000))
    }
}
