package com.nocturne.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBoostTest {
    private val grey = 0xFF404040.toInt()
    private val dimGreen = 0xFF145A14.toInt()

    private fun frame(w: Int, h: Int, vararg set: Triple<Int, Int, Int>): IntArray {
        val px = IntArray(w * h) { grey }
        set.forEach { (x, y, c) -> px[y * w + x] = c }
        return px
    }

    @Test
    fun aGreyFrameIsUnchanged() {
        val src = frame(9, 9)
        assertEquals(src.toList(), OverlayBoost.boost(src, 9, 9, 2).toList())
    }

    @Test
    fun anOverlayPixelIsWidenedAndBrightenedInItsOwnHue() {
        val out = OverlayBoost.boost(frame(9, 9, Triple(4, 4, dimGreen)), 9, 9, 2)
        assertEquals(0xFF00FF00.toInt(), out[4 * 9 + 4]) // dark green becomes pure green
        assertEquals(out[4 * 9 + 4], out[4 * 9 + 6]) // two pixels to the right
        assertEquals(out[4 * 9 + 4], out[2 * 9 + 4]) // two pixels up
        assertEquals(grey, out[4 * 9 + 8]) // beyond the radius and its one-pixel fade
        assertEquals(grey, out[0]) // corner
    }

    @Test
    fun twoColoursKeepTheirOwnHueWhereTheyTouch() {
        val magenta = 0xFF8C148C.toInt()
        val out = OverlayBoost.boost(frame(9, 9, Triple(3, 4, dimGreen), Triple(4, 4, magenta)), 9, 9, 2)
        val g = out[4 * 9 + 3]
        val m = out[4 * 9 + 4]
        assertTrue(((g shr 8) and 0xFF) > 200 && ((g shr 16) and 0xFF) < 60) // still green
        assertTrue(((m shr 16) and 0xFF) > 200 && ((m shr 8) and 0xFF) < 60) // still magenta
    }

    @Test
    fun aStrayPixelOfAnotherHueIsOutvotedByItsNeighbours() {
        val orange = 0xFF8C5A14.toInt()
        val line = (2..6).map { Triple(it, 4, dimGreen) }.toTypedArray()
        val out = OverlayBoost.boost(frame(9, 9, *line, Triple(4, 3, orange)), 9, 9, 2)
        val c = out[4 * 9 + 4]
        assertTrue(((c shr 8) and 0xFF) > 250 && ((c shr 16) and 0xFF) < 100)
    }

    @Test
    fun aGapInALineIsClosedAndItsEdgeFades() {
        val out = OverlayBoost.boost(frame(15, 9, Triple(3, 4, dimGreen), Triple(7, 4, dimGreen)), 15, 9, 2)
        assertEquals(0xFF00FF00.toInt(), out[4 * 15 + 5]) // the gap pixel between them
        assertEquals(grey, out[4 * 15 + 10]) // past the radius and its one-pixel fade
        val fade = out[5 * 15 + 9] // 2.2 px from the second pixel: inside the fade, so blended
        assertTrue(fade != grey && fade != 0xFF00FF00.toInt())
    }

    @Test
    fun aFaintTintBelowTheThresholdIsNotOverlay() {
        val tinted = 0xFF404B40.toInt() // spread 11: noise-level, not a pen colour
        val src = frame(9, 9, Triple(4, 4, tinted))
        assertEquals(src.toList(), OverlayBoost.boost(src, 9, 9, 2).toList())
    }

    @Test
    fun paintStaysInsideTheImageAndTheInputIsNotModified() {
        val src = frame(5, 5, Triple(0, 0, dimGreen))
        val copy = src.copyOf()
        val out = OverlayBoost.boost(src, 5, 5, 3)
        assertEquals(copy.toList(), src.toList())
        assertEquals(25, out.size)
        assertEquals(255, (out[2 * 5 + 2] shr 8) and 0xFF)
    }

    @Test
    fun lineRadiusScalesWithTheFrameWidthWithinLimits() {
        assertEquals(1, OverlayBoost.lineRadius(200))
        assertEquals(3, OverlayBoost.lineRadius(900))
        assertEquals(6, OverlayBoost.lineRadius(6000))
    }
}
