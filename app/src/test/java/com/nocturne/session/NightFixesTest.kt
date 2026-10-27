package com.nocturne.session

import com.nocturne.protocol.SchedulerJobStatus
import com.nocturne.protocol.WireAlignSettings
import com.nocturne.protocol.WireSchedulerJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightFixesTest {
    private fun job(state: Int) = WireSchedulerJob(name = "M 31_j2", state = state)

    // ── Scheduler running, B8 ──

    @Test
    fun aRunningStatusMeansRunning() {
        assertTrue(AppState(schedulerStatus = SCHEDULER_STATE_RUNNING).schedulerIsRunning())
    }

    @Test
    fun anIdleStatusMeansNotRunningEvenWithABusyJobListed() {
        val s = AppState(schedulerStatus = 0, wireSchedulerJobs = listOf(job(SchedulerJobStatus.BUSY)))
        assertFalse(s.schedulerIsRunning())
    }

    @Test
    fun anUnknownStatusIsReadFromTheJobList() {
        // After a reconnect: get_states has no Scheduler state, but the job Ekos works on is Busy.
        assertTrue(AppState(schedulerStatus = null, wireSchedulerJobs = listOf(job(SchedulerJobStatus.BUSY))).schedulerIsRunning())
        assertFalse(AppState(schedulerStatus = null, wireSchedulerJobs = listOf(job(SchedulerJobStatus.IDLE))).schedulerIsRunning())
        assertFalse(AppState(schedulerStatus = null).schedulerIsRunning())
    }

    // ── Plate solve here must not slew, B9 ──

    @Test
    fun aSolveHereResetsASlewActionAndAnUnknownOne() {
        assertTrue(solverActionNeedsReset(null))
        assertTrue(solverActionNeedsReset(WireAlignSettings(nothingR = false, slewR = true, syncR = false)))
        assertFalse(solverActionNeedsReset(WireAlignSettings(nothingR = true)))
        assertFalse(solverActionNeedsReset(WireAlignSettings(nothingR = false, syncR = true)))
    }

    @Test
    fun centeringNeverRestoresSlewAndKeepsASyncChoice() {
        assertEquals(Triple(true, false, false), restoredSolverAction(null))
        assertEquals(Triple(true, false, false), restoredSolverAction(WireAlignSettings(nothingR = false, slewR = true)))
        assertEquals(Triple(true, false, false), restoredSolverAction(WireAlignSettings(nothingR = true)))
        assertEquals(Triple(false, false, true), restoredSolverAction(WireAlignSettings(nothingR = false, syncR = true)))
    }
}
