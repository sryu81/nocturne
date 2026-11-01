package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetMountTest {
    private val m31 = Target(id = "M 31", common = "Andromeda", coords = "00h42m44s +41°16′08″", ra0 = 0.7122, de0 = 41.2689)

    @Test
    fun theSeparationBetweenTwoPointsIsTheGreatCircleDistance() {
        assertEquals(0.0, angularSeparationDeg(10.0, 20.0, 10.0, 20.0), 1e-9)
        assertEquals(1.0, angularSeparationDeg(50.0, 10.0, 50.0, 11.0), 1e-9)               // one degree in Dec
        assertEquals(1.0, angularSeparationDeg(359.5, 0.0, 0.5, 0.0), 1e-9)                  // across RA 0h
        assertEquals(1.0, angularSeparationDeg(10.0, 60.0, 12.0, 60.0), 0.01)                // RA degrees shrink with cos(dec)
        assertEquals(180.0, angularSeparationDeg(0.0, 90.0, 0.0, -90.0), 1e-9)
        assertEquals(0.0, angularSeparationDeg(0.0, 90.0, 123.0, 90.0), 1e-6)                // the pole is one point
    }

    @Test
    fun theSeparationReadsInTheUnitThatFits() {
        assertEquals("on target", formatSeparation(0.0))
        assertEquals("on target", formatSeparation(0.5 / 60.0))
        assertEquals("12′ off", formatSeparation(0.2))
        assertEquals("59′ off", formatSeparation(0.99))
        assertEquals("1.4° away", formatSeparation(1.4))
        assertEquals("9.9° away", formatSeparation(9.9))
        assertEquals("42° away", formatSeparation(42.3))
    }

    @Test
    fun thePlanPickIsTheCurrentTarget() {
        val s = AppState(targetId = "M 31", targetSnapshot = m31)
        val t = s.currentTarget()!!
        assertEquals("M 31 — Andromeda", t.name)
        assertEquals("00h42m44s +41°16′08″", t.coords)
        assertTrue(t.fromPlan)
        assertEquals(0.7122 * 15.0, t.raDeg!!, 1e-9)
        assertEquals(41.2689, t.decDeg!!, 1e-9)
    }

    @Test
    fun aCustomTargetsCoordinatesAreReadFromItsText() {
        val custom = Target(id = "u1", common = "My field", coords = "20h59m17s +44°31′44″", custom = true)
        val t = AppState(userTargets = listOf(custom), targetId = "u1").currentTarget()!!
        assertEquals("My field", t.name)
        assertEquals((20 + 59 / 60.0 + 17 / 3600.0) * 15.0, t.raDeg!!, 1e-9)
        assertEquals(44 + 31 / 60.0 + 44 / 3600.0, t.decDeg!!, 1e-9)
    }

    @Test
    fun withNoPickEkosTargetNameIsTheFallback() {
        val t = AppState(wireMountTarget = "M 42").currentTarget()!!
        assertEquals("M 42", t.name)
        assertFalse(t.fromPlan)
        assertNull(t.coords)
        assertNull(AppState().currentTarget())
        assertNull(AppState(wireMountTarget = "  ").currentTarget())
    }

    @Test
    fun thePlanPickBeatsEkosTarget() {
        val s = AppState(targetId = "M 31", targetSnapshot = m31, wireMountTarget = "M 42")
        assertEquals("M 31 — Andromeda", s.currentTarget()!!.name)
    }

    @Test
    fun theMountsDistanceFromTheTargetUsesJ2000() {
        val s = AppState(targetId = "M 31", targetSnapshot = m31, mountRa0 = 0.7122 * 15.0, mountDec0 = 41.2689 + 0.5)
        assertEquals(0.5, s.targetSeparationDeg()!!, 1e-6)
        assertEquals("30′ off", formatSeparation(s.targetSeparationDeg()!!))
    }

    @Test
    fun noSeparationWithoutBothEnds() {
        assertNull(AppState(targetId = "M 31", targetSnapshot = m31).targetSeparationDeg())                        // no mount position
        assertNull(AppState(mountRa0 = 10.0, mountDec0 = 20.0).targetSeparationDeg())                              // no target
        assertNull(AppState(wireMountTarget = "M 42", mountRa0 = 10.0, mountDec0 = 20.0).targetSeparationDeg())    // Ekos name only
    }
}
