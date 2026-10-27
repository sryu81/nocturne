package com.nocturne.ui.components

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes the overlay Ekos paints into a Polar alignment frame (the correction triangle and the
 * target circle) visible on a phone. Ekos draws them about 1 px wide on a mono frame, and the
 * frame is then scaled down to the screen width, so they vanish into the noise.
 *
 * The frame itself is grey, so any strongly coloured pixel belongs to the overlay: those pixels are
 * widened by [lineRadius] and painted fully saturated at full brightness in their own hue (the three
 * triangle edges stay told apart). The overlay pen is dim (dark purple, dark green), so the colour is
 * rebuilt from its hue, not just scaled up, which would wash it out to pastel. Image size and geometry are unchanged, so tap-to-pick maps as before.
 */
object OverlayBoost {
    /**
     * Channel spread (max − min) from which a pixel counts as overlay. A mono frame is exactly grey,
     * so stars and noise sit at about 0, while the dim overlay pen is only 15–60 apart: checked on a
     * phone screenshot of a real run (the dark magenta edge and the circle fall below 32).
     */
    const val SPREAD_MIN = 14

    /** Half-width of the widened line, in image pixels: about 3 dp on screen for a typical frame width. */
    fun lineRadius(width: Int): Int = (width / 300f).roundToInt().coerceIn(1, 6)

    fun apply(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(boost(px, w, h, lineRadius(w)), 0, w, 0, 0, w, h)
        return out
    }

    /** Pure core of [apply] on ARGB pixels; returns a new array, [src] is not modified. */
    fun boost(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = src.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[y * w + x]
            val colour = vivid(c) ?: continue
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy !in 0 until h) continue
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx !in 0 until w || dx * dx + dy * dy > radius * radius) continue
                    val i = yy * w + xx
                    // Overlay pixels keep their own colour; a neighbour's paint only goes on background.
                    if (vivid(src[i]) == null) out[i] = colour
                }
            }
            out[y * w + x] = colour
        }
        return out
    }

    /** The pixel's hue at full saturation and brightness, or null when it is grey (not overlay). */
    private fun vivid(c: Int): Int? {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        if (hi - lo < SPREAD_MIN) return null
        val k = 255f / (hi - lo)
        return (0xFF shl 24) or (((r - lo) * k).roundToInt() shl 16) or (((g - lo) * k).roundToInt() shl 8) or ((b - lo) * k).roundToInt()
    }
}
