package com.nocturne.transport

import android.content.Context
import android.net.wifi.WifiManager

/** The phone's current Wi-Fi link: [rssiDbm] and a 0–4 [level] for drawing bars. */
data class WifiSignal(val rssiDbm: Int, val level: Int)

/** Null when the phone isn't on Wi-Fi (Android reports an RSSI of −127 or lower then). */
fun wifiSignal(context: Context): WifiSignal? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    @Suppress("DEPRECATION") val info = wifi.connectionInfo ?: return null
    if (info.rssi <= -127) return null
    @Suppress("DEPRECATION") val level = WifiManager.calculateSignalLevel(info.rssi, 5)
    return WifiSignal(info.rssi, level)
}
