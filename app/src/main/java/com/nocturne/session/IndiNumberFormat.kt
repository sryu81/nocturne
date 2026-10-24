package com.nocturne.session

import kotlin.math.abs

/**
 * INDI number `format` is a C printf spec (indiapi.h `numberFormat`), not a Java one:
 *
 * 1. A precision-less dot (`"%.f"`, as on every device's `POLLING_PERIOD`) is valid C
 *    (precision 0) but throws `IllegalFormatPrecisionException` on Android.
 * 2. INDI's sexagesimal `m` conversion (`"%010.6m"` for RA/DEC/lat-long) has no Java equivalent.
 *
 * Anything that can't be normalized falls back to a plain decimal rather than throwing.
 */
private val INDI_NUMBER_FORMAT = Regex("^%([-+0 #]*)(\\d+)?(?:\\.(\\d+)?)?([a-zA-Z])(.*)$")

fun formatIndiNumber(format: String, value: Double): String {
    val m = INDI_NUMBER_FORMAT.matchEntire(format.trim()) ?: return safeFallback(value)
    val flags = m.groupValues[1]
    val width = m.groupValues[2]
    val precisionDigits = m.groupValues[3]
    val conv = m.groupValues[4]
    val suffix = m.groupValues[5] // trailing unit text, e.g. " °C"
    return try {
        when (conv) {
            "m", "M" -> sexagesimal(value, precisionDigits.toIntOrNull()) + suffix
            "f", "F", "g", "G", "e", "E" -> {
                // C: "%.f" means precision 0, no dot means 6; Java needs the digit explicitly.
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
 * INDI's sexagesimal display (`fs_sexa` in indicom.c) for RA/DEC/lat-long. [fracDigits]
 * follows INDI's numberFormat table: 3->D:M, 5->D:M:S,
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
