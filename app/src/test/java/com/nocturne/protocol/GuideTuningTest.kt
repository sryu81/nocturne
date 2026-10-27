package com.nocturne.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTuningTest {
    @Test
    fun `decodes the guider control loop keys of a real reply, ints for the max pulse boxes`() {
        // kstarsrc of the rig on 2026-10-26: RA P 0.70, DEC P 0.55, max pulse 35; the Guide
        // checkboxes have no kcfg_ prefix (`dECGuideEnabled` is in the live capture above).
        val event = EkosEventCodec.decode(
            """{"payload":{"kcfg_RAProportionalGain":0.7,"kcfg_DECProportionalGain":0.55,
            "kcfg_RAIntegralGain":0,"kcfg_DECIntegralGain":0.1,"kcfg_RAMinimumPulseArcSec":0.2,
            "kcfg_DECMinimumPulseArcSec":0.25,"kcfg_RAMaximumPulseArcSec":35,"kcfg_DECMaximumPulseArcSec":35,
            "rAGuideEnabled":true,"dECGuideEnabled":false},"type":"guide_get_all_settings"}""",
        ) as EkosEvent.GuideSettings
        val g = event.settings
        assertEquals(0.7, g.tuning(GuideTuning.RA_GAIN), 0.0)
        assertEquals(0.55, g.tuning(GuideTuning.DEC_GAIN), 0.0)
        assertEquals(0.1, g.tuning(GuideTuning.DEC_INTEGRAL), 0.0)
        assertEquals(0.25, g.tuning(GuideTuning.DEC_MIN_PULSE), 0.0)
        assertEquals(35.0, g.tuning(GuideTuning.RA_MAX_PULSE), 0.0)
        assertTrue(g.axisEnabled(GuideAxis.RA))
        assertFalse(g.axisEnabled(GuideAxis.DEC))
    }

    @Test
    fun `a reply without the keys keeps the defaults`() {
        val g = (EkosEventCodec.decode("""{"payload":{"guideExposure":1},"type":"guide_get_all_settings"}""") as EkosEvent.GuideSettings).settings
        assertEquals(0.75, g.tuning(GuideTuning.RA_GAIN), 0.0)
        assertTrue(g.axisEnabled(GuideAxis.RA))
    }

    @Test
    fun `withTuning writes one field and leaves the rest`() {
        val g = WireGuideSettings()
        val out = g.withTuning(GuideTuning.RA_GAIN, 0.5)
        assertEquals(0.5, out.kcfg_RAProportionalGain, 0.0)
        assertEquals(g.kcfg_DECProportionalGain, out.kcfg_DECProportionalGain, 0.0)
        assertEquals(g.copy(kcfg_RAProportionalGain = 0.5), out)
    }

    @Test
    fun `values are clamped to the spin box range and the max pulse is rounded to an int`() {
        assertEquals(1.0, GuideTuning.RA_GAIN.clamp(7.0), 0.0)
        assertEquals(0.0, GuideTuning.RA_GAIN.clamp(-1.0), 0.0)
        assertEquals(36.0, GuideTuning.RA_MAX_PULSE.clamp(35.6), 0.0)
        assertEquals(3600.0, GuideTuning.DEC_MAX_PULSE.clamp(9999.0), 0.0)
        assertEquals(36, WireGuideSettings().withTuning(GuideTuning.RA_MAX_PULSE, 35.6).kcfg_RAMaximumPulseArcSec)
    }

    @Test
    fun `every tuning has a distinct wire key with the kcfg prefix and the axes do not`() {
        val keys = GuideTuning.values().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("kcfg_") })
        assertTrue(GuideAxis.values().none { it.key.startsWith("kcfg_") })
    }
}
