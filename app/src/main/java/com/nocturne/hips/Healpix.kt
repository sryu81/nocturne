package com.nocturne.hips

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Just enough HEALPix (nested scheme) to read HiPS survey tiles — the DSS2 colour survey the Pi
 * mirrors (`pi-tools/hips-mirror`) and the app copies for offline framing ([SkyAtlas]).
 *
 * A HiPS tile of order k is one HEALPix pixel at order k, stored as a 512×512 JPEG; each image
 * pixel is one HEALPix pixel at order k+9. The in-tile orientation (image column = the y bits of
 * the face coordinate, image row = the x bits) was checked against CDS's own `hips2fits`
 * renderer in four sky zones (equatorial, polar/equatorial boundary, north cap, south),
 * 2026-10-24: correlation 0.74–0.96 for this mapping, ≤0.50 for each of the other seven.
 */
object Healpix {
    const val TILE_ORDER_BITS = 9
    const val TILE_WIDTH = 1 shl TILE_ORDER_BITS // 512

    /** Face 0..11 and the (ix, iy) coordinates inside it, each in 0 until 2^order. */
    data class FacePixel(val face: Int, val ix: Int, val iy: Int)

    /** Standard HEALPix ang2pix (nested), returning the face coordinates instead of the index.
     *  [theta] is the colatitude (0 at the north pole), [phi] the longitude, both radians. */
    fun facePixel(order: Int, theta: Double, phi: Double): FacePixel {
        val nside = 1 shl order
        val z = cos(theta)
        val za = abs(z)
        val tt = ((phi % (2 * PI)) + 2 * PI) % (2 * PI) * 2 / PI // [0, 4)
        return if (za <= 2.0 / 3.0) {
            val t1 = nside * (0.5 + tt)
            val t2 = nside * z * 0.75
            val jp = (t1 - t2).toLong()
            val jm = (t1 + t2).toLong()
            val ifp = (jp / nside).toInt()
            val ifm = (jm / nside).toInt()
            val face = if (ifp == ifm) ifp or 4 else if (ifp < ifm) ifp else ifm + 8
            val ix = (jm and (nside - 1).toLong()).toInt()
            val iy = nside - (jp and (nside - 1).toLong()).toInt() - 1
            FacePixel(face, ix, iy)
        } else {
            val ntt = min(3, tt.toInt())
            val tp = tt - ntt
            val tmp = nside * sqrt(3 * (1 - za))
            val jp = min((tp * tmp).toInt(), nside - 1)
            val jm = min(((1 - tp) * tmp).toInt(), nside - 1)
            if (z >= 0) FacePixel(ntt, nside - jm - 1, nside - jp - 1)
            else FacePixel(ntt + 8, jp, jm)
        }
    }

    /** Nested index of face coordinates: ix in the even bits, iy in the odd bits. */
    fun nestIndex(order: Int, p: FacePixel): Long {
        var bits = 0L
        for (b in 0 until order) {
            bits = bits or (((p.ix shr b) and 1).toLong() shl (2 * b)) or (((p.iy shr b) and 1).toLong() shl (2 * b + 1))
        }
        return p.face.toLong() * (1L shl (2 * order)) + bits
    }

    fun tileCount(order: Int): Int = 12 * (1 shl (2 * order))

    /** `Norder<k>/Dir<d>/Npix<n>.jpg`, the layout both CDS and the Pi mirror use. */
    fun tilePath(order: Int, npix: Int): String = "Norder$order/Dir${(npix / 10000) * 10000}/Npix$npix.jpg"

    /** Which tile of [order] holds a sky position, and which pixel inside its 512×512 image. */
    data class TileSample(val npix: Int, val col: Int, val row: Int)

    fun tileSample(order: Int, raDeg: Double, decDeg: Double): TileSample {
        val p = facePixel(order + TILE_ORDER_BITS, PI / 2 - Math.toRadians(decDeg), Math.toRadians(raDeg))
        val tile = FacePixel(p.face, p.ix shr TILE_ORDER_BITS, p.iy shr TILE_ORDER_BITS)
        val sx = p.ix and (TILE_WIDTH - 1)
        val sy = p.iy and (TILE_WIDTH - 1)
        return TileSample(nestIndex(order, tile).toInt(), col = sy, row = sx)
    }

    /** Angular size of one tile pixel at [order], degrees (≈58.6° / 2^order / 512). */
    fun tilePixelDeg(order: Int): Double =
        Math.toDegrees(sqrt(4 * PI / tileCount(order))) / TILE_WIDTH

    /**
     * Inverse gnomonic (TAN) projection for a square [sizePx] image of [fovDeg] centred on
     * ([ra0Deg], [dec0Deg]), north up and east left — same framing as a `hips2fits` cutout with
     * `projection=TAN`. Returns (raDeg, decDeg) of the pixel centre at ([col], [row]).
     */
    fun tanToSky(ra0Deg: Double, dec0Deg: Double, fovDeg: Double, sizePx: Int, col: Int, row: Int): Pair<Double, Double> {
        val scale = Math.toRadians(fovDeg) / sizePx
        val xi = -(col - (sizePx - 1) / 2.0) * scale
        val eta = -(row - (sizePx - 1) / 2.0) * scale
        val ra0 = Math.toRadians(ra0Deg)
        val dec0 = Math.toRadians(dec0Deg)
        val rho = hypot(xi, eta)
        if (rho == 0.0) return ra0Deg to dec0Deg
        val c = atan(rho)
        val dec = asin(cos(c) * sin(dec0) + eta * sin(c) * cos(dec0) / rho)
        val ra = ra0 + atan2(xi * sin(c), rho * cos(dec0) * cos(c) - eta * sin(dec0) * sin(c))
        return Math.toDegrees(ra) to Math.toDegrees(dec)
    }
}
