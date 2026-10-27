package com.nocturne.ui.controls

import com.nocturne.protocol.WirePolarVector
import com.nocturne.session.AppState
import com.nocturne.session.ControlsModule
import com.nocturne.session.IndiProperty
import com.nocturne.session.LiveDevice
import com.nocturne.session.TrainAssignment
import com.nocturne.session.formatHa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsSummaryTest {
    private val idle = AppState()

    private fun summary(state: AppState, module: ControlsModule) = controlsSummary(state, module)

    // ── Mount ──

    @Test
    fun trackingMountShowsPierSideAndHourAngle() {
        val s = idle.copy(mountTracking = true, wireMountPierSide = 0, mountHa = 20.0)
        val m = summary(s, ControlsModule.MOUNT)
        assertEquals("Tracking · East · HA ${formatHa(20.0)}", m.line)
        assertFalse(m.active)
    }

    @Test
    fun anUnknownPierSideIsLeftOut() {
        val s = idle.copy(mountTracking = false, wireMountPierSide = -1, mountHa = 90.0)
        assertEquals("tracking off · HA ${formatHa(90.0)}", summary(s, ControlsModule.MOUNT).line)
    }

    @Test
    fun parkedAndTrackingOffAreNamed() {
        assertEquals("parked", summary(idle.copy(mountParked = true), ControlsModule.MOUNT).line)
        assertEquals("tracking off", summary(idle.copy(mountTracking = false), ControlsModule.MOUNT).line)
    }

    @Test
    fun unknownMountIsNa() {
        val m = summary(idle, ControlsModule.MOUNT)
        assertEquals("N/A", m.line)
        assertFalse(m.active)
    }

    @Test
    fun aManualSlewIsActiveWarnedAndStoppable() {
        val m = summary(idle.copy(slewDir = "N", mountTracking = true), ControlsModule.MOUNT)
        assertTrue(m.line.startsWith("slewing N"))
        assertTrue(m.active && m.warn && m.stoppable)
    }

    @Test
    fun aGotoOrParkInProgressIsActiveWarnedAndStoppable() {
        for (status in listOf("Slewing", "Moving", "Parking")) {
            val m = summary(idle.copy(wireMountStatus = status), ControlsModule.MOUNT)
            assertEquals(status.lowercase(), m.line)
            assertTrue(status, m.active && m.warn && m.stoppable)
        }
    }

    // ── Camera ──

    private val camera = "Cam"
    private fun cameraState(coolerOn: Boolean?, power: Double?) = idle.copy(
        primaryTrain = TrainAssignment(camera = camera),
        wireDevices = listOf(LiveDevice(camera, connected = true, roles = emptySet())),
        coolerCommandedOn = coolerOn,
        indiProps = mapOf(
            camera to listOfNotNull(
                IndiProperty.NumberProp("CCD_TEMPERATURE", "Temperature", "Main", -5.0, -50.0, 50.0, 1.0),
                power?.let { IndiProperty.NumberProp("CCD_COOLER_POWER", "Power", "Main", it, 0.0, 100.0, 1.0) },
            ),
        ),
    )

    @Test
    fun aCoolingCameraShowsTemperaturePowerAndNothingElseWithoutSettings() {
        assertEquals("-5.0 °C · 79%", summary(cameraState(true, 79.0), ControlsModule.CAMERA).line)
    }

    @Test
    fun aCoolerThatIsOffSaysSo() {
        assertEquals("cooler off · -5.0 °C", summary(cameraState(false, null), ControlsModule.CAMERA).line)
    }

    @Test
    fun noCameraIsNa() {
        assertEquals("N/A", summary(idle, ControlsModule.CAMERA).line)
    }

    @Test
    fun cameraIsActiveOnlyForKnownWorkingCaptureStates() {
        assertTrue(summary(idle.copy(wireCaptureStatus = "Capturing"), ControlsModule.CAMERA).active)
        assertTrue(summary(idle.copy(wireCaptureStatus = "Setting Temperature"), ControlsModule.CAMERA).active)
        assertFalse(summary(idle.copy(wireCaptureStatus = "Idle"), ControlsModule.CAMERA).active)
        assertFalse(summary(idle.copy(wireCaptureStatus = "Something New"), ControlsModule.CAMERA).active)
    }

    // ── Focuser ──

    @Test
    fun focuserShowsPositionAndStatus() {
        assertEquals("12450 · idle", summary(idle.copy(focPos = 12450, wireFocusStatus = "Idle"), ControlsModule.FOCUSER).line)
        assertEquals("N/A", summary(idle, ControlsModule.FOCUSER).line)
    }

    @Test
    fun runningAutofocusIsActive() {
        val f = summary(idle.copy(focPos = 12450, focusRunning = true, wireFocusStatus = "In Progress"), ControlsModule.FOCUSER)
        assertEquals("12450 · autofocus running", f.line)
        assertTrue(f.active)
        assertFalse(f.warn)
    }

    // ── Plate solve ──

    @Test
    fun plateSolveShowsStatusAndLastAccuracy() {
        assertEquals("Complete · 3.2″", summary(idle.copy(wireAlignStatus = "Complete", alignAccuracyHistory = listOf(8.0, 3.2)), ControlsModule.PLATE_SOLVE).line)
        assertEquals("Failed", summary(idle.copy(wireAlignStatus = "Failed"), ControlsModule.PLATE_SOLVE).line)
        assertEquals("N/A", summary(idle, ControlsModule.PLATE_SOLVE).line)
    }

    @Test
    fun aSolveInProgressIsActive() {
        val p = summary(idle.copy(wireAlignStatus = "In Progress"), ControlsModule.PLATE_SOLVE)
        assertEquals("solving…", p.line)
        assertTrue(p.active)
    }

    // ── Guide ──

    @Test
    fun guidingShowsStatusAndTotalRms() {
        val g = summary(idle.copy(guiding = true, wireGuideStatus = "Guiding", guideRmsRA = 0.3, guideRmsDE = 0.4), ControlsModule.GUIDE)
        assertEquals("Guiding · 0.50″ RMS", g.line)
        assertTrue(g.active)
    }

    @Test
    fun anIdleGuiderIsNotActiveAndHidesStaleRms() {
        val g = summary(idle.copy(guiding = false, wireGuideStatus = "Idle", guideRmsRA = 0.3, guideRmsDE = 0.4), ControlsModule.GUIDE)
        assertEquals("Idle", g.line)
        assertFalse(g.active)
    }

    // ── Polar alignment ──

    @Test
    fun polarAlignmentNotStartedIsQuiet() {
        val p = summary(idle, ControlsModule.POLAR_ALIGNMENT)
        assertEquals("not started", p.line)
        assertFalse(p.active || p.stoppable)
        assertEquals("not started", summary(idle.copy(wirePolarStage = "Idle"), ControlsModule.POLAR_ALIGNMENT).line)
    }

    @Test
    fun polarMeasuringShowsTheImageAndIsWarned() {
        val p = summary(idle.copy(polarRunning = true, wirePolarStage = "Second Capture"), ControlsModule.POLAR_ALIGNMENT)
        assertEquals("measuring · image 2 of 3", p.line)
        assertTrue(p.active && p.warn && p.stoppable)
    }

    @Test
    fun polarRefreshingShowsTheTotalErrorOnlyOnceComputed() {
        val s = idle.copy(polarRunning = true, wirePolarStage = "Refreshing")
        assertEquals("refreshing", summary(s, ControlsModule.POLAR_ALIGNMENT).line)
        assertEquals("refreshing · total 0.12°", summary(s.copy(wirePolarUpdatedError = 0.12), ControlsModule.POLAR_ALIGNMENT).line)
        // -1 means "not computed yet".
        assertEquals("refreshing", summary(s.copy(wirePolarVector = WirePolarVector(error = -1.0)), ControlsModule.POLAR_ALIGNMENT).line)
    }

    // ── Pinning ──

    @Test
    fun activeModulesKeepMenuOrder() {
        val s = idle.copy(guiding = true, focusRunning = true, slewDir = "E")
        assertEquals(listOf(ControlsModule.MOUNT, ControlsModule.FOCUSER, ControlsModule.GUIDE), activeModules(s))
        assertTrue(activeModules(idle).isEmpty())
    }
}
