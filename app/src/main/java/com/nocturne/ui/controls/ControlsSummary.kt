package com.nocturne.ui.controls

import com.nocturne.session.AppState
import com.nocturne.session.ControlsModule
import com.nocturne.session.MOUNT_MOVING_STATES
import com.nocturne.session.PAH_MEASURE_IMAGE
import com.nocturne.session.PAH_STAGE_IDLE
import com.nocturne.session.PAH_STAGE_REFRESHING
import com.nocturne.session.benchFocPos
import com.nocturne.session.coolerIsOn
import com.nocturne.session.formatHa
import com.nocturne.session.guideRmsTotal
import com.nocturne.session.indiNumber
import com.nocturne.session.mountParkedReal
import com.nocturne.session.mountPierSideLabel
import com.nocturne.session.mountTrackingOn
import kotlin.math.roundToInt

/**
 * What a Controls menu card says under a module's name, and whether the module is doing something
 * right now. [active] modules are pinned to the top of the menu; [warn] ones are in the warning
 * colour (the mount or polar alignment is moving the rig); [stoppable] ones get a STOP button on
 * the card (the mount's abort, or polar alignment's stop).
 */
data class ModuleSummary(
    val line: String,
    val active: Boolean = false,
    val warn: Boolean = false,
    val stoppable: Boolean = false,
)

/**
 * Capture states in which the Capture module is working (`ekos.h` captureStates). Anything not
 * listed, including a state this app doesn't know, is not active, so a card never stays pinned on
 * an unfamiliar word.
 */
private val CAPTURE_ACTIVE_STATES = setOf(
    "In Progress", "Capturing", "Image Received", "Dithering", "Focusing", "Filter Focus",
    "Changing Filter", "Setting Temperature", "Setting Rotator", "Waiting",
)

/** Align states in which a solve is under way (`ekos.h` alignStates). */
private val ALIGN_ACTIVE_STATES = setOf("In Progress", "Syncing", "Slewing", "Rotating")

fun controlsSummary(state: AppState, module: ControlsModule): ModuleSummary = when (module) {
    ControlsModule.MOUNT -> mountSummary(state)
    ControlsModule.CAMERA -> cameraSummary(state)
    ControlsModule.FOCUSER -> focuserSummary(state)
    ControlsModule.PLATE_SOLVE -> plateSolveSummary(state)
    ControlsModule.GUIDE -> guideSummary(state)
    ControlsModule.POLAR_ALIGNMENT -> polarSummary(state)
}

/** The modules doing something now, in menu order: pinned above the rest of the menu. */
fun activeModules(state: AppState): List<ControlsModule> =
    ControlsModule.entries.filter { controlsSummary(state, it).active }

private fun mountSummary(s: AppState): ModuleSummary {
    val moving = s.slewDir != null || s.wireMountStatus in MOUNT_MOVING_STATES
    val tracking = s.mountTrackingOn
    val first = when {
        s.slewDir != null -> "slewing ${s.slewDir}"
        s.wireMountStatus in MOUNT_MOVING_STATES -> s.wireMountStatus.orEmpty().lowercase()
        s.mountParkedReal -> "parked"
        tracking == true -> "Tracking"
        tracking == false -> "tracking off"
        else -> "N/A"
    }
    // The label reads "raw N" for a pier side the app can't name (-1 is "unknown"); leave those out.
    val pier = s.mountPierSideLabel?.takeIf { it == "East" || it == "West" }
    val line = listOfNotNull(first, pier, s.mountHa?.let { "HA ${formatHa(it)}" }).joinToString(" · ")
    return ModuleSummary(line, active = moving, warn = moving, stoppable = moving)
}

private fun cameraSummary(s: AppState): ModuleSummary {
    val camera = s.primaryTrain.camera
    val connected = s.wireDevices?.any { it.name == camera && it.connected } == true
    val temp = if (connected) s.indiNumber(camera, "CCD_TEMPERATURE")?.let { "%.1f °C".format(it) } else null
    val power = if (connected) s.indiNumber(camera, "CCD_COOLER_POWER")?.roundToInt()?.let { "$it%" } else null
    val coolerOn = if (connected) s.coolerIsOn(camera) else null
    val settings = s.wireCaptureSettings
    val parts = if (coolerOn == false) {
        listOfNotNull("cooler off", temp)
    } else {
        listOfNotNull(temp, power, settings?.let { "${seconds(it.captureExposureN)} s" }, settings?.let { "bin ${it.captureBinHN}" })
    }
    return ModuleSummary(parts.joinToString(" · ").ifEmpty { "N/A" }, active = s.wireCaptureStatus in CAPTURE_ACTIVE_STATES)
}

private fun focuserSummary(s: AppState): ModuleSummary {
    val status = if (s.focusRunning) "autofocus running" else s.wireFocusStatus?.lowercase()
    return ModuleSummary(listOfNotNull(s.benchFocPos?.toString() ?: "N/A", status).joinToString(" · "), active = s.focusRunning)
}

private fun plateSolveSummary(s: AppState): ModuleSummary {
    if (s.wireAlignStatus in ALIGN_ACTIVE_STATES) return ModuleSummary("solving…", active = true)
    val accuracy = s.alignAccuracyHistory.lastOrNull()?.let { "%.1f″".format(it) }
    return ModuleSummary(listOfNotNull(s.wireAlignStatus, accuracy).joinToString(" · ").ifEmpty { "N/A" })
}

private fun guideSummary(s: AppState): ModuleSummary {
    val rms = if (s.guiding) s.guideRmsTotal?.let { "%.2f″ RMS".format(it) } else null
    val status = s.wireGuideStatus ?: if (s.guiding) "running…" else null
    return ModuleSummary(listOfNotNull(status, rms).joinToString(" · ").ifEmpty { "N/A" }, active = s.guiding)
}

private fun polarSummary(s: AppState): ModuleSummary {
    val stage = s.wirePolarStage
    val image = PAH_MEASURE_IMAGE[stage]
    val total = (s.wirePolarUpdatedError ?: s.wirePolarVector?.error)?.takeIf { it >= 0 }
    val line = when {
        image != null -> "measuring · image $image of 3"
        stage == PAH_STAGE_REFRESHING -> "refreshing" + (total?.let { " · total %.2f°".format(it) } ?: "")
        stage != null && stage != PAH_STAGE_IDLE -> stage
        s.polarRunning -> "running…"
        else -> "not started"
    }
    return ModuleSummary(line, active = s.polarRunning, warn = s.polarRunning, stoppable = s.polarRunning)
}

/** `300` for a whole number of seconds, `0.5` otherwise. */
private fun seconds(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
