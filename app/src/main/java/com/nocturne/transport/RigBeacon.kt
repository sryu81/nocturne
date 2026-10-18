package com.nocturne.transport

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** One rig heard on the LAN via the Pi's `astro-beacon` service. */
data class DiscoveredRig(
    val hostname: String,
    /** Packet's source address — the one that actually routes back to us, which is not
     *  necessarily the first entry of the beacon's own `ips` map on a multi-homed Pi. */
    val address: String,
    val uptimeSec: Int?,
    /** Raw `vcgencmd get_throttled` value, e.g. "0x50000"; null if the Pi couldn't read it. */
    val throttled: String?,
    val seenAtMs: Long,
)

/**
 * Listens for the Pi-side `astro-beacon` systemd service (`/usr/local/bin/astro-beacon` on the
 * rig), which broadcasts `{"magic":"rpi5_astro","hostname",…,"ips":{…},"uptime","throttled"}`
 * every 5 s to UDP [BEACON_PORT] on every interface's directed broadcast + 255.255.255.255.
 *
 * Emits every valid beacon (repeats included) — callers dedupe by hostname and age entries out.
 * Only runs while collected, so ConnectScreen holds the socket + Wi-Fi lock only while visible.
 */
fun rigBeacons(context: Context): Flow<DiscoveredRig> = flow {
    // Many Wi-Fi chipsets filter broadcast/multicast frames in power-save unless an app holds
    // this lock — without it beacons arrive late or never on real phones.
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("nocturne-beacon").apply {
        setReferenceCounted(false)
        acquire()
    }
    val socket = DatagramSocket(null)
    val buf = ByteArray(2048)
    try {
        socket.reuseAddress = true
        socket.broadcast = true
        socket.soTimeout = 1000
        socket.bind(InetSocketAddress(BEACON_PORT))
        while (currentCoroutineContext().isActive) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            parseBeacon(text, packet.address.hostAddress ?: continue)?.let { emit(it) }
        }
    } finally {
        socket.close()
        if (lock.isHeld) lock.release()
    }
}.flowOn(Dispatchers.IO)

const val BEACON_PORT = 50505
private const val BEACON_MAGIC = "rpi5_astro"
private val beaconJson = Json { ignoreUnknownKeys = true }

internal fun parseBeacon(text: String, sourceAddress: String, nowMs: Long = System.currentTimeMillis()): DiscoveredRig? {
    val obj = runCatching { beaconJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    if (obj["magic"]?.jsonPrimitive?.contentOrNull != BEACON_MAGIC) return null
    return DiscoveredRig(
        hostname = obj["hostname"]?.jsonPrimitive?.contentOrNull ?: sourceAddress,
        address = sourceAddress,
        uptimeSec = obj["uptime"]?.jsonPrimitive?.intOrNull,
        throttled = obj["throttled"]?.jsonPrimitive?.contentOrNull,
        seenAtMs = nowMs,
    )
}
