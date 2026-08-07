package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the real-rig crash: every INDI device exposes
 * `POLLING_PERIOD` with format `"%.f"`, which Android's Formatter mis-parses
 * into `IllegalFormatPrecisionException(102)` (the ASCII code of 'f') — see
 * [formatIndiNumber]'s doc. Format strings below are byte-for-byte what a
 * real rig (LX200 OnStep mount, ToupTek cameras, ZWO EAF/EFW) reported live
 * via `device_get`, not guessed.
 */
class IndiNumberFormatTest {

    @Test fun `precision-less dot defaults to 0, matching C`() {
        assertEquals("1000", formatIndiNumber("%.f", 1000.0))
        assertEquals("100", formatIndiNumber("%.f", 100.0))
    }

    @Test fun `plain width-only float defaults to C's precision 6`() {
        assertEquals("1013.000000", formatIndiNumber("%4f", 1013.0))
    }

    @Test fun `explicit precision is preserved`() {
        assertEquals("23.40", formatIndiNumber("%5.2f", 23.4))
        assertEquals("1.000000", formatIndiNumber("%.6f", 1.0))
    }

    @Test fun `bare general conversion does not crash`() {
        // Real value: SCOPE_INFO.FOCAL_LENGTH, "%g" — just needs to not throw and
        // to contain the magnitude; Java's own %g default precision is fine here.
        assertEquals(true, formatIndiNumber("%g", 90.0).startsWith("90"))
    }

    @Test fun `sexagesimal m conversion renders DD-MM-SS, not a Java crash`() {
        // Real value: EQUATORIAL_EOD_COORD.RA/DEC, "%010.6m"
        assertEquals("12:30:00.0", formatIndiNumber("%010.6m", 12.5))
        // GEOGRAPHIC_COORD.LAT, "%012.8m"
        assertEquals("37:46:39.00", formatIndiNumber("%012.8m", 37.7775))
        // Negative (southern latitude / negative DEC)
        assertEquals("-5:15:00.0", formatIndiNumber("%010.6m", -5.25))
    }

    @Test fun `simulator fixture unit suffixes are preserved`() {
        assertEquals("-10.0 °C", formatIndiNumber("%.1f °C", -10.0))
        assertEquals("45°", formatIndiNumber("%.0f°", 45.0))
        assertEquals("68 %", formatIndiNumber("%.0f %", 68.0))
    }

    @Test fun `unrecognized format falls back instead of crashing`() {
        assertEquals("5", formatIndiNumber("not a format", 5.0))
        assertEquals("5.50", formatIndiNumber("%%broken%%", 5.5))
    }
}
