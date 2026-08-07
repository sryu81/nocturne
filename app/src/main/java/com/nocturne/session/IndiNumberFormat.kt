package com.nocturne.session

import kotlin.math.abs

/**
 * Real INDI drivers report `format` as a C-printf-style spec (indiapi.h's `numberFormat`),
 * not a Java/Android Formatter spec — two real-world gaps confirmed live, both of which
 * crashed *every* real device sheet (every single INDI device exposes `POLLING_PERIOD` with
 * format `"%.f"`, so it was the first Number property hit on any device):
 *
 * 1. A precision-less dot (`"%.f"`, `"%.g"`) is valid C (precision defaults to 0) but not
 *    valid Java — and specifically on Android/ART throws `IllegalFormatPrecisionException`
 *    with the *ASCII code of the conversion letter* as the reported precision (`'f'` = 102 —
 *    confirmed against the real crash), not the desktop JVM's `UnknownFormatConversionException`.
 * 2. INDI's own sexagesimal `m` conversion (`"%010.6m"` for RA/DEC/lat-long) has no Java
 *    equivalent at all — `UnknownFormatConversionException: m`.
 *
 * Anything this can't confidently normalize falls back to a plain decimal string rather than
 * ever crashing a device sheet again — a wrong-looking number beats a crashed sheet.
 */
private val INDI_NUMBER_FORMAT = Regex("^%([-+0 #]*)(\\d+)?(?:\\.(\\d+)?)?([a-zA-Z])(.*)$")

fun formatIndiNumber(format: String, value: Double): String {
    val m = INDI_NUMBER_FORMAT.matchEntire(format.trim()) ?: return safeFallback(value)
    val flags = m.groupValues[1]
    val width = m.groupValues[2]
    val precisionDigits = m.groupValues[3]
    val conv = m.groupValues[4]
    val suffix = m.groupValues[5] // e.g. Nocturne's own simulator-only " °C"/"°"/" %" fixture units
    return try {
        when (conv) {
            "m", "M" -> sexagesimal(value, precisionDigits.toIntOrNull()) + suffix
            "f", "F", "g", "G", "e", "E" -> {
                // A dot with no digits after it (INDI's "%.f") means precision 0 in C; no dot
                // at all means C's own default of 6 — Java requires the digit explicitly either way.
                val precision = precisionDigits.toIntOrNull() ?: if (format.contains('.')) 0 else 6
                "%$flags$width.$precision$conv".format(value) + suffix
            }
            "d", "i" -> value.toLong().toString() + suffix
            else -> safeFallback(value)
        }
    } catch (e: Exception) {
        safeFallback(value)
    }
}

private fun safeFallback(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

/**
 * INDI's sexagesimal display (`fs_sexa` in indicom.c) — DD:MM:SS-style, used for RA/DEC/
 * lat-long. [fracDigits] follows INDI's own numberFormat table: 3->D:M, 5->D:M:S,
 * 6->D:M:S.S, 8->D:M:SS.SS, 9->D:M:SS.SSS. Anything else falls back to the D:M:S.S shape.
 */
private fun sexagesimal(value: Double, fracDigits: Int?): String {
    val negative = value < 0
    val whole = abs(value)
    val d = whole.toInt()
    val remMin = (whole - d) * 60.0
    val mm = remMin.toInt()
    val s = (remMin - mm) * 60.0
    val sign = if (negative) "-" else ""
    val minutes = "%02d".format(mm)
    return when (fracDigits) {
        3 -> "$sign$d:$minutes"
        5 -> "$sign$d:$minutes:${"%02.0f".format(s)}"
        8 -> "$sign$d:$minutes:${"%05.2f".format(s)}"
        9 -> "$sign$d:$minutes:${"%06.3f".format(s)}"
        else -> "$sign$d:$minutes:${"%04.1f".format(s)}"
    }
}
