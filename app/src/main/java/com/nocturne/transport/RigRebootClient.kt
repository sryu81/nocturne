package com.nocturne.transport

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Client for the companion daemon on the Pi (`pi-tools/reboot-daemon/`), a plain unauthenticated
 * HTTP channel separate from EkosRemote. A reboot or shutdown must work when Ekos itself is hung, so it can't
 * go through the websocket.
 */
class RigRebootClient(private val host: String, private val port: Int) {

    private val http = OkHttpClient()

    /** POSTs `/reboot`. The daemon replies before rebooting, so success means the request was
     *  accepted; the connection drops shortly after and [EkosRemoteClient] reconnects. */
    suspend fun reboot(): Result<Unit> = post("reboot")

    /** POSTs `/shutdown`: powers the Pi off after replying. It stays off until power is cycled. */
    suspend fun shutdown(): Result<Unit> = post("shutdown")

    private suspend fun post(path: String): Result<Unit> = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url("http://$host:$port/$path")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (cont.isActive) {
                        cont.resume(
                            if (it.isSuccessful) Result.success(Unit)
                            else Result.failure(IOException("daemon replied HTTP ${it.code}")),
                        )
                    }
                }
            }
        })
    }

    /** GETs `/health`: the Pi's own health, answered even when KStars is down. Null when the daemon is unreachable or too old. */
    suspend fun health(): PiHealth? = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url("http://$host:$port/health").get().build()
        val call = http.newCall(request)
        val startedAt = System.currentTimeMillis()
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val now = System.currentTimeMillis()
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (cont.isActive) cont.resume(body?.let { b -> parsePiHealth(b, now - startedAt, now) })
                }
            }
        })
    }

    /**
     * POSTs `/set-time`; the daemon runs `date -s` + `hwclock -w`. Nothing on the EkosRemote wire
     * sets the Pi's system clock (KStars' own clock is set separately, see
     * [com.nocturne.session.SessionController.setKstarsTime]). [iso] is a local
     * `YYYY-MM-DDTHH:MM:SS` with no zone suffix, interpreted in the Pi's `/etc/localtime`.
     */
    suspend fun setTime(iso: String): Result<Unit> = suspendCancellableCoroutine { cont ->
        val body = buildJsonObject { put("iso", iso) }.toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://$host:$port/set-time")
            .post(body)
            .build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (cont.isActive) {
                        cont.resume(
                            if (it.isSuccessful) Result.success(Unit)
                            else Result.failure(IOException("daemon replied HTTP ${it.code}")),
                        )
                    }
                }
            }
        })
    }
}
