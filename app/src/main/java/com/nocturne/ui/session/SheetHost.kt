package com.nocturne.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.nocturne.session.DEVICES
import com.nocturne.session.formatSiteTime
import com.nocturne.session.realNightWindow
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.session.train
import com.nocturne.ui.components.NocturneSheet


/** Hosts whichever sheet is open. */
@Composable
fun SheetHost(state: AppState, ctrl: SessionController, landscape: Boolean) {
    val sheet = state.sheet ?: return
    // Back closes the sheet, not the screen behind it.
    BackHandler(onBack = ctrl::closeSheet)
    val full = sheet == SheetType.SETUP
    val (title, meta) = when (sheet) {
        SheetType.GUIDE -> "Guiding" to "RMS, drift + live frame"
        SheetType.FOCUS -> "Focus" to "status + live frame"
        SheetType.ALERTS -> "Alerts" to "tonight"
        // Dusk → dawn once the almanac arrives; a placeholder before then.
        SheetType.SUMMARY -> "Session summary" to (
            state.realNightWindow?.let {
                "${state.formatSiteTime(it.first)} → ${state.formatSiteTime(it.second)}"
            } ?: "night N/A"
        )
        SheetType.PREFS -> "Alert rules" to "push + on-screen"
        SheetType.SETUP -> (if (state.setupEditingName != null) "Edit rig profile" else "New rig profile") to "name + device connections"
        SheetType.OPTICAL_TRAIN -> "Optical train" to "primary + secondary roles"
        SheetType.MODULE_ASSIGNMENTS -> "Module assignments" to "which train each Ekos module uses"
        SheetType.SCOPES -> "Scopes" to "add, edit, remove"
        SheetType.MAINTENANCE -> "Rig maintenance" to "reboot or shut down the Pi"
        SheetType.RIG_HEALTH -> "Rig health" to "links, modules and the Pi"
        SheetType.MOUNT_SETTINGS -> "Mount settings" to "flip, limits, auto-park"
        SheetType.ALIGN_SETTINGS -> "Align settings" to "exposure, gain, filter, accuracy"
        SheetType.GUIDE_SETTINGS -> "Guide settings" to "dither, frame, calibration"
        SheetType.FOCUS_SETTINGS -> "Focus settings" to "exposure, gain, filter"
        // Keep meta short (~30 chars): it isn't weighted, so a long one squeezes the title.
        SheetType.SCHEDULER_SETTINGS -> "Scheduler settings" to "startup, constraints & more"
        SheetType.DEVICE -> {
            // When connected, deviceKey is the live device's name, not a DEVICES[].key, so it
            // must be looked up in wireDevices first.
            val live = state.wireDevices?.firstOrNull { it.name == state.deviceKey }
            if (live != null) {
                live.name to (if (live.connected) "connected" else "not connected")
            } else {
                val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
                (DEVICE_ROLE_LABELS[d.key] ?: d.name) to (if (d.req) "required for a session" else "optional")
            }
        }
    }

    NocturneSheet(
        title = title,
        meta = meta,
        onClose = ctrl::closeSheet,
        fullscreen = full,
        content = {
            when (sheet) {
                SheetType.GUIDE -> GuideSheet(state, landscape)
                SheetType.FOCUS -> FocusSheet(state, ctrl)
                SheetType.ALERTS -> AlertsSheet(state, ctrl)
                SheetType.SUMMARY -> SummarySheet(state, ctrl)
                SheetType.PREFS -> PrefsSheet(state, ctrl)
                SheetType.SETUP -> SetupBody(state, ctrl)
                SheetType.OPTICAL_TRAIN -> OpticalTrainSheet(state, ctrl)
                SheetType.MODULE_ASSIGNMENTS -> ModuleAssignmentsSheet(state, ctrl)
                SheetType.SCOPES -> ScopesSheet(state, ctrl)
                SheetType.MAINTENANCE -> MaintenanceSheet(state, ctrl)
                SheetType.RIG_HEALTH -> com.nocturne.ui.home.RigHealthSheet(state)
                SheetType.MOUNT_SETTINGS -> MountSettingsSheet(state, ctrl)
                SheetType.ALIGN_SETTINGS -> AlignSettingsSheet(state, ctrl)
                SheetType.GUIDE_SETTINGS -> GuideSettingsSheet(state, ctrl)
                SheetType.FOCUS_SETTINGS -> FocusSettingsSheet(state, ctrl)
                SheetType.SCHEDULER_SETTINGS -> SchedulerSettingsSheet(state, ctrl)
                SheetType.DEVICE -> DeviceSheet(state, ctrl)
            }
        },
        footer = if (sheet == SheetType.SETUP) {
            { SetupFooter(state, ctrl) }
        } else null,
    )
}
