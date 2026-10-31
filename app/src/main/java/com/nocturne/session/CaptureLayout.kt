package com.nocturne.session

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where frames are saved on the Pi (`docs/PLAN.md`, "File layout rule"):
 * `<ROOT>/<Target>/<night>/<FrameType>/<Filter>/<Target>_<FrameType>_<Filter>_<exp>_<datetime>_<n>.fits`.
 * The app sets both halves of Ekos's path, `FITSDirectory` and `PlaceholderFormat`, for every job
 * and never lets the user edit either.
 */
object CaptureLayout {
    /** The SD card's root. A USB drive has the same folder at its own mount point. */
    const val ROOT = "/home/soo/Nocturne"

    /** Captures that belong to no job (a manual capture in Ekos) land here, still inside [ROOT]. */
    const val MANUAL_TARGET = "Manual"

    private const val FALLBACK_TARGET = "Unnamed"

    /** Same rule as [sanitizeFileToken], so `M 31` is `M_31` as in the old folders. */
    fun targetFolder(name: String): String =
        sanitizeFileToken(name.trim()).trim('_').ifEmpty { FALLBACK_TARGET }

    /**
     * The local date the night began, so frames after midnight stay with the evening before.
     * [window] is tonight's dusk and dawn from the almanac ([realNightWindow]): the night whose
     * dawn has not passed yet, else the next one. Without an almanac it is the date 12 hours ago,
     * which is right from noon to the following noon and wrong for a job pushed in the morning
     * for that evening.
     */
    fun nightDate(now: Instant, window: Pair<Instant, Instant>?, zone: ZoneId): LocalDate {
        if (window == null) return now.minus(Duration.ofHours(12)).atZone(zone).toLocalDate()
        val (dusk, dawn) = window
        val start = if (now.isAfter(dawn)) dusk.plus(Duration.ofDays(1)) else dusk
        return start.atZone(zone).toLocalDate()
    }

    fun fitsDirectory(target: String, night: LocalDate): String = "$ROOT/${targetFolder(target)}/$night"

    /** Appended to [fitsDirectory]; the target is literal text so a file keeps its name when moved. */
    fun placeholderFormat(target: String): String = "/%T/%F/${targetFolder(target)}_%T_%F_%e_%D"

    /** For captures started outside a job: `<ROOT>/Manual/<Ekos target>/<FrameType>/<Filter>/…`. */
    fun manualFitsDirectory(): String = "$ROOT/$MANUAL_TARGET"

    const val MANUAL_PLACEHOLDER_FORMAT = "/%t/%T/%F/%t_%T_%F_%e_%D"
}
