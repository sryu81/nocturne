package com.nocturne.transport

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress

/**
 * Whether the phone may use the internet for optional extras (the online framing cutout).
 *
 * In the field the phone sits on the rig's own Wi-Fi, whose internet uplink can be a prepaid
 * SIM with a small data cap, so optional internet traffic is skipped when the phone's active
 * network is the rig's subnet, or when Android reports the network as metered.
 */
object RigNetwork {

    fun allowOptionalInternet(context: Context, rigHost: String): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (cm.isActiveNetworkMetered) return false
        return !onRigSubnet(cm, rigHost)
    }

    private fun onRigSubnet(cm: ConnectivityManager, rigHost: String): Boolean {
        // only literal addresses (beacon/typed IPs); never resolve a name just to decide this
        if (!rigHost.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
        val rig = InetAddress.getByName(rigHost).address
        val link = cm.getLinkProperties(cm.activeNetwork ?: return false) ?: return false
        return link.linkAddresses.any { la ->
            val mine = la.address.address
            mine.size == rig.size && samePrefix(mine, rig, la.prefixLength)
        }
    }

    internal fun samePrefix(a: ByteArray, b: ByteArray, prefixBits: Int): Boolean {
        for (bit in 0 until prefixBits.coerceAtMost(a.size * 8)) {
            val mask = 0x80 ushr (bit % 8)
            if ((a[bit / 8].toInt() and mask) != (b[bit / 8].toInt() and mask)) return false
        }
        return true
    }
}
