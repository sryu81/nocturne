package com.nocturne.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Fetches a real DSS sky-survey cutout centered on a target's own RA/Dec, for the Plan tab's
 * Framing card (M5, docs/STATUS.md). Uses CDS Strasbourg's public `hips2fits` service — no API
 * key, real astronomical imagery (DSS2 color survey), returns a plain JPEG.
 *
 * **This is the first direct internet call this app makes that isn't to the Pi itself** — every
 * other network path in Nocturne talks only to `ws://<pi>:9000` or the reboot daemon on the same
 * host. Worth remembering next time the network/trust-model section of docs/STATUS.md is
 * revisited: this one depends on the *phone's own* internet access, separate from the rig LAN, and
 * fails independently of whether the Pi connection is healthy.
 */
class ReferenceImageClient {

    private val http = OkHttpClient()

    /** [fovDeg] is the desired field of view in degrees for the cutout's larger dimension — the
     *  call site should pass some real padding over the camera's own actual FOV so the box drawn
     *  on top doesn't sit flush against the image edge. Returns null on any failure
     *  (offline, no route, non-2xx, decode issue is left to the caller) — never throws. */
    suspend fun fetchCutout(raDeg: Double, decDeg: Double, fovDeg: Double): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val url = "https://alasky.u-strasbg.fr/hips-image-services/hips2fits".toHttpUrl().newBuilder()
                .addQueryParameter("hips", "CDS/P/DSS2/color")
                .addQueryParameter("width", "600")
                .addQueryParameter("height", "600")
                .addQueryParameter("fov", fovDeg.coerceIn(0.05, 60.0).toString())
                .addQueryParameter("projection", "TAN")
                .addQueryParameter("coordsys", "icrs")
                .addQueryParameter("ra", raDeg.toString())
                .addQueryParameter("dec", decDeg.toString())
                .addQueryParameter("format", "jpg")
                .build()
            val call = http.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bytes = if (it.isSuccessful) it.body?.bytes() else null
                        if (cont.isActive) cont.resume(bytes)
                    }
                }
            })
        }
}
