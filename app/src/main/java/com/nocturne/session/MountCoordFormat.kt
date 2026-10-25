package com.nocturne.session

import kotlin.math.abs
import kotlin.math.roundToLong

/** Mount card readouts. Ekos sends every coordinate in degrees, RA included. */

private const val NA = "N/A"

/** `hh:mm:ss`; whole seconds, rolling over at 24 h. */
fun formatRa(degrees: Double?): String {
    if (degrees == null) return NA
    val secs = (((degrees % 360.0) + 360.0) % 360.0 / 15.0 * 3600.0).roundToLong() % 86_400L
    return "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
}

/** `+dd:mm:ss`. */
fun formatDec(degrees: Double?): String {
    if (degrees == null) return NA
    val secs = (abs(degrees) * 3600.0).roundToLong()
    val sign = if (degrees < 0 && secs != 0L) '-' else '+'
    return "%c%02d:%02d:%02d".format(sign, secs / 3600, secs / 60 % 60, secs % 60)
}

/** Signed hour angle like Ekos' panel: `+1h23m`, west of the meridian positive. */
fun formatHa(degrees: Double?): String {
    if (degrees == null) return NA
    var d = ((degrees % 360.0) + 360.0) % 360.0
    if (d > 180.0) d -= 360.0
    val mins = (abs(d) / 15.0 * 60.0).roundToLong()
    val sign = if (d < 0 && mins != 0L) '-' else '+'
    return "%c%dh%02dm".format(sign, mins / 60, mins % 60)
}

/** `12.3°`; used for Alt, Az and ZA. */
fun formatDegrees(degrees: Double?): String = degrees?.let { "%.1f°".format(it) } ?: NA

/** Zenith angle: 90° minus altitude. */
fun zenithAngle(altitude: Double?): Double? = altitude?.let { 90.0 - it }
