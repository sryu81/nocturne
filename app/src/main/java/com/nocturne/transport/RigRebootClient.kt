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
 * Talks to the small companion daemon installed on the rig's Pi
 * (`pi-tools/reboot-daemon/`) — a plain HTTP+shared-token channel, entirely
 * separate from the EkosRemote websocket. The wire protocol has no OS-level
 * reboot command, and even if it did, a hung/crashed Ekos process is exactly
 * the case a reboot needs to recover from — it can't depend on that same
 * process being alive to ask for one.
 */
class RigRebootClient(private val host: String, private val port: Int, private val token: String) {

    private val http = OkHttpClient()

    /** POSTs `/reboot`; the daemon responds before actually rebooting, so success here means
     *  "the Pi accepted the request", not "the Pi is back up" — callers should expect the
     *  connection to drop shortly after and rely on [EkosRemoteClient]'s own reconnect. */
    suspend fun reboot(): Result<Unit> = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url("http://$host:$port/reboot")
            .header("X-Reboot-Token", token)
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

    /**
     * POSTs `/set-time` with the phone's own local wall-clock date/time — same daemon, same
     * shared-secret header, real `sudo date -s`+`hwclock -w` on the Pi (2026-09 addition, see
     * `reboot_daemon.py`'s own doc for why this needs OS-level `sudo` rather than anything on the
     * EkosRemote wire: no INDI/Ekos property controls the Pi's system clock at all, only KStars'
     * own internal simulated clock does, which is a separate real gap — see
     * [com.nocturne.session.SessionController.setKstarsTime] for that half).
     * [iso] must be a **local** (no timezone suffix) `YYYY-MM-DDTHH:MM:SS` string — the daemon
     * passes it straight to `date -s`, interpreted in whatever timezone the Pi's own
     * `/etc/localtime` is set to, matching the real intent (phone and rig co-located).
     */
    suspend fun setTime(iso: String): Result<Unit> = suspendCancellableCoroutine { cont ->
        val body = buildJsonObject { put("iso", iso) }.toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://$host:$port/set-time")
            .header("X-Reboot-Token", token)
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
