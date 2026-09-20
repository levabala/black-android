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

    @Test fun unlockStartsFreshIntervalAndBootCanStartLocked() {
        val timer = SchedulerCore(settings)
        timer.start(0, false, false)
        assertEquals(Phase.LOCKED, timer.phase)
        timer.setScreenAvailable(true, 30_000)
        assertEquals(20_000, timer.remainingMillis(30_000))
        timer.setScreenAvailable(false, 35_000)
        timer.setScreenAvailable(true, 60_000)
        assertEquals(20_000, timer.remainingMillis(60_000))
    }

    @Test fun testNowHonorsMicrophonePause() {
        val timer = SchedulerCore(settings)
        timer.start(0, true, true)
        assertFalse(timer.testNow(0))
        timer.setMicrophoneActive(false, 1_000)
        assertTrue(timer.testNow(1_000))
        assertEquals(Phase.WARNING, timer.phase)
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

    @Test fun optionalLockSettingPreservesRemainingCountdown() {
        val timer = SchedulerCore(settings.copy(resetAfterLock = false))
        timer.start(1_000, true, false)
        timer.setScreenAvailable(false, 6_000)
        timer.setScreenAvailable(true, 100_000)
        assertEquals(15_000, timer.remainingMillis(100_000))
    }
}
