package com.nocturne.ui.components

import org.junit.Assert.assertEquals
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
        assertEquals(grey, out[4 * 9 + 7]) // beyond the radius
        assertEquals(grey, out[0]) // corner
    }

    @Test
    fun twoColoursKeepTheirOwnPixelsWhereTheyTouch() {
        val magenta = 0xFF8C148C.toInt()
        val both = OverlayBoost.boost(frame(9, 9, Triple(3, 4, dimGreen), Triple(4, 4, magenta)), 9, 9, 2)
        val greenOnly = OverlayBoost.boost(frame(9, 9, Triple(3, 4, dimGreen)), 9, 9, 2)
        assertEquals(greenOnly[4 * 9 + 3], both[4 * 9 + 3])
        assertEquals(0xFFFF00FF.toInt(), both[4 * 9 + 4])
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
