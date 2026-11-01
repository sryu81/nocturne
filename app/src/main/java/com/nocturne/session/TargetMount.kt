package com.nocturne.session

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * The target shown on Home and on the Controls mount card. [fromPlan] is false when Ekos reported a
 * target (a goto made outside the app) and the Plan tab has none picked: then only the [name] is known.
 */
data class CurrentTarget(
    val name: String,
    /** "00h42m44s +41°16′08″" (J2000) when known. */
    val coords: String?,
    val raDeg: Double?,
    val decDeg: Double?,
    val fromPlan: Boolean,
)

/**
 * The Plan tab's pick, else the target Ekos last sent the mount to. The Plan pick is what the user chose
 * to work on; Ekos's is a fallback so a goto made from KStars still shows a name.
 */
fun AppState.currentTarget(): CurrentTarget? {
    findTarget(targetId)?.let { t ->
        val deg = t.raDecDegrees
        return CurrentTarget(t.displayName, t.coords.takeIf { it.isNotBlank() }, deg?.first, deg?.second, fromPlan = true)
    }
    return wireMountTarget?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { CurrentTarget(it, null, null, null, fromPlan = false) }
}

/** How far the mount's J2000 position is from the target, in degrees; null when either is unknown. */
fun AppState.targetSeparationDeg(): Double? {
    val target = currentTarget() ?: return null
    val ra = target.raDeg ?: return null
    val dec = target.decDeg ?: return null
    return angularSeparationDeg(mountRa0 ?: return null, mountDec0 ?: return null, ra, dec)
}

/** Great-circle distance between two sky positions, all in degrees. */
fun angularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val d1 = Math.toRadians(dec1)
    val d2 = Math.toRadians(dec2)
    val dRa = Math.toRadians(abs(ra1 - ra2))
    val c = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
    return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
}

/** Under an arcminute counts as on the target; below a degree in arcminutes, above it in degrees. */
fun formatSeparation(deg: Double): String = when {
    deg < 1.0 / 60.0 -> "on target"
    deg < 1.0 -> "${Math.round(deg * 60.0)}′ off"
    deg < 10.0 -> String.format(java.util.Locale.US, "%.1f° away", deg)
    else -> "${Math.round(deg)}° away"
}
