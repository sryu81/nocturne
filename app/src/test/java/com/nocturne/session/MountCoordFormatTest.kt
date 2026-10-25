package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MountCoordFormatTest {
    @Test fun raIsDegreesShownAsHours() {
        assertEquals("06:04:00", formatRa(91.0))
        assertEquals("00:00:00", formatRa(0.0))
        assertEquals("00:00:00", formatRa(359.9999999)) // rounds up through 24 h
        assertEquals("N/A", formatRa(null))
    }

    @Test fun decKeepsSignAndPole() {
        assertEquals("+90:00:00", formatDec(90.0))
        assertEquals("-05:30:00", formatDec(-5.5))
        assertEquals("+00:00:00", formatDec(-0.0001)) // no "-00:00:00"
        assertEquals("N/A", formatDec(null))
    }

    @Test fun haIsSignedAndWrapsLikeEkos() {
        assertEquals("+1h30m", formatHa(22.5))
        assertEquals("-1h30m", formatHa(337.5)) // 22.5 h on the 0..24 h clock
        assertEquals("+0h00m", formatHa(0.0))
        assertEquals("+12h00m", formatHa(180.0))
        assertEquals("N/A", formatHa(null))
    }

    @Test fun zenithAngleIsNinetyMinusAltitude() {
        assertEquals(30.0, zenithAngle(60.0)!!, 1e-9)
        assertEquals(-5.0, zenithAngle(95.0)!!, 1e-9)
        assertNull(zenithAngle(null))
        assertEquals("N/A", formatDegrees(null))
    }
}
