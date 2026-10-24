package com.nocturne.session

import com.nocturne.protocol.WireCaptureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryImageSizeTest {

    private fun state(w: Int?, h: Int?, bin: Int = 1) = AppState(
        primaryTrain = TrainAssignment(camera = "ToupTek ATR2600M"),
        wireCcdInfoByDevice = mapOf("ToupTek ATR2600M" to CcdInfo(6224, 4168, 3.76)),
        wireCaptureSettings = WireCaptureSettings(
            captureFrameXN = 0, captureFrameYN = 0, captureFrameWN = w, captureFrameHN = h,
            captureBinHN = bin, captureBinVN = bin,
        ),
    )

    @Test
    fun `full sensor is not a crop`() {
        val s = state(6224, 4168).primaryImageSize()!!
        assertFalse(s.cropped)
        assertEquals(6224, s.outW)
    }

    @Test
    fun `the simulator's 1280x1024 frame on the ATR2600M is a crop`() {
        assertTrue(state(1280, 1024).primaryImageSize()!!.cropped)
    }

    @Test
    fun `binning divides the output size`() {
        val s = state(6224, 4168, bin = 2).primaryImageSize()!!
        assertEquals(3112, s.outW)
        assertEquals(2084, s.outH)
        assertFalse(s.cropped)
    }

    @Test
    fun `no frame reported means nothing to show`() {
        assertNull(state(null, null).primaryImageSize())
    }
}
