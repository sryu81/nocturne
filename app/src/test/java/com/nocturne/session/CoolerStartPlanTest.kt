package com.nocturne.session

import com.nocturne.protocol.WireCaptureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoolerStartPlanTest {
    private val cam = "ToupTek ATR2600M"

    private fun temperature() = IndiProperty.NumberProp("CCD_TEMPERATURE", "Temperature", "Main", 16.4, -50.0, 50.0, 1.0)
    private fun power(v: Double) = IndiProperty.NumberProp("CCD_COOLER_POWER", "Cooler power", "Main", v, 0.0, 100.0, 1.0)

    private val warm = AppState(
        primaryTrain = TrainAssignment(camera = cam),
        indiProps = mapOf(cam to listOf(temperature())),
        coolTarget = -5.0,
    )

    @Test
    fun aColdStartSwitchesTheCoolerOnAtTheSetPoint() {
        val plan = warm.coolerStartPlan()
        assertEquals(CoolerStartPlan(cam, -5.0, enforce = true), plan)
    }

    @Test
    fun aCoolerCommandedOffIsSwitchedBackOn() {
        assertEquals(cam, warm.copy(coolerCommandedOn = false).coolerStartPlan()?.camera)
    }

    @Test
    fun aCoolerAlreadyOnIsLeftAlone() {
        assertNull(warm.copy(coolerCommandedOn = true).coolerStartPlan())
        assertNull(warm.copy(indiProps = mapOf(cam to listOf(temperature(), power(40.0)))).coolerStartPlan())
    }

    @Test
    fun aCameraThatCannotCoolIsLeftAlone() {
        assertNull(warm.copy(indiProps = emptyMap()).coolerStartPlan())
        assertNull(warm.copy(primaryTrain = TrainAssignment()).coolerStartPlan())
    }

    @Test
    fun theSetPointFallsBackToEkosAndNoneMeansNoPlan() {
        val ekos = warm.copy(coolTarget = null, wireCaptureSettings = WireCaptureSettings(cameraTemperatureN = -10.0))
        assertEquals(-10.0, ekos.coolerStartPlan()?.targetC)
        assertNull(warm.copy(coolTarget = null).coolerStartPlan())
    }

    @Test
    fun enforceIsOnlyAskedForWhenEkosDoesNotHaveItYet() {
        val enforced = warm.copy(wireCaptureSettings = WireCaptureSettings(cameraTemperatureS = true))
        assertEquals(false, enforced.coolerStartPlan()?.enforce)
    }
}
