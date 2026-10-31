package com.nocturne.session

import com.nocturne.protocol.WireAlignSettings
import kotlinx.serialization.json.put

// Small pure rules of the rig session, kept apart so they can be unit-tested without a socket.

/** Largest rig-vs-phone clock difference left alone, in seconds. */
internal const val CLOCK_SKEW_OK_SEC = 2L

/** True when a plain solve could move the mount: the solver action is `slewR`, or not known yet. */
internal fun solverActionNeedsReset(settings: WireAlignSettings?): Boolean = settings == null || settings.slewR

/**
 * The (nothingR, slewR, syncR) to put back after goto-and-center. Never `slewR`: it is only ever
 * wanted during the centering loop, and restoring a stale `slewR` (an interrupted run, or a read
 * taken mid-loop) would make every later "Plate solve here" slew the mount. Sync is kept if the
 * user had chosen it.
 */
internal fun restoredSolverAction(original: WireAlignSettings?): Triple<Boolean, Boolean, Boolean> =
    if (original?.syncR == true) Triple(false, false, true) else Triple(true, false, false)

internal enum class AutoTimeSync { SYNC, CLOCK_OK, RUNNING, UNKNOWN }

/** What the connect-time time sync does: [skewSec] is the phone's clock minus the rig's, null if unknown. */
internal fun autoTimeSync(skewSec: Long?, jobRunning: Boolean): AutoTimeSync = when {
    skewSec == null -> AutoTimeSync.UNKNOWN
    kotlin.math.abs(skewSec) <= CLOCK_SKEW_OK_SEC -> AutoTimeSync.CLOCK_OK
    jobRunning -> AutoTimeSync.RUNNING
    else -> AutoTimeSync.SYNC
}
