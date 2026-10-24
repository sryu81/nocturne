package com.nocturne.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RigBeaconTest {

    // Verbatim packet captured from the rig's astro-beacon.
    private val real = """{"magic": "rpi5_astro", "hostname": "astros2", "ips": {"wlan0": "10.0.0.184"}, "uptime": 4337, "throttled": "0x50000"}"""

    @Test fun parsesRealBeacon() {
        val rig = parseBeacon(real, "10.0.0.184", nowMs = 42L)!!
        assertEquals(DiscoveredRig("astros2", "10.0.0.184", 4337, "0x50000", 42L), rig)
    }

    @Test fun usesSourceAddressNotIpsMap() {
        assertEquals("192.168.4.1", parseBeacon(real, "192.168.4.1")!!.address)
    }

    @Test fun nullThrottledAndMissingFieldsTolerated() {
        val rig = parseBeacon("""{"magic":"rpi5_astro","throttled":null}""", "10.0.0.9")!!
        assertEquals("10.0.0.9", rig.hostname)
        assertNull(rig.uptimeSec)
        assertNull(rig.throttled)
    }

    @Test fun rejectsWrongMagicAndGarbage() {
        assertNull(parseBeacon("""{"magic":"other","hostname":"x"}""", "10.0.0.1"))
        assertNull(parseBeacon("not json", "10.0.0.1"))
        assertNull(parseBeacon("[1,2]", "10.0.0.1"))
    }
}
