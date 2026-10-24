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
 * Fetches a DSS2 color cutout centered on a target's RA/Dec for the Framing card, from CDS
 * Strasbourg's public `hips2fits` service (no API key, plain JPEG). Unlike every other network
 * path in the app, this goes to the internet rather than the Pi, so it depends on the phone's own
 * connectivity (see [RigNetwork]).
 */
class ReferenceImageClient {

    private val http = OkHttpClient()

    /** [fovDeg] is the cutout's field of view in degrees; pad it over the camera FOV so the
     *  overlay box doesn't touch the edge. Returns null on network failure or non-2xx; never
     *  throws. Decoding is left to the caller. */
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
