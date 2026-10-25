package com.nocturne.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiHealthTest {

    private val full = """
        {"status":"ok","time":1792916775,"cpu_temp_c":45.0,"uptime_s":88384,"load1":0.49,
         "throttled":"0x50005",
         "sd":{"free":380751441920,"total":1003753218048},
         "usb":[{"path":"/mnt/astro","free":1000,"total":4000}],"future_field":1}
    """.trimIndent()

    @Test
    fun parsesEveryField() {
        val h = parsePiHealth(full, rttMs = 12, nowMs = 5)!!
        assertEquals(45.0, h.cpuTempC!!, 0.0)
        assertEquals(88384L, h.uptimeSec)
        assertEquals(1792916775L, h.timeEpochSec)
        assertEquals(380751441920L, h.sd!!.free)
        assertEquals(listOf(PiVolume("/mnt/astro", 1000, 4000)), h.usb)
        assertEquals(12L, h.rttMs)
    }

    @Test
    fun olderDaemonWithOnlyStatusStillParses() {
        val h = parsePiHealth("""{"status":"ok"}""", rttMs = 3, nowMs = 0)!!
        assertNull(h.cpuTempC)
        assertNull(h.sd)
        assertTrue(h.usb.isEmpty())
        assertFalse(h.underVoltageNow)
    }

    @Test
    fun throttleBitsSeparateNowFromSinceBoot() {
        val h = parsePiHealth(full, 0, 0)!! // 0x50005: bit0 (under-voltage now) + bit2, bits 16 and 18 since boot
        assertTrue(h.underVoltageNow)
        assertTrue(h.throttledSinceBoot)
        val past = parsePiHealth("""{"status":"ok","throttled":"0x50000"}""", 0, 0)!!
        assertFalse(past.underVoltageNow)
        assertTrue(past.throttledSinceBoot)
        val clean = parsePiHealth("""{"status":"ok","throttled":"0x0"}""", 0, 0)!!
        assertFalse(clean.underVoltageNow || clean.throttledSinceBoot)
    }

    @Test
    fun clockSkewIsPhoneMinusPi() {
        val h = parsePiHealth("""{"status":"ok","time":1000}""", 0, 0)!!
        assertEquals(30L, h.clockSkewSec(1_030_000))
        assertNull(parsePiHealth("""{"status":"ok"}""", 0, 0)!!.clockSkewSec(1_030_000))
    }

    @Test
    fun rejectsNonHealthBodies() {
        assertNull(parsePiHealth("not json", 0, 0))
        assertNull(parsePiHealth("""{"status":"error"}""", 0, 0))
        assertNull(parsePiHealth("[]", 0, 0))
    }
}
