package com.nocturne.ui.components

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Makes the overlay Ekos paints into a Polar alignment frame (the correction triangle and the
 * target circle) visible on a phone. Ekos draws them about 1 px wide on a mono frame, and the
 * frame is then scaled down to the screen width, so they vanish into the noise.
 *
 * The frame itself is grey, so any strongly coloured pixel belongs to the overlay. Each one is
 * splatted into a soft disc of [lineRadius]: the discs add up to the line's coverage (alpha), so
 * the gaps JPEG leaves in a 1 px line close and the edge is antialiased, and they add up the
 * pixels' colours, so a stray chroma-noise pixel is outvoted by its neighbours instead of
 * showing as a speck. The summed colour is then rebuilt at full saturation and brightness (the
 * overlay pen is dim, so scaling it up would wash it out to pastel) and blended over the frame by
 * coverage. Geometry is untouched, so the line stays where Ekos drew it and tap-to-pick maps as before.
 */
object OverlayBoost {
    /**
     * Channel spread (max − min) from which a pixel counts as overlay. A mono frame is exactly grey,
     * so stars and noise sit at about 0, while the dim overlay pen is only 15–60 apart: checked on a
     * phone screenshot of a real run (the dark magenta edge and the circle fall below 32).
     */
    const val SPREAD_MIN = 14

    /** Spread at which a pixel's vote on the line colour is full strength; fainter pixels count less. */
    private const val SPREAD_FULL = 40f

    /** A pixel's own colour counts this many times over a neighbour at the same distance, so where two lines touch each keeps its own hue. */
    private const val SELF_WEIGHT = 3f

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
        var cover: FloatArray? = null
        var red = FloatArray(0)
        var green = FloatArray(0)
        var blue = FloatArray(0)
        for (y in 0 until h) for (x in 0 until w) {
            val c = src[y * w + x]
            val spread = spread(c)
            if (spread < SPREAD_MIN) continue
            if (cover == null) {
                cover = FloatArray(w * h)
                red = FloatArray(w * h)
                green = FloatArray(w * h)
                blue = FloatArray(w * h)
            }
            val vote = min(1f, spread / SPREAD_FULL)
            val (vr, vg, vb) = vivid(c)
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy !in 0 until h) continue
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx !in 0 until w) continue
                    // Full inside the radius, fading to nothing one pixel beyond it.
                    val k = (radius + 1 - sqrt((dx * dx + dy * dy).toFloat())).coerceIn(0f, 1f)
                    if (k == 0f) continue
                    val i = yy * w + xx
                    val wt = k * vote * (if (dx == 0 && dy == 0) SELF_WEIGHT else 1f)
                    cover[i] += k
                    red[i] += vr * wt
                    green[i] += vg * wt
                    blue[i] += vb * wt
                }
            }
        }
        if (cover == null) return out
        for (i in out.indices) {
            val a = min(1f, cover[i])
            if (a <= 0f) continue
            val lo = min(red[i], min(green[i], blue[i]))
            val hi = max(red[i], max(green[i], blue[i]))
            if (hi - lo <= 0f) continue
            val k = 255f / (hi - lo)
            val base = src[i]
            fun mix(from: Int, sum: Float) = (from + ((sum - lo) * k - from) * a).roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or
                (mix((base shr 16) and 0xFF, red[i]) shl 16) or
                (mix((base shr 8) and 0xFF, green[i]) shl 8) or
                mix(base and 0xFF, blue[i])
        }
        return out
    }

    private fun spread(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    /** The pixel's hue at full saturation and brightness, as r, g, b in 0..255. */
    private fun vivid(c: Int): Triple<Float, Float, Float> {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val lo = min(r, min(g, b))
        val k = 255f / (max(r, max(g, b)) - lo)
        return Triple((r - lo) * k, (g - lo) * k, (b - lo) * k)
    }
}
