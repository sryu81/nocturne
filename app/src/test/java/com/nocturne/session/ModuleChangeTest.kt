package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModuleChangeTest {

    @Test
    fun stampsOnlyTheModulesWhoseStatusChanged() {
        val before = AppState(wireGuideStatus = "Idle", wireMountStatus = "Tracking")
        val after = before.copy(wireGuideStatus = "Guiding")
        val stamped = before.stampModuleChanges(after, nowMs = 1000)
        assertEquals(mapOf("Guide" to 1000L), stamped.moduleChangedAt)
    }

    @Test
    fun noChangeLeavesTheStateUntouched() {
        val s = AppState(wireGuideStatus = "Idle")
        assertEquals(s, s.stampModuleChanges(s.copy(), nowMs = 1000))
    }

    @Test
    fun firstReportCountsAsAChangeAndEarlierStampsSurvive() {
        val first = AppState().stampModuleChanges(AppState(wireCaptureStatus = "Idle"), nowMs = 10)
        val next = first.stampModuleChanges(first.copy(wireFocusStatus = "Complete"), nowMs = 20)
        assertEquals(mapOf("Cam" to 10L, "Focus" to 20L), next.moduleChangedAt)
    }

    @Test
    fun schedulerHasNoStatusUntilItsFirstPush() {
        assertNull(AppState().moduleStatus("Sched"))
        assertEquals("Running", AppState(schedulerRunning = true, schedulerStatus = 2).moduleStatus("Sched"))
        assertEquals("Idle", AppState(schedulerRunning = false, schedulerStatus = 0).moduleStatus("Sched"))
    }
}
