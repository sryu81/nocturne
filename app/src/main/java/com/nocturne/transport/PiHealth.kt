package com.nocturne.transport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Free and total bytes of one mounted volume on the Pi. */
data class PiVolume(val path: String, val free: Long, val total: Long)

/**
 * The maintenance daemon's `GET /health` (`pi-tools/reboot-daemon/`). Every field but [rttMs] is
 * null when the Pi couldn't read it, so an older daemon that answers only `{"status":"ok"}`
 * still parses. The daemon also sends `vcgencmd get_throttled`, which the app ignores: a Pi 5 sets
 * its under-voltage bits on a harmless supply.
 */
data class PiHealth(
    val cpuTempC: Double?,
    val uptimeSec: Long?,
    val load1: Double?,
    /** The Pi's clock, epoch seconds, to compare with the phone's. */
    val timeEpochSec: Long?,
    val sd: PiVolume?,
    val usb: List<PiVolume>,
    /** Request round trip as measured by the phone. */
    val rttMs: Long,
    val receivedAtMs: Long,
) {
    /** Phone clock minus the Pi's, seconds (the request's own delay is under a second). */
    fun clockSkewSec(phoneNowMs: Long): Long? = timeEpochSec?.let { phoneNowMs / 1000 - it }
}

private val healthJson = Json { ignoreUnknownKeys = true }

internal fun parsePiHealth(body: String, rttMs: Long, nowMs: Long): PiHealth? {
    val obj = runCatching { healthJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    if (obj["status"]?.jsonPrimitive?.contentOrNull != "ok") return null
    fun volume(path: String, o: JsonObject): PiVolume? {
        val free = o["free"]?.jsonPrimitive?.longOrNull ?: return null
        val total = o["total"]?.jsonPrimitive?.longOrNull ?: return null
        return PiVolume(path, free, total)
    }
    return PiHealth(
        cpuTempC = obj["cpu_temp_c"]?.jsonPrimitive?.doubleOrNull,
        uptimeSec = obj["uptime_s"]?.jsonPrimitive?.longOrNull,
        load1 = obj["load1"]?.jsonPrimitive?.doubleOrNull,
        timeEpochSec = obj["time"]?.jsonPrimitive?.longOrNull,
        sd = obj["sd"]?.jsonObject?.let { volume("/", it) },
        usb = obj["usb"]?.jsonArray?.mapNotNull { e ->
            val o = e.jsonObject
            volume(o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null, o)
        }.orEmpty(),
        rttMs = rttMs,
        receivedAtMs = nowMs,
    )
}
