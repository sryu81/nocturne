package com.nocturne.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nocturne.session.DEVICES
import com.nocturne.session.Device
import com.nocturne.session.IndiProperty
import com.nocturne.session.indiSwitchElements
import com.nocturne.session.LiveDevice
import com.nocturne.session.PA_SECS
import com.nocturne.session.RigRebootState
import com.nocturne.session.SkyAtlasStatus
import com.nocturne.session.formatIndiNumber
import com.nocturne.session.formatSiteTime
import com.nocturne.session.sourceLabel
import com.nocturne.session.installedDriverOptions
import com.nocturne.session.realDeviceOptions
import com.nocturne.session.realFilterNames
import com.nocturne.session.realNightWindow
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.session.guideCameraDevice
import com.nocturne.session.alignCameraDevice
import com.nocturne.session.gainSpecialValue
import com.nocturne.session.TrainRole
import com.nocturne.session.TrainSlot
import com.nocturne.session.focRange
import com.nocturne.session.indiNumber
import com.nocturne.session.doneSpec
import com.nocturne.session.endedJob
import com.nocturne.session.fRatio
import com.nocturne.session.get
import com.nocturne.session.opticNote
import com.nocturne.session.ScopeDef
import com.nocturne.session.isOn
import com.nocturne.session.isSelected
import com.nocturne.session.missing
import com.nocturne.session.paTotal
import com.nocturne.session.pct
import com.nocturne.session.ready
import com.nocturne.session.eafTemp
import com.nocturne.session.benchFocPos
import com.nocturne.session.realSlewRateProp
import com.nocturne.session.train
import com.nocturne.session.trainRolePool
import com.nocturne.session.keepCount
import com.nocturne.session.rejectCount
import com.nocturne.session.medHfr
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.GainField
import com.nocturne.protocol.GUIDE_BOX_SIZES
import com.nocturne.ui.components.FocusCurve
import com.nocturne.ui.components.GuideTelemetry
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.NocturneSheet
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturnePalette
import com.nocturne.ui.theme.NocturneTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.nocturne.session.PAH_DIRECTIONS
import com.nocturne.session.PAH_MEASURE_IMAGE
import com.nocturne.session.PAH_REFRESH_ALGORITHMS
import com.nocturne.session.PAH_ROTATE_STAGES
import com.nocturne.session.PAH_ROTATIONS
import com.nocturne.session.PAH_STAGE_REFRESHING
import com.nocturne.session.PAH_STAGE_SELECT_STAR

/** Tintable big circle icon used in alert rows. */
private data class AlertStyle(val color: Color, val icon: ImageVector)

/**
 * Styled by severity, the only classification the wire carries besides `source`.
 * `KSNotification::EventType` (`ksnotification.h`): `Debug`/`Info`/`Warn`/`Alert`.
 */
private fun alertStyle(a: com.nocturne.session.Alert): AlertStyle = when (a.severity) {
    3 -> AlertStyle(NocturnePalette.Danger, Phosphor.Warning) // Alert
    2 -> AlertStyle(NocturnePalette.Warn, Phosphor.Warning) // Warn
    1 -> AlertStyle(NocturnePalette.Ok, Phosphor.CheckCircle) // Info
    else -> AlertStyle(NocturnePalette.Accent, Phosphor.CheckCircle) // Debug
}

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
        SheetType.CAMERA_SETTINGS -> "File settings" to "save path, filename template"
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
                SheetType.CAMERA_SETTINGS -> CameraSettingsSheet(state, ctrl)
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

// ── Guide ────────────────────────────────────────────────────────────────

/**
 * Guide camera frame + status, and live RMS/drift ([GuideTelemetry]) from the fork's guide-stats
 * push (stock EkosRemote only sends `{status}`).
 */
@Composable
private fun GuideSheet(state: AppState, landscape: Boolean) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val guide = state.wireGuideSettings
    val preview = @Composable {
        Panel {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(c.surfaceDeep, RoundedCornerShape(8.dp)),
            ) {
                MediaFramePreview(state.latestGuideFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            }
            Spacer(Modifier.height(10.dp))
            TextC(
                state.wireGuideStatus?.let { "status: $it" } ?: "no status yet",
                style = t.Mono17, color = c.text,
            )
        }
    }
    val info = @Composable {
        GuideTelemetry(state, chartHeight = 120.dp)
        if (guide != null) {
            Spacer(Modifier.height(11.2.dp))
            TextC(
                "${"%.1f".format(guide.guideExposure)}s exposure · gain ${
                    if (state.gainSpecialValue(state.guideCameraDevice)?.let { guide.guideGain <= it + 1e-6 } == true) "--"
                    else "%.0f".format(guide.guideGain)
                } · bin ${guide.guideBinning}",
                style = t.MonoSmall, color = c.neutral500,
            )
        }
    }
    // Landscape: preview left, status right, so everything fits without scrolling.
    if (landscape) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { preview() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { info() }
        }
    } else {
        Column {
            preview()
            Spacer(Modifier.height(11.2.dp))
            info()
        }
    }
}

// ── Focus ────────────────────────────────────────────────────────────────

/**
 * Focus frame + status, the live autofocus V-curve ([FocusCurve], from `new_focus_state`'s
 * per-frame `{hfr, pos}`), and POSITION/TEMP Δ/NEXT AF.
 */
@Composable
private fun FocusSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val tempNow = state.eafTemp
    val tempAtAf = state.focusTempAtLastAf
    val tempDelta = if (tempNow != null && tempAtAf != null) tempNow - tempAtAf else null
    val hfr = state.latestFocusFrame?.header?.hfr
    Column {
        Panel {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(c.surfaceDeep, RoundedCornerShape(8.dp)),
            ) {
                MediaFramePreview(state.latestFocusFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            }
            Spacer(Modifier.height(10.dp))
            TextC(
                listOfNotNull(
                    state.wireFocusStatus?.let { "status: $it" } ?: "no status yet",
                    hfr?.let { "HFR %.2f".format(it) },
                ).joinToString(" · "),
                style = t.Mono17, color = c.text,
            )
            Spacer(Modifier.height(10.dp))
            FocusCurve(state, chartHeight = 140.dp)
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("POSITION", state.benchFocPos?.toString() ?: "N/A", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("TEMP Δ", tempDelta?.let { "%+.1f°".format(it) } ?: "N/A", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            val refocus = state.wireCaptureSettings?.let { if (it.enforceRefocusEveryN) "every ${it.refocusEveryN} m" else "off" } ?: "N/A"
            Stat("REFOCUS", refocus, Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Run autofocus now",
            onClick = ctrl::runAutofocusNow,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
}

// ── Alerts ───────────────────────────────────────────────────────────────

@Composable
private fun AlertsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        // The new_notification stream; empty state until one arrives this connection.
        if (state.wireNotifications.isEmpty()) {
            TextC("No alerts yet this session.", style = t.Body13, color = c.textMuted)
            Spacer(Modifier.height(11.2.dp))
        }
        state.wireNotifications.forEach { a ->
            val st = alertStyle(a)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .padding(11.2.dp),
            ) {
                Box(Modifier.width(2.dp).height(40.dp).background(st.color, RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(11.2.dp))
                Phosphor.Icon(st.icon, size = 17.dp, tint = st.color, modifier = Modifier.padding(top = 1.dp))
                Spacer(Modifier.width(11.2.dp))
                Column(Modifier.weight(1f)) {
                    TextC(a.message, style = t.Body13, color = c.text)
                    TextC("${a.time} · ${a.sourceLabel}", style = t.MonoMicro, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(8.4.dp))
        }
        NocturneButton(
            text = "Alert rules & notifications",
            onClick = { ctrl.openSheet(SheetType.PREFS) },
            icon = Phosphor.SlidersHorizontal,
            style = com.nocturne.ui.components.BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(42.dp),
        )
    }
}

// ── Prefs ────────────────────────────────────────────────────────────────

/**
 * `option_get`/`option_set` toggles. Ekos has no per-category notification settings in
 * `kstars.kcfg`; these two are what exists: the master gate for the `new_notification` stream,
 * and whether Ekos plays a sound on the Pi. `—` until the connect-time fetch replies.
 */
@Composable
private fun PrefsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        SwitchRow(
            label = "Notifications",
            sub = "master switch for the whole real alert stream (new_notification)",
            checked = state.wireEkosRemoteNotifications == true,
            onToggle = { ctrl.setEkosRemoteNotifications(!(state.wireEkosRemoteNotifications ?: false)) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Sound",
            sub = "Ekos plays a sound on the Pi's own speaker for each alert",
            checked = state.wireEkosRemoteSound == true,
            onToggle = { ctrl.setEkosRemoteSound(!(state.wireEkosRemoteSound ?: false)) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))
        // App-side only: the phone's own notifications, not Ekos' alert stream above.
        SwitchRow(
            label = "Push to this phone",
            sub = "alerts, a failing module, a lost link and a full disk while the phone is locked; " +
                "keeps the connection alive with a notification (needs Android's notification permission)",
            checked = state.pushNotificationsEnabled,
            onToggle = { ctrl.setPushNotifications(!state.pushNotificationsEnabled) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        // Quiet hours are app-side only; Ekos has no matching setting.
        SwitchRow(
            label = "Quiet hours",
            sub = "while on, only critical pushes (failures, a lost link) reach the phone; local only, doesn't affect the Pi",
            checked = state.quietHoursEnabled,
            onToggle = ctrl::toggleQuietHours,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
    }
}

// ── Setup ────────────────────────────────────────────────────────────────

/** Rig-setup role names (wizard rows and picker titles), keyed like [DEVICES]. */
private val DEVICE_ROLE_LABELS = mapOf(
    "mount" to "Mount", "cam" to "Main camera", "guide" to "Guide camera", "efw" to "Filter wheel",
    "focus" to "Focuser", "rotator" to "Rotator", "dome" to "Dome", "weather" to "Weather",
    "powerbox" to "Power box",
)

private val DEVICE_ICONS: Map<String, ImageVector> = mapOf(
    "mount" to Phosphor.CompassTool,
    "cam" to Phosphor.Camera,
    "efw" to Phosphor.CirclesThree,
    "guide" to Phosphor.CrosshairSimple,
    "focus" to Phosphor.ArrowsInLineHorizontal,
    "rotator" to Phosphor.ArrowsClockwise,
    "dome" to Phosphor.Garage,
    "weather" to Phosphor.CloudSun,
    "powerbox" to Phosphor.Plugs,
)

/**
 * Rig profile setup: name + device role pickers on one screen. Optical trains are edited
 * separately from the Gear tab.
 */
@Composable
private fun SetupBody(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        FieldLabel("Profile name")
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = state.profileName,
            onValueChange = ctrl::setProfileName,
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Mount and camera are required; the rest can come later.",
            style = t.MonoSmall, color = c.neutral500,
        )
        Spacer(Modifier.height(8.4.dp))
        DEVICES.forEach { d ->
            val selected = state.isSelected(d.key)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .clickable { ctrl.openDevice(d.key) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(DEVICE_ICONS[d.key] ?: Phosphor.Plugs, size = 18.dp, tint = c.neutral500)
                Spacer(Modifier.width(11.2.dp))
                val chosen = state.selectedDeviceNames[d.key]?.takeIf { selected && it != "None" }
                Column(Modifier.weight(1f)) {
                    TextC(DEVICE_ROLE_LABELS[d.key] ?: d.key, style = t.MonoMicro, color = c.textFaint)
                    TextC(chosen ?: "Choose a driver…", style = t.Body13, color = if (chosen != null) c.text else c.textMuted)
                }
                TextC(
                    if (selected) "SELECTED" else if (d.req) "REQUIRED" else "OFF",
                    style = t.MonoMicro,
                    color = if (selected) c.ok else if (d.req) c.danger else c.textFaint,
                    modifier = Modifier
                        .background(
                            if (selected) c.ok.copy(alpha = 0.14f) else if (d.req) c.danger.copy(alpha = 0.16f) else c.divider.copy(alpha = 0.4f),
                            RoundedCornerShape(3.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(8.4.dp))
        }
    }
}

/** Scope entry: name + focal length + aperture, the Ekos Scopes catalog fields (`scope_add`). */
@Composable
private fun ScopeInputFields(
    label: String,
    name: String, onNameChange: (String) -> Unit,
    focalMm: Int, onFocalChange: (Int) -> Unit,
    apertureMm: Int, onApertureChange: (Int) -> Unit,
    note: String,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    FieldLabel(label)
    Spacer(Modifier.height(5.dp))
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        textStyle = t.Body13.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
    )
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth()) {
        MmField("Focal length", focalMm, Modifier.weight(1f), onFocalChange)
        Spacer(Modifier.width(8.4.dp))
        MmField("Aperture", apertureMm, Modifier.weight(1f), onApertureChange)
    }
    Spacer(Modifier.height(5.dp))
    TextC("$note · ${fRatio(focalMm, apertureMm)}", style = t.MonoMicro, color = c.textMuted)
}

/**
 * Small labeled digits-only mm field for scope focal length/aperture. Keeps its own `text` so it
 * can be cleared mid-edit; [onChange] fires only when the text parses.
 */
@Composable
private fun MmField(label: String, mm: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf(mm.toString()) }
    Column(modifier) {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }.take(4)
                    text = filtered
                    filtered.toIntOrNull()?.let(onChange)
                },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            TextC("mm", style = t.MonoSmall, color = c.neutral500)
        }
    }
}

/**
 * Ekos's Scopes catalog (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`, see
 * docs/PROTOCOL.md): a flat list of telescopes/lenses, separate from the profile. A train's
 * Scope role picks one of these by name.
 */
@Composable
private fun ScopesSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val scopes = state.wireScopes ?: state.scopes
    Column {
        scopes.forEach { scope ->
            val editing = state.editingScopeId == scope.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (editing) c.accent.copy(alpha = 0.14f) else c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, if (editing) c.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { ctrl.toggleEditScope(scope.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC(scope.name, style = t.Body13, color = c.text)
                    TextC(
                        "${scope.focalMm} mm · ${fRatio(scope.focalMm, scope.apertureMm)} · ${scope.apertureMm} mm aperture",
                        style = t.MonoSmall, color = c.textDim,
                    )
                }
                IconBtn(icon = Phosphor.X, onClick = { ctrl.removeScope(scope.id) }, size = 28, tint = c.danger)
                Spacer(Modifier.width(6.dp))
                IconBtn(icon = Phosphor.CaretRight, onClick = { ctrl.toggleEditScope(scope.id) }, size = 28)
            }
            if (editing) {
                Spacer(Modifier.height(8.4.dp))
                ScopeEditorForm(
                    initial = scope,
                    onSave = { name, focalMm, apertureMm ->
                        ctrl.updateScope(scope.id, name, scope.vendor, scope.type, focalMm, apertureMm)
                        ctrl.toggleEditScope(scope.id)
                    },
                    onCancel = { ctrl.toggleEditScope(scope.id) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (state.addingScope) {
            ScopeEditorForm(
                initial = null,
                onSave = { name, focalMm, apertureMm -> ctrl.addScope(name, vendor = "", type = "", focalMm = focalMm, apertureMm = apertureMm) },
                onCancel = ctrl::cancelAddScope,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .clickable { ctrl.startAddScope() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(Phosphor.Plus, size = 16.dp, tint = c.accent400)
                Spacer(Modifier.width(8.dp))
                TextC("New scope", style = t.Body13, color = c.accent400)
            }
        }
    }
}

/** Inline add/edit form for one [ScopeDef]. */
@Composable
private fun ScopeEditorForm(initial: ScopeDef?, onSave: (name: String, focalMm: Int, apertureMm: Int) -> Unit, onCancel: () -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var focalMm by remember(initial?.id) { mutableStateOf(initial?.focalMm ?: 550) }
    var apertureMm by remember(initial?.id) { mutableStateOf(initial?.apertureMm ?: 80) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        ScopeInputFields(
            label = "Name",
            name = name, onNameChange = { name = it },
            focalMm = focalMm, onFocalChange = { focalMm = it },
            apertureMm = apertureMm, onApertureChange = { apertureMm = it },
            note = opticNote(focalMm),
        )
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Cancel", onClick = onCancel,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(40.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Save", onClick = { onSave(name, focalMm, apertureMm) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f).height(40.dp),
            )
        }
    }
}

@Composable
private fun SetupFooter(state: AppState, ctrl: SessionController) {
    val ready = listOf("mount", "cam").all { state.isSelected(it) && state.selectedDeviceNames[it] != "None" }
    Row(Modifier.fillMaxWidth()) {
        NocturneButton(
            text = "Cancel",
            onClick = ctrl::setupBack,
            style = com.nocturne.ui.components.BtnStyle.SUBTLE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Spacer(Modifier.width(8.4.dp))
        NocturneButton(
            text = "Save profile",
            onClick = ctrl::finishSetup,
            enabled = ready,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
    }
}

// ── Mount settings (curated subset) ─────────────────────────────────────────

/**
 * Curated subset of Ekos's Mount tab: meridian flip, altitude/HA slew limits, auto-park.
 * [AppState.wireMountSettings] is null until the first `mount_get_all_settings` reply.
 */
@Composable
private fun MountSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    var confirmSetHome by remember { mutableStateOf(false) }
    val m = state.wireMountSettings
    if (m == null) {
        TextC("Fetching mount settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        SwitchRow(
            label = "Execute meridian flip",
            sub = "flip automatically when the mount crosses the meridian",
            checked = m.executeMeridianFlip,
            onToggle = { ctrl.setMountMeridianFlip(!m.executeMeridianFlip) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Flip offset")
        Spacer(Modifier.height(5.dp))
        DegreeField(m.meridianFlipOffsetDegrees, "min", ctrl::setMountMeridianFlipOffset)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Altitude limits",
            sub = "refuse to slew past these altitudes",
            checked = m.enableAltitudeLimits,
            onToggle = { ctrl.setMountAltLimitEnabled(!m.enableAltitudeLimits) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.enableAltitudeLimits) {
            Spacer(Modifier.height(8.4.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Min alt")
                    Spacer(Modifier.height(5.dp))
                    DegreeField(m.minimumAltLimit, "°", ctrl::setMountAltLimitMin)
                }
                Spacer(Modifier.width(11.2.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("Max alt")
                    Spacer(Modifier.height(5.dp))
                    DegreeField(m.maximumAltLimit, "°", ctrl::setMountAltLimitMax)
                }
            }
            Spacer(Modifier.height(8.4.dp))
            SwitchRow(
                label = "Tracking only",
                sub = "only enforce while tracking, not on manual slews",
                checked = m.enableAltitudeLimitsTrackingOnly,
                onToggle = { ctrl.setMountAltLimitTrackingOnly(!m.enableAltitudeLimitsTrackingOnly) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Hour-angle limit",
            sub = "refuse to slew past this many hours from the meridian",
            checked = m.enableHaLimit,
            onToggle = { ctrl.setMountHaLimitEnabled(!m.enableHaLimit) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.enableHaLimit) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Max hour angle")
            Spacer(Modifier.height(5.dp))
            DegreeField(m.maximumHaLimit, "h", ctrl::setMountHaLimitMax)
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Park every day",
            sub = "auto-park the mount at a fixed time",
            checked = m.parkEveryDay,
            onToggle = { ctrl.setMountParkEveryDay(!m.parkEveryDay) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.parkEveryDay) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Park time (HH:MM:SS)")
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = m.autoParkTime,
                    onValueChange = ctrl::setMountAutoParkTime,
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if ("SET" in state.indiSwitchElements(state.primaryTrain.mount, "TELESCOPE_HOME")) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            FieldLabel("Home position")
            Spacer(Modifier.height(5.dp))
            NocturneButton(
                text = "Set home to current position",
                onClick = { confirmSetHome = true },
                style = BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
    if (confirmSetHome) {
        com.nocturne.ui.components.ConfirmDialog(
            title = "Set home here?",
            message = "The mount's current position becomes its home. Go home and homing will return here.",
            confirmText = "Set home",
            confirmStyle = BtnStyle.DANGER,
            onConfirm = {
                confirmSetHome = false
                ctrl.homeMount("SET")
            },
            onDismiss = { confirmSetHome = false },
        )
    }
}

/**
 * Numeric field row for degree/hour-angle inputs. Keeps its own `text` so partial input (empty,
 * `"-"`, a trailing `"."`) survives recomposition; [onChange] fires only when the text parses.
 */
@Composable
private fun DegreeField(value: Double, unit: String, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("%.1f".format(value)) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { new ->
                val filtered = new.filter { it.isDigit() || it == '.' || it == '-' }
                text = filtered
                filtered.toDoubleOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
        TextC(unit, style = t.MonoSmall, color = c.neutral500)
    }
}

// ── Camera file settings ────────────────────────────────────────────────────

/**
 * Capture-module file settings: save path and filename template (`fileDirectoryT`/
 * `placeholderFormatT`). Opened from the Frames tab. [AppState.wireCaptureSettings] is null until
 * the first `capture_get_all_settings` reply.
 */
@Composable
private fun CameraSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val cam = state.wireCaptureSettings
    if (cam == null) {
        TextC("Fetching camera settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Save path")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = cam.fileDirectoryT,
                onValueChange = ctrl::setCameraSaveDir,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Placeholder format")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = cam.placeholderFormatT,
                onValueChange = ctrl::setCameraPlaceholderFormat,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(5.dp))
        TextC(
            "Real subfolder/filename template, applied after Save path — e.g. /%t/%T/%F/ (%t target, %T frame type, %F filter).",
            style = t.MonoMicro, color = c.textMuted,
        )
    }
}

/** Integer counterpart of [DegreeField], with the same local text buffer. */
@Composable
private fun IntField(value: Int, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("$value") }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { new ->
                val filtered = new.filter { it.isDigit() }
                text = filtered
                filtered.toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Align settings (curated subset) ─────────────────────────────────────────

/** Ekos's alignBinning combo options; the wire value is a string like `"1x1"`, not a number. */
private val ALIGN_BINNING_OPTIONS = listOf("1x1", "2x2", "3x3", "4x4")

/**
 * Curated subset of Ekos's Align tab: exposure, gain, filter, binning, solver accuracy.
 * [AppState.wireAlignSettings] is null until the first `align_get_all_settings` reply.
 */
@Composable
private fun AlignSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val a = state.wireAlignSettings
    if (a == null) {
        TextC("Fetching align settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Exposure")
        Spacer(Modifier.height(5.dp))
        DegreeField(a.alignExposure, "s", ctrl::setAlignExposure)
        Spacer(Modifier.height(8.4.dp))
        GainField(a.alignGain, state.gainSpecialValue(state.alignCameraDevice), Modifier.fillMaxWidth(), ctrl::setAlignGain)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // alignUseCurrentFilter (align.ui, Align::checkFilter): on, a solve never changes the
        // filter; off, a solve switches to alignFilter every time, even mid-sequence.
        SwitchRow(
            label = "Use current filter",
            sub = "Off: solving force-switches to the filter below, even mid-sequence",
            checked = a.alignUseCurrentFilter,
            onToggle = { ctrl.setAlignUseCurrentFilter(!a.alignUseCurrentFilter) },
        )
        Spacer(Modifier.height(8.4.dp))
        // With "Use current filter" on, the filter chip shows Capture's loaded filter read-only,
        // as Ekos disables alignFilter in that case.
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Filter")
                Spacer(Modifier.height(5.dp))
                if (a.alignUseCurrentFilter) {
                    CycleChip(state.wireCaptureSettings?.FilterPosCombo?.ifBlank { "—" } ?: "—", enabled = false) {}
                } else {
                    // Cycle the wheel's own slot names; Ekos silently rejects a name it doesn't have.
                    val filterNames = state.realFilterNames.orEmpty()
                    CycleChip(a.alignFilter.ifBlank { "N/A" }) {
                        if (filterNames.isNotEmpty()) ctrl.setAlignFilter(filterNames[(filterNames.indexOf(a.alignFilter) + 1).mod(filterNames.size)])
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Binning")
                Spacer(Modifier.height(5.dp))
                CycleChip(a.alignBinning) { ctrl.setAlignBinning(ALIGN_BINNING_OPTIONS[(ALIGN_BINNING_OPTIONS.indexOf(a.alignBinning) + 1).mod(ALIGN_BINNING_OPTIONS.size)]) }
            }
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        FieldLabel("Solver accuracy threshold")
        Spacer(Modifier.height(5.dp))
        DegreeField(a.alignAccuracyThreshold, "\"", ctrl::setAlignAccuracyThreshold)
        Spacer(Modifier.height(8.4.dp))
        // Arcminutes, unlike the arcsec accuracy above. At 0 the rotator readback is never
        // "within threshold".
        FieldLabel("Rotator threshold")
        Spacer(Modifier.height(5.dp))
        DegreeField(a.kcfg_AstrometryRotatorThreshold, "'", ctrl::setAlignRotatorThreshold)
    }
}

/** Tap-to-cycle chip; with [enabled] false it is dimmed and not clickable. */
@Composable
private fun CycleChip(value: String, enabled: Boolean = true, onTap: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .then(if (enabled) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        TextC(value, style = t.Body13, color = if (enabled) c.text else c.textFaint)
    }
}

// ── Guide settings (curated subset) ─────────────────────────────────────────

/**
 * Curated subset of Ekos's Guide tab: accuracy threshold, dither, frame (dark/subframe/auto
 * star/lost star) and calibration. Exposure/gain/binning are on the Controls tab's guide preview.
 * [AppState.wireGuideSettings] is null until the first `guide_get_all_settings` reply.
 */
@Composable
private fun GuideSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val g = state.wireGuideSettings
    if (g == null) {
        TextC("Fetching guide settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Accuracy threshold")
        Spacer(Modifier.height(5.dp))
        DegreeField(g.guiderAccuracyThreshold, "\"", ctrl::setGuideAccuracyThreshold)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Dither",
            sub = "nudge the mount slightly between subs to average out fixed-pattern noise",
            checked = g.kcfg_DitherEnabled,
            onToggle = { ctrl.setGuideDitherEnabled(!g.kcfg_DitherEnabled) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (g.kcfg_DitherEnabled) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Dither amount")
            Spacer(Modifier.height(5.dp))
            IntField(g.kcfg_DitherPixels, ctrl::setGuideDitherPixels)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Settle threshold")
            Spacer(Modifier.height(5.dp))
            DegreeField(g.kcfg_DitherThreshold, "px", ctrl::setGuideDitherThreshold)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Settle time after dither (s)")
            Spacer(Modifier.height(5.dp))
            IntField(g.kcfg_DitherSettle, ctrl::setGuideDitherSettle)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Dither timeout (s)")
            Spacer(Modifier.height(5.dp))
            IntField(g.kcfg_DitherTimeout, ctrl::setGuideDitherTimeout)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Max dither iterations")
            Spacer(Modifier.height(5.dp))
            IntField(g.kcfg_DitherMaxIterations, ctrl::setGuideDitherMaxIterations)
            Spacer(Modifier.height(8.4.dp))
            GuideSwitch("Dither failure aborts guiding", "otherwise guiding continues after a failed dither", g.kcfg_DitherFailAbortsAutoGuide) {
                ctrl.setGuideDitherFailAbortsGuide(!g.kcfg_DitherFailAbortsAutoGuide)
            }
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // ── Frame (guide.ui) ─────────────────────────────────────────────
        TextC("FRAME", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        GuideSwitch("Dark frame", "subtract a dark from each guide frame", g.guideDarkFrame) {
            ctrl.setGuideDarkFrame(!g.guideDarkFrame)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Subframe", "only download a box around the guide star", g.guideSubframe) {
            ctrl.setGuideSubframe(!g.guideSubframe)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Auto star", "pick the guide star automatically", g.guideAutoStar) {
            ctrl.setGuideAutoStar(!g.guideAutoStar)
        }
        Spacer(Modifier.height(8.4.dp))
        // Ekos locks this combo while the guider is busy (guide.cpp setBusy) — same here.
        FieldLabel("Box size (px)" + if (state.guiding) " · locked while guiding" else "")
        Spacer(Modifier.height(5.dp))
        CycleChip(g.guideSquareSize, enabled = !state.guiding) {
            ctrl.setGuideBoxSize(GUIDE_BOX_SIZES[(GUIDE_BOX_SIZES.indexOf(g.guideSquareSize) + 1).mod(GUIDE_BOX_SIZES.size)])
        }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Lost star timeout (s)")
        Spacer(Modifier.height(5.dp))
        IntField(g.kcfg_GuideLostStarTimeout, ctrl::setGuideLostStarTimeout)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // ── Calibration (opscalibration.ui) ──────────────────────────────
        TextC("CALIBRATION", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        GuideSwitch("Reuse calibration", "skip re-calibrating the guider at the start of each session", g.kcfg_ReuseGuideCalibration) {
            ctrl.setGuideReuseCalibration(!g.kcfg_ReuseGuideCalibration)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Reset calibration after slew", "always re-calibrate when guiding starts after a mount slew", g.kcfg_ResetGuideCalibration) {
            ctrl.setGuideResetCalibration(!g.kcfg_ResetGuideCalibration)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Reverse DEC on pier change", "flip DEC calibration after a meridian flip", g.kcfg_ReverseDecOnPierSideChange) {
            ctrl.setGuideReverseDecOnFlip(!g.kcfg_ReverseDecOnPierSideChange)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Two-axis calibration", "calibrate DEC as well as RA", g.kcfg_TwoAxisEnabled) {
            ctrl.setGuideTwoAxis(!g.kcfg_TwoAxisEnabled)
        }
        Spacer(Modifier.height(8.4.dp))
        GuideSwitch("Clear DEC backlash", "take up DEC gear backlash before calibrating", g.kcfg_GuideCalibrationBacklash) {
            ctrl.setGuideCalibrationBacklash(!g.kcfg_GuideCalibrationBacklash)
        }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Pulse duration (ms)")
        Spacer(Modifier.height(5.dp))
        IntField(g.kcfg_CalibrationPulseDuration, ctrl::setGuideCalibrationPulse)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Max move (px)")
        Spacer(Modifier.height(5.dp))
        IntField(g.kcfg_CalibrationMaxMove, ctrl::setGuideCalibrationMaxMove)
    }
}

@Composable
private fun GuideSwitch(label: String, sub: String, checked: Boolean, onToggle: () -> Unit) {
    SwitchRow(
        label = label,
        sub = sub,
        checked = checked,
        onToggle = onToggle,
        modifier = Modifier.fillMaxWidth().background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
    )
}

// ── Focus settings (curated subset) ─────────────────────────────────────────

/** `opsfocusmechanics.ui` combo (`focusWalk`). "Classic" is Linear-only but the combo always offers it. */
private val FOCUS_WALK_CYCLE = listOf("Classic", "Fixed Steps", "CFZ Shuffle")

/** `opsfocusprocess.ui` combo (`focusDetection`). */
private val FOCUS_DETECTION_CYCLE = listOf("Gradient", "Centroid", "Threshold", "SEP", "Bahtinov")

/** `opsfocusprocess.ui` combo (`focusCurveFit`). */
private val FOCUS_CURVE_FIT_CYCLE = listOf("Quadratic", "Hyperbola", "Parabola", "Gaussian")

/** `opsfocusprocess.ui` combo (`focusStarMeasure`). */
private val FOCUS_STAR_MEASURE_CYCLE =
    listOf("HFR", "HFR Adj", "FWHM", "# Stars", "Fourier", "StdDev", "Sobel", "Laplassian", "Canny")

/**
 * Everyday subset of Ekos's Focus settings, grouped as in Ekos: **Mechanics**
 * (`opsfocusmechanics.ui`) and **Process** (`opsfocusprocess.ui`). Expert tuning (mosaic, masking,
 * adaptive, CFZ calculator, donut…) is left out. Exposure/gain/filter/binning are on the Controls
 * tab's Focuser card. `absTicksSpin` isn't shown: no command sets an absolute position.
 * [AppState.wireFocusSettings] is null until the first `focus_get_all_settings` reply.
 */
@Composable
private fun FocusSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val f = state.wireFocusSettings
    if (f == null) {
        TextC("Fetching focus settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        // ── Mechanics (opsfocusmechanics.ui) ────────────────────────────────
        TextC("MECHANICS", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(10.dp))

        // `focusTicks` is also the step the app's Focus In/Out buttons use (they send steps=0).
        FieldLabel("Initial step size (ticks)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusTicks, ctrl::setFocusTicks)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Out step multiple (× initial step)")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusOutSteps, "x", ctrl::setFocusOutSteps)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Walk")
        Spacer(Modifier.height(5.dp))
        CycleChip(f.focusWalk) { ctrl.setFocusWalk(FOCUS_WALK_CYCLE[(FOCUS_WALK_CYCLE.indexOf(f.focusWalk) + 1).mod(FOCUS_WALK_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Number steps (Fixed Steps / CFZ Shuffle)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusNumSteps, ctrl::setFocusNumSteps)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Driver backlash (ticks)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusBacklash, ctrl::setFocusBacklash)
        Spacer(Modifier.height(8.4.dp))
        // Added to every outward Focus-module move, then corrected back.
        FieldLabel("AF overscan (ticks)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusAFOverscan, ctrl::setFocusAFOverscan)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("AF overscan delay (s)")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusOverscanDelay, "s", ctrl::setFocusOverscanDelay)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Motion timeout (s)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusMotionTimeout, ctrl::setFocusMotionTimeout)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Capture timeout (s)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusCaptureTimeout, ctrl::setFocusCaptureTimeout)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Settle time (s)")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusSettleTime, "s", ctrl::setFocusSettleTime)
        Spacer(Modifier.height(8.4.dp))
        // `focusMaxTravel` ("Max travel" in Ekos) is a per-run search cap (`focus.cpp`
        // searchMin/searchMax), not a device limit, hence the label.
        FieldLabel("Autofocus search range (ticks)")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusMaxTravel, ctrl::setFocusMaxTravel)
        TextC(
            "how far one autofocus run is allowed to search from its start position — not a hardware limit",
            style = t.MonoMicro, color = c.textFaint,
        )
        Spacer(Modifier.height(8.4.dp))
        // The hardware limit: ABS_FOCUS_POSITION min/max, driver-reported and read-only. Shown
        // next to the search range so the two aren't confused.
        val range = state.focRange
        FieldLabel("Real hardware range")
        Spacer(Modifier.height(5.dp))
        TextC(
            if (range != null) "${range.first} – ${range.last} (read-only, from the focuser itself)" else "unknown — not connected yet",
            style = t.Body13, color = c.textMuted,
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // ── Process (opsfocusprocess.ui) ────────────────────────────────────
        TextC("PROCESS", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(10.dp))

        FieldLabel("Star detection")
        Spacer(Modifier.height(5.dp))
        CycleChip(f.focusDetection) { ctrl.setFocusDetection(FOCUS_DETECTION_CYCLE[(FOCUS_DETECTION_CYCLE.indexOf(f.focusDetection) + 1).mod(FOCUS_DETECTION_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))

        // Free text: the algorithm list (e.g. "Linear 1 Pass") isn't enumerated on the wire.
        FieldLabel("Autofocus algorithm")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = f.focusAlgorithm,
                onValueChange = ctrl::setFocusAlgorithm,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Curve fit")
        Spacer(Modifier.height(5.dp))
        CycleChip(f.focusCurveFit) { ctrl.setFocusCurveFit(FOCUS_CURVE_FIT_CYCLE[(FOCUS_CURVE_FIT_CYCLE.indexOf(f.focusCurveFit) + 1).mod(FOCUS_CURVE_FIT_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Star measure")
        Spacer(Modifier.height(5.dp))
        CycleChip(f.focusStarMeasure) { ctrl.setFocusStarMeasure(FOCUS_STAR_MEASURE_CYCLE[(FOCUS_STAR_MEASURE_CYCLE.indexOf(f.focusStarMeasure) + 1).mod(FOCUS_STAR_MEASURE_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Tolerance (%)")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusTolerance, "%", ctrl::setFocusTolerance)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("R² limit")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusR2Limit, "", ctrl::setFocusR2Limit)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Frames per point")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusFramesCount, ctrl::setFocusFramesCount)
    }
}

// ── Scheduler settings (curated subset, see WireSchedulerSettings) ─────────

/**
 * Scheduler-wide policy, laid out like Ekos's Scheduler tab: startup condition, constraints and
 * per-job step defaults, completion condition, observatory startup/shutdown, aborted-job handling,
 * guide & focus limits, dither. [AppState.wireSchedulerSettings] is null until the first
 * `scheduler_get_all_settings` reply.
 */
@Composable
private fun SchedulerSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val sc = state.wireSchedulerSettings
    if (sc == null) {
        TextC("Fetching scheduler settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        TextC("STARTUP CONDITION", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Start ASAP", selected = sc.asapConditionR, onSelect = ctrl::setSchedulerStartAsap)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Start at a specific time", selected = sc.startupTimeConditionR, onSelect = { ctrl.setSchedulerStartAtTime(sc.startupTimeEdit) })
        if (sc.startupTimeConditionR) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Start time (ISO)")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.startupTimeEdit, ctrl::setSchedulerStartAtTime)
        }
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Lead time")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_LeadTime, "min", ctrl::setSchedulerLeadTime)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Pre-dawn cutoff")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_PreDawnTime, "min", ctrl::setSchedulerPreDawnTime)
            }
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("CONSTRAINTS", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Altitude limit", checked = sc.schedulerAltitude,
            onToggle = { ctrl.setSchedulerAltitudeEnabled(!sc.schedulerAltitude) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerAltitude) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerAltitudeValue, "°", ctrl::setSchedulerAltitudeValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Moon separation", checked = sc.schedulerMoonSeparation,
            onToggle = { ctrl.setSchedulerMoonSeparationEnabled(!sc.schedulerMoonSeparation) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerMoonSeparation) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerMoonSeparationValue, "°", ctrl::setSchedulerMoonSeparationValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Moon altitude", checked = sc.schedulerMoonAltitude,
            onToggle = { ctrl.setSchedulerMoonAltitudeEnabled(!sc.schedulerMoonAltitude) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerMoonAltitude) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerMoonAltitudeMaxValue, "°", ctrl::setSchedulerMoonAltitudeMaxValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Twilight", sub = "the real root cause behind the twilight-shutdown incident — see project notes",
            checked = sc.schedulerTwilight,
            onToggle = { ctrl.setSchedulerTwilightEnabled(!sc.schedulerTwilight) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Dawn offset")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_DawnOffset, "h", ctrl::setSchedulerDawnOffset)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Dusk offset")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_DuskOffset, "h", ctrl::setSchedulerDuskOffset)
            }
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Horizon", checked = sc.schedulerHorizon,
            onToggle = { ctrl.setSchedulerHorizonEnabled(!sc.schedulerHorizon) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel("Job steps (new-job defaults)")
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Track", checked = sc.schedulerTrackStep,
            onToggle = { ctrl.setSchedulerTrackStep(!sc.schedulerTrackStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Focus", checked = sc.schedulerFocusStep,
            onToggle = { ctrl.setSchedulerFocusStep(!sc.schedulerFocusStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Align", checked = sc.schedulerAlignStep,
            onToggle = { ctrl.setSchedulerAlignStep(!sc.schedulerAlignStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Guide", checked = sc.schedulerGuideStep,
            onToggle = { ctrl.setSchedulerGuideStep(!sc.schedulerGuideStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Force alignment before each job",
            sub = "Only applies to a job whose own Align step is on — doesn't override one with Align off",
            checked = sc.kcfg_ForceAlignmentBeforeJob,
            onToggle = { ctrl.setSchedulerForceAlignmentBeforeJob(!sc.kcfg_ForceAlignmentBeforeJob) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("COMPLETION CONDITION", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Complete sequences", selected = sc.schedulerCompleteSequences, onSelect = ctrl::setSchedulerCompleteSequences)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Repeat sequences", selected = sc.schedulerRepeatSequences, onSelect = { ctrl.setSchedulerRepeatSequences(sc.schedulerRepeatSequencesLimit) })
        if (sc.schedulerRepeatSequences) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Repeat count")
            Spacer(Modifier.height(5.dp))
            DegreeField(sc.schedulerRepeatSequencesLimit.toDouble(), "×") { ctrl.setSchedulerRepeatSequences(it.toInt()) }
        }
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Repeat everything", selected = sc.schedulerRepeatEverything, onSelect = ctrl::setSchedulerRepeatEverything)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Loop until terminated", selected = sc.schedulerUntilTerminated, onSelect = ctrl::setSchedulerUntilTerminated)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Loop until a date", selected = sc.schedulerUntil, onSelect = { ctrl.setSchedulerUntil(sc.schedulerUntilValue) })
        if (sc.schedulerUntil) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Until (ISO)")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerUntilValue, ctrl::setSchedulerUntil)
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("OBSERVATORY STARTUP / SHUTDOWN", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Startup procedure enabled", checked = sc.schedulerStartupEnabled,
            onToggle = { ctrl.setSchedulerStartupEnabled(!sc.schedulerStartupEnabled) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerStartupEnabled) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Pre-startup script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPreStartupScript, ctrl::setSchedulerPreStartupScript)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Post-startup script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPostStartupScript, ctrl::setSchedulerPostStartupScript)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Shutdown procedure enabled", checked = sc.schedulerShutdownEnabled,
            onToggle = { ctrl.setSchedulerShutdownEnabled(!sc.schedulerShutdownEnabled) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerShutdownEnabled) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Pre-shutdown script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPreShutdownScript, ctrl::setSchedulerPreShutdownScript)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Post-shutdown script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPostShutdownScript, ctrl::setSchedulerPostShutdownScript)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Preemptive shutdown", sub = "shut down early if no job can run before the gap ends",
            checked = sc.kcfg_PreemptiveShutdown,
            onToggle = { ctrl.setSchedulerPreemptiveShutdown(!sc.kcfg_PreemptiveShutdown) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.kcfg_PreemptiveShutdown) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.kcfg_PreemptiveShutdownTime, "h", ctrl::setSchedulerPreemptiveShutdownTime)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Stop Ekos after shutdown", checked = sc.kcfg_StopEkosAfterShutdown,
            onToggle = { ctrl.setSchedulerStopEkosAfterShutdown(!sc.kcfg_StopEkosAfterShutdown) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Shutdown script terminates INDI", checked = sc.kcfg_ShutdownScriptTerminatesINDI,
            onToggle = { ctrl.setSchedulerShutdownScriptTerminatesIndi(!sc.kcfg_ShutdownScriptTerminatesINDI) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("ABORTED-JOB HANDLING", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Don't restart", selected = sc.errorHandlingDontRestartButton, onSelect = ctrl::setSchedulerAbortDontRestart)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Restart immediately", selected = sc.errorHandlingRestartImmediatelyButton, onSelect = ctrl::setSchedulerAbortRestartImmediately)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Restart after re-queueing", selected = sc.errorHandlingRestartQueueButton, onSelect = ctrl::setSchedulerAbortRestartQueue)
        if (sc.errorHandlingRestartImmediatelyButton || sc.errorHandlingRestartQueueButton) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Restart delay")
            Spacer(Modifier.height(5.dp))
            DegreeField(sc.errorHandlingStrategyDelay.toDouble(), "min") { ctrl.setSchedulerAbortDelay(it.toInt()) }
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Reschedule errored jobs", checked = sc.errorHandlingRescheduleErrorsCB,
            onToggle = { ctrl.setSchedulerAbortRescheduleErrors(!sc.errorHandlingRescheduleErrorsCB) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // With this on, Ekos can resume a stale queued job on its own restart.
        SwitchRow(
            label = "Remember job progress", sub = "Ekos can auto-resume a queued job on its own, even before this app reconnects",
            checked = sc.kcfg_RememberJobProgress,
            onToggle = { ctrl.setSchedulerRememberJobProgress(!sc.kcfg_RememberJobProgress) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )

        // ── Guide & Focus Limits ────────────────────────────────────────────────────
        // Ekos's "Guide & Focus Limits" dialog (limits.ui): guide-deviation abort, start-of-job
        // drift, refocus triggers. These and Dither are Capture-module fields, written with
        // capture_set_all_settings; they sit here because they govern an imaging session.
        val cam = state.wireCaptureSettings
        if (cam == null) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            TextC("Fetching guide/focus limits…", style = t.Body13, color = c.textMuted)
        } else {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            TextC("GUIDE & FOCUS LIMITS", style = t.MicroUppercase, color = c.textFaint)
            Spacer(Modifier.height(8.4.dp))

            SwitchRow(
                label = "Guide deviation guard",
                sub = "abort capture if the guide star drifts past this many arcsec",
                checked = cam.enforceGuideDeviation,
                onToggle = { ctrl.setCameraGuideDeviationEnabled(!cam.enforceGuideDeviation) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceGuideDeviation) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max deviation")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.guideDeviation, "\"", ctrl::setCameraGuideDeviation)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Start-of-job drift guard",
                sub = "wait for the guide star to settle within this many arcsec before starting a job",
                checked = cam.enforceStartGuiderDrift,
                onToggle = { ctrl.setCameraStartGuideDriftEnabled(!cam.enforceStartGuiderDrift) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceStartGuiderDrift) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max drift")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.startGuideDeviation, "\"", ctrl::setCameraStartGuideDeviation)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Refocus every N minutes",
                sub = "force an autofocus on this timer, reset at each autofocus",
                checked = cam.enforceRefocusEveryN,
                onToggle = { ctrl.setCameraRefocusEveryNEnabled(!cam.enforceRefocusEveryN) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceRefocusEveryN) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Every N minutes")
                Spacer(Modifier.height(5.dp))
                IntField(cam.refocusEveryN, ctrl::setCameraRefocusEveryN)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Refocus on temperature drift",
                sub = "force an autofocus once temperature has drifted this much since the last one",
                checked = cam.enforceAutofocusOnTemperature,
                onToggle = { ctrl.setCameraRefocusOnTemperatureEnabled(!cam.enforceAutofocusOnTemperature) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceAutofocusOnTemperature) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max ΔT")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.maxFocusTemperatureDelta, "°C", ctrl::setCameraMaxFocusTemperatureDelta)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            // Capture's global dither default (`enableDitherPerJob`/`guideDitherPerJobFrequency`);
            // a block's "Dither every" (`Block.ditherEvery`) overrides it.
            TextC("DITHER (GLOBAL DEFAULT)", style = t.MicroUppercase, color = c.textFaint)
            Spacer(Modifier.height(8.4.dp))
            SwitchRow(
                label = "Dither every job",
                sub = "dither after this many captured subs, not just per-filter",
                checked = cam.enableDitherPerJob,
                onToggle = { ctrl.setCameraDitherPerJobEnabled(!cam.enableDitherPerJob) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enableDitherPerJob) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Every N subs")
                Spacer(Modifier.height(5.dp))
                IntField(cam.guideDitherPerJobFrequency, ctrl::setCameraDitherPerJobFrequency)
            }
        }
    }
}

/** Radio-style selection row with [SwitchRow]'s look; tapping the selected option re-selects it. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    SwitchRow(
        label = label, checked = selected, onToggle = onSelect,
        modifier = Modifier.fillMaxWidth().background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
    )
}

/** Plain single-line text field box, same visual shape as [CameraSettingsSheet]'s save-path field. */
@Composable
private fun TextFieldBox(value: String, onChange: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Rig maintenance ─────────────────────────────────────────────────────────

/**
 * Most recent last-known fix from the GPS or network provider, or null if neither has one. A
 * cached fix is precise enough for site/almanac math, so there's no Play Services dependency or
 * wait for a fresh lock. Elevation defaults to 0.0 when missing; it only affects refraction.
 */
private fun requestLastKnownLocation(context: android.content.Context): Triple<Double, Double, Double>? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val candidates = listOfNotNull(
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
    )
    val best = candidates.maxByOrNull { it.time } ?: return null
    return Triple(best.latitude, best.longitude, if (best.hasAltitude()) best.altitude else 0.0)
}

/**
 * Rig-level recovery. The wire has no reboot command, and a hung Ekos couldn't serve one anyway,
 * so this talks to a companion daemon on the Pi over HTTP (`pi-tools/reboot-daemon/`, [RigRebootClient]).
 */
@Composable
private fun MaintenanceSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    var portText by remember { mutableStateOf(state.rigRebootPort.toString()) }
    var showConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }

    // Location sync goes over the EkosRemote wire, not the daemon.
    val context = LocalContext.current
    var locationError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            requestLastKnownLocation(context)?.let { (lat, lon, elev) -> ctrl.syncLocationToRig(lat, lon, elev) }
                ?: run { locationError = "No cached GPS fix yet — open Maps once, then retry" }
        } else {
            locationError = "Location permission denied"
        }
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.warn.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Phosphor.Icon(Phosphor.Warning, size = 16.dp, tint = c.warn)
            Spacer(Modifier.width(10.dp))
            TextC(
                "Reboot and shut down act on the Pi itself, not just Ekos — they cut power to any " +
                    "in-progress slew, capture, or guiding. After a reboot, reconnect once the Pi is back; " +
                    "after a shutdown it stays off until you cycle its power.",
                style = t.Caption, color = c.warn,
            )
        }
        Spacer(Modifier.height(20.dp))

        TextC("REBOOT DAEMON PORT", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        TextC(
            "Same host as the rig connection. Default 9001 (pi-tools/reboot-daemon).",
            style = t.MonoMicro, color = c.textFaint,
        )
        val typedPort = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (typedPort != null && typedPort != state.rigRebootPort) {
            Spacer(Modifier.height(10.dp))
            NocturneButton(
                text = "Save port",
                onClick = { ctrl.setRigRebootPort(typedPort) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))

        NocturneButton(
            text = when (state.rigRebootState) {
                RigRebootState.SENDING -> "Sending…"
                else -> "Reboot Pi"
            },
            onClick = { showConfirm = true },
            enabled = state.rigRebootState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.rigRebootState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(10.dp))
                TextC("Reboot sent — rig will be back in ~60s.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(10.dp))
                TextC("Couldn't reboot: ${state.rigRebootError}", style = t.Caption, color = c.danger)
            }
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        NocturneButton(
            text = if (state.rigShutdownState == RigRebootState.SENDING) "Sending…" else "Shut down Pi",
            onClick = { showShutdownConfirm = true },
            enabled = state.rigShutdownState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.rigShutdownState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(10.dp))
                TextC("Shutdown sent — the Pi powers off in a few seconds.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(10.dp))
                TextC("Couldn't shut down: ${state.rigShutdownError}", style = t.Caption, color = c.danger)
            }
            else -> {}
        }
        Spacer(Modifier.height(24.dp))
        HDivider()
        Spacer(Modifier.height(24.dp))

        // Location goes over the wire (kstars_set_location). Time sets both the Pi OS clock
        // (daemon) and KStars' clock (wire).
        TextC("GPS / TIME SYNC", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        NocturneButton(
            text = "Sync location from phone GPS",
            onClick = {
                locationError = null
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    requestLastKnownLocation(context)?.let { (lat, lon, elev) -> ctrl.syncLocationToRig(lat, lon, elev) }
                        ?: run { locationError = "No cached GPS fix yet — open Maps once, then retry" }
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        locationError?.let {
            Spacer(Modifier.height(8.dp))
            TextC(it, style = t.Caption, color = c.danger)
        }
        Spacer(Modifier.height(12.dp))
        NocturneButton(
            text = when (state.piTimeSyncState) {
                RigRebootState.SENDING -> "Syncing…"
                else -> "Sync time to rig"
            },
            onClick = ctrl::syncTimeToRig,
            enabled = state.piTimeSyncState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.piTimeSyncState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(8.dp))
                TextC("Time synced — Pi OS clock + KStars' own clock both updated.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(8.dp))
                TextC(
                    "KStars' own clock still updated over the wire; Pi OS clock didn't: ${state.piTimeSyncError}",
                    style = t.Caption, color = c.warn,
                )
            }
            else -> {}
        }
        Spacer(Modifier.height(24.dp))
        HDivider()
        Spacer(Modifier.height(24.dp))
        SkyAtlasSection(state.skyAtlas, ctrl)
    }

    if (showConfirm) {
        com.nocturne.ui.components.TypedConfirmDialog(
            title = "Reboot the rig?",
            message = "This power-cycles the Pi. Anything mid-slew, mid-capture, or mid-guide " +
                "will be cut off ungracefully.",
            requiredText = "REBOOT",
            confirmText = "Reboot",
            onConfirm = {
                showConfirm = false
                ctrl.rebootRig()
            },
            onDismiss = { showConfirm = false },
        )
    }
    if (showShutdownConfirm) {
        com.nocturne.ui.components.TypedConfirmDialog(
            title = "Shut down the rig?",
            message = "This powers the Pi off. It will not come back until its power is cycled, and " +
                "anything mid-slew, mid-capture, or mid-guide will be cut off ungracefully.",
            requiredText = "SHUTDOWN",
            confirmText = "Shut down",
            onConfirm = {
                showShutdownConfirm = false
                ctrl.shutdownRig()
            },
            onDismiss = { showShutdownConfirm = false },
        )
    }
}

/**
 * Offline sky atlas (DSS2 colour HiPS, orders 0..5) for the Plan tab's Framing card, copied from
 * the rig's own mirror over the reboot daemon. Tiles don't depend on the scope or camera, so one
 * copy frames any target at any focal length with no internet.
 */
@Composable
private fun SkyAtlasSection(atlas: SkyAtlasStatus, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    LaunchedEffect(Unit) { ctrl.refreshSkyAtlas() }

    TextC("SKY ATLAS (OFFLINE FRAMING)", style = t.MicroUppercase, color = c.textMuted)
    Spacer(Modifier.height(8.dp))
    TextC(
        if (atlas.copying) "Copying: checked ${atlas.copyDone} / ${atlas.totalTiles} tiles"
        else "On phone: ${atlas.localTiles} / ${atlas.totalTiles} tiles" +
            if (atlas.completeOrder >= 0) " · frames from level ${atlas.completeOrder}" else " · not usable yet",
        style = t.Body13, color = c.text,
    )
    Spacer(Modifier.height(4.dp))
    TextC(
        atlas.rigTiles?.let { "On rig: $it / ${atlas.totalTiles} tiles" } ?: "On rig: checking…",
        style = t.MonoMicro, color = c.textFaint,
    )
    Spacer(Modifier.height(12.dp))
    if (atlas.copying) {
        LinearProgressIndicator(
            progress = { atlas.copyDone.toFloat() / atlas.totalTiles.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
            color = c.accent,
            trackColor = c.divider,
        )
        Spacer(Modifier.height(10.dp))
        NocturneButton(
            text = "Cancel copy",
            onClick = ctrl::cancelSkyAtlasCopy,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        NocturneButton(
            text = if (atlas.localTiles == 0) "Copy atlas from rig (~1 GB)" else "Copy missing tiles from rig",
            onClick = ctrl::copySkyAtlasFromRig,
            enabled = atlas.localTiles < atlas.totalTiles,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        if (atlas.localTiles > 0) {
            Spacer(Modifier.height(10.dp))
            NocturneButton(
                text = "Delete loaded tiles (level 5) from phone",
                onClick = ctrl::clearSkyAtlas,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    atlas.lastMissing?.takeIf { it > 0 }?.let {
        Spacer(Modifier.height(8.dp))
        TextC("$it tiles not on the rig yet (its download is still running) — copy again later.", style = t.Caption, color = c.warn)
    }
    atlas.error?.let {
        Spacer(Modifier.height(8.dp))
        TextC(it, style = t.Caption, color = c.danger)
    }
}

/**
 * Optical train role editor: a fixed Primary and Secondary train (no add/remove), each with the
 * 10 roles of `train_get_all` (Mount/Camera/Rotator/Guide via/Dust cap/Scope/Filter wheel/Focuser/
 * Reducer/Light box). Pools come from the profile's devices and the Scopes catalog; Reducer is a
 * multiplier. Which module uses which train is [ModuleAssignmentsSheet].
 */
@Composable
private fun OpticalTrainSheet(state: AppState, ctrl: SessionController) {
    var slot by remember { mutableStateOf(TrainSlot.PRIMARY) }
    Column {
        Row(Modifier.border(1.dp, NocturneTheme.colors.divider, RoundedCornerShape(10.dp))) {
            listOf(TrainSlot.PRIMARY to "Primary", TrainSlot.SECONDARY to "Secondary").forEach { (s, label) ->
                val sel = s == slot
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (sel) NocturneTheme.colors.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { slot = s }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextC(label, style = NocturneTheme.type.Button13, color = if (sel) NocturneTheme.colors.accent400 else NocturneTheme.colors.textDim)
                }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TrainForm(state, ctrl, slot)
    }
}

/**
 * Which train each Ekos module (Camera/Focus/Mount/Guide/Align/Dark Library) uses, stored
 * per active profile (`train_set`/`train_get_profiles`). Gear hides the entry until trains exist.
 */
@Composable
private fun ModuleAssignmentsSheet(state: AppState, ctrl: SessionController) {
    val trainNames = state.wireTrains?.map { it.name } ?: emptyList()
    Column {
        MODULE_ASSIGNMENT_LABELS.forEach { (moduleKey, label) ->
            RoleRow(
                label = label,
                options = trainNames,
                selected = state.moduleTrainAssignments?.get(moduleKey) ?: "",
                onSelect = { ctrl.setModuleTrain(moduleKey, it) },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * `train_set` module keys + labels: the 6 Ekos modules with their own Optical Train combo. The
 * wire key is `"capture"` but Ekos labels the tab Camera. Scheduler picks trains per job, and
 * Setup/Analyze/Observatory have none, so they're absent.
 *
 * Dark Library builds master darks for whichever camera its train points at, independently of
 * Capture's train (e.g. darks for the guide camera).
 */
private val MODULE_ASSIGNMENT_LABELS = listOf(
    "capture" to "Camera",
    "focus" to "Focus",
    "mount" to "Mount",
    "guide" to "Guide",
    "align" to "Align",
    "darklibrary" to "Dark Library",
)

private val TRAIN_ROLE_LABELS = listOf(
    TrainRole.MOUNT to "Mount",
    TrainRole.CAMERA to "Camera",
    TrainRole.ROTATOR to "Rotator",
    TrainRole.GUIDE_VIA to "Guide via",
    TrainRole.DUST_CAP to "Dust cap",
    TrainRole.SCOPE to "Scope/Lens",
    TrainRole.FILTER_WHEEL to "Filter wheel",
    TrainRole.FOCUSER to "Focuser",
    TrainRole.LIGHT_BOX to "Light box",
    TrainRole.ADAPTIVE_OPTICS to "Adaptive optics",
)

@Composable
private fun TrainForm(state: AppState, ctrl: SessionController, slot: TrainSlot) {
    val train = state.train(slot)
    Column {
        TRAIN_ROLE_LABELS.forEach { (role, label) ->
            val pool = state.trainRolePool(role)
            RoleRow(
                label = label,
                options = pool,
                selected = train.get(role),
                onSelect = { ctrl.setTrainRole(slot, role, it) },
            )
            Spacer(Modifier.height(8.4.dp))
        }
        FieldLabel("Reducer/Barlow")
        Spacer(Modifier.height(5.dp))
        ReducerField(value = train.reducer, onChange = { ctrl.setTrainReducer(slot, it) })
    }
}

/** One role's picker: a horizontally scrolling chip row. */
@Composable
private fun RoleRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(label, style = t.CaptionMedium, color = c.textDim)
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
        ) {
            options.forEach { opt ->
                val sel = opt == selected
                Box(
                    Modifier
                        .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    TextC(opt, style = t.Body13, color = if (sel) c.accent400 else c.textDim)
                }
            }
        }
    }
}

/** Numeric reducer/barlow multiplier — plain digits+dot field, "1.00x" style. */
@Composable
private fun ReducerField(value: Double, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = "%.2f".format(value),
            onValueChange = { text ->
                text.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
        TextC("x", style = t.MonoSmall, color = c.neutral500)
    }
}

// ── Polar align ──────────────────────────────────────────────────────────


/**
 * Polar Alignment Assistant, driven by `new_polar_state`. Pushes are independent partial
 * shapes (`{"stage"}`, `{"message"}`, `{"vector"}`, `{"enabled"}`, updated errors), merged in
 * [AppState]. Stage strings are the `PAHStages` table (see [PAH_MEASURE_IMAGE]), so the
 * sheet branches on them: image progress while measuring, Done rotating (manual slew), Select
 * Star → Start adjusting, Refreshing → Done. Settings are the assistant's own widgets via
 * `align_set_all_settings`, editable only when the desktop enables them.
 *
 * Shown on the Controls tab's Polar alignment page: this is the scrolling body, and
 * [PolarAlignmentActions] is the button bar pinned under it.
 */
@Composable
internal fun PolarAlignmentBody(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val stage = state.wirePolarStage
    val running = state.polarRunning
    val pah = state.wireAlignSettings
    val manualSlew = pah?.pAHManualSlew == true
    val algorithm = pah?.pAHRefreshAlgorithm
    val starSelect = stage == PAH_STAGE_SELECT_STAR
    // Move Star methods need a star picked on the image; Plate Solve doesn't.
    val canPickStar = starSelect && algorithm != null && algorithm != PAH_REFRESH_ALGORITHMS[0]

    Column {
        TextC(
            stage ?: if (running) "running…" else "not started",
            style = t.Mono26, color = if (running) c.warn else c.text,
        )
        PAH_MEASURE_IMAGE[stage]?.let { image ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(if (i <= image) c.accent else c.divider, RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            TextC("measuring · image $image of 3", style = t.MonoSmall, color = c.textMuted)
        }
        Spacer(Modifier.height(9.dp))
        TextC(
            state.wirePolarMessage?.let(::appPahMessage) ?: "Put the mount either in the home position pointed toward the celestial pole, or pointed anywhere near the meridian, then tap Start.",
            style = t.Body13, color = c.textMuted,
        )
        // Align frame. Once PAH has a result, Ekos sends "+A" updated frames
        // (Media::sendUpdatedFrame) cropped from AlignView's display pixmap, which already has
        // Ekos's own correction triangle / plate-solve arrows painted in (FITSView::updateFrame
        // → AlignView::drawOverlay) — so nothing is drawn client-side, it would only duplicate.
        Spacer(Modifier.height(14.dp))
        PaImage(state, canPickStar, ctrl)
        if (canPickStar) {
            Spacer(Modifier.height(6.dp))
            TextC("Tap a bright star to place the correction triangle.", style = t.Caption, color = c.accent400)
        }
        // updatedError/Az/Alt arrive during refresh (setUpdatedErrors); vector.* with the
        // measurement (setPolarResults). Az/alt are signed (which way to turn the knob); -1/-1/-1
        // means "not computed yet", so only the total can be tested against it.
        val totalErr = (state.wirePolarUpdatedError ?: state.wirePolarVector?.error)?.takeIf { it >= 0 }
        if (totalErr != null) {
            val azErr = state.wirePolarUpdatedAzError ?: state.wirePolarVector?.azError
            val altErr = state.wirePolarUpdatedAltError ?: state.wirePolarVector?.altError
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                TextC("total %.2f°".format(totalErr), style = t.MonoMid, color = c.text, modifier = Modifier.weight(1f))
                TextC(azErr?.let { "az %+.2f°".format(it) } ?: "az —", style = t.MonoMid, color = c.textMuted, modifier = Modifier.weight(1f))
                TextC(altErr?.let { "alt %+.2f°".format(it) } ?: "alt —", style = t.MonoMid, color = c.textMuted, modifier = Modifier.weight(1f))
            }
        }

        // Settings — same enable rules as the desktop assistant: measurement settings only while
        // idle, refresh exposure/method also at Select Star (before adjusting starts).
        if (!running || starSelect) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            if (pah?.pAHDirection == null) {
                TextC("Polar alignment settings load once Ekos's Align module replies.", style = t.Caption, color = c.textFaint)
            } else {
                if (!running) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Direction")
                            Spacer(Modifier.height(5.dp))
                            CycleChip(pah.pAHDirection) {
                                ctrl.setPahDirection(PAH_DIRECTIONS[(PAH_DIRECTIONS.indexOf(pah.pAHDirection) + 1).mod(PAH_DIRECTIONS.size)])
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Rotation")
                            Spacer(Modifier.height(5.dp))
                            val rot = pah.pAHRotation ?: 30
                            CycleChip("$rot°") {
                                ctrl.setPahRotation(PAH_ROTATIONS[(PAH_ROTATIONS.indexOf(rot) + 1).mod(PAH_ROTATIONS.size)])
                            }
                        }
                    }
                    // Mount speed options are the mount's own slew-rate labels (the desktop combo
                    // is filled from the same TELESCOPE_SLEW_RATE list).
                    val speeds = state.realSlewRateProp?.options.orEmpty()
                    if (speeds.isNotEmpty() && pah.pAHMountSpeed != null) {
                        Spacer(Modifier.height(8.4.dp))
                        FieldLabel("Mount speed")
                        Spacer(Modifier.height(5.dp))
                        CycleChip(pah.pAHMountSpeed) {
                            ctrl.setPahMountSpeed(speeds[(speeds.indexOf(pah.pAHMountSpeed) + 1).mod(speeds.size)])
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        label = "Manual slew",
                        sub = "For non-GOTO mounts — you rotate RA by hand, then tap Done",
                        checked = manualSlew,
                        onToggle = { ctrl.setPahManualSlew(!manualSlew) },
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Refresh exposure")
                        Spacer(Modifier.height(5.dp))
                        DegreeField(pah.pAHExposure ?: 2.0, "s") { ctrl.setPahExposure(it.coerceIn(1.0, 30.0)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Refresh method")
                        Spacer(Modifier.height(5.dp))
                        val alg = algorithm ?: PAH_REFRESH_ALGORITHMS[0]
                        CycleChip(alg) {
                            ctrl.setPahRefreshAlgorithm(PAH_REFRESH_ALGORITHMS[(PAH_REFRESH_ALGORITHMS.indexOf(alg) + 1).mod(PAH_REFRESH_ALGORITHMS.size)])
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Only matters before the mount moves on its own; once adjusting by hand it's just noise.
        if (!running || stage in PAH_ROTATE_STAGES || PAH_MEASURE_IMAGE[stage] != null) {
            TextC(
                "Real PAH drives its own mount rotation sequence once started — watch the mount. Stop cancels immediately, mid-motion if it's already rotating.",
                style = t.MonoMicro, color = c.warn,
            )
        }
    }
}

/**
 * The buttons for the current stage: the primary action (Start, Done rotating, Start adjusting,
 * Done) and Stop while a run is going; every stage has at least one.
 */
@Composable
internal fun PolarAlignmentActions(state: AppState, ctrl: SessionController) {
    val stage = state.wirePolarStage
    val running = state.polarRunning
    val manualSlew = state.wireAlignSettings?.pAHManualSlew == true
    val starSelect = stage == PAH_STAGE_SELECT_STAR
    val refreshing = stage == PAH_STAGE_REFRESHING
    val primary: Pair<String, () -> Unit>? = when {
        !running -> "Start" to ctrl::startPolarAlign
        stage in PAH_ROTATE_STAGES && manualSlew -> "Done rotating" to ctrl::polarSlewDone
        starSelect -> "Start adjusting" to ctrl::polarRefresh
        refreshing -> "Done" to ctrl::polarRefreshDone
        else -> null
    }
    // Refreshing's "Done" already ends the run (same server call as Stop).
    val showStop = running && !refreshing
    Column {
        primary?.let { (label, action) ->
            com.nocturne.ui.components.NocturneButton(
                text = label,
                onClick = action,
                style = com.nocturne.ui.components.BtnStyle.SOLID,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
        if (showStop) {
            if (primary != null) Spacer(Modifier.height(8.dp))
            com.nocturne.ui.components.NocturneButton(
                text = "Stop",
                onClick = ctrl::stopPolarAlign,
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
}

/** Ekos's PAH prompts name its desktop buttons; point them at this page's. */
private fun appPahMessage(message: String): String = message
    .replace("Click Stop", "Tap Done")
    .replace("click Stop", "tap Done")
    .replace("then click refresh", "then tap Start adjusting")
    .replace("click refresh", "tap Start adjusting")

/**
 * Latest align frame, box sized to the frame's own aspect (correction crops are square, a fixed
 * 4:3 box would shrink them). Pinch to zoom, drag to pan once zoomed, double-tap to reset: Ekos
 * paints the correction triangle at frame scale, so it's a few pixels wide on a phone ([OverlayBoost] widens it). When
 * [pickable], a tap sends its position as a fraction of the *received image* (zoom and
 * letterbox-corrected) — `polar_set_crosshair` maps that back through the crop's bounding rect
 * server-side (Message::processPolarCommands).
 */
@Composable
private fun PaImage(state: AppState, pickable: Boolean, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val jpeg = state.latestAlignFrame?.jpeg
    val imageSize = remember(jpeg) {
        jpeg?.let {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, o)
            if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
        }
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var picked by remember(jpeg) { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = boxSize.width * (z - 1f) / 2f
        val maxY = boxSize.height * (z - 1f) / 2f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(imageSize?.let { (it.width.toFloat() / it.height).coerceIn(0.75f, 2f) } ?: (4f / 3f))
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceDeep)
            .onSizeChanged { boxSize = it }
            // One finger only moves the image while zoomed in, so the sheet still scrolls over it at 1×.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || zoom > 1f) {
                            val z = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                            zoom = z
                            pan = if (z == 1f) Offset.Zero else clampPan(pan + event.calculatePan(), z)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(imageSize, pickable) {
                detectTapGestures(
                    onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                    onTap = { tap ->
                        if (!pickable || imageSize == null) return@detectTapGestures
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        val local = (tap - pan - center) / zoom + center
                        val scale = minOf(boxSize.width / imageSize.width.toFloat(), boxSize.height / imageSize.height.toFloat())
                        val ox = (boxSize.width - imageSize.width * scale) / 2f
                        val oy = (boxSize.height - imageSize.height * scale) / 2f
                        val fx = (local.x - ox) / (imageSize.width * scale)
                        val fy = (local.y - oy) / (imageSize.height * scale)
                        if (fx in 0f..1f && fy in 0f..1f) {
                            picked = local
                            ctrl.polarSelectStar(fx.toDouble(), fy.toDouble())
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
        ) {
            MediaFramePreview(state.latestAlignFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised, boostOverlay = true)
            picked?.takeIf { pickable }?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(c.warn, radius = 14.dp.toPx() / zoom, center = p, style = Stroke(width = 1.5.dp.toPx() / zoom))
                }
            }
        }
    }
    if (jpeg != null) {
        Spacer(Modifier.height(4.dp))
        TextC("Pinch to zoom · double-tap to reset", style = NocturneTheme.type.MonoMicro, color = c.textFaint)
    }
}

// ── Device ───────────────────────────────────────────────────────────────

@Composable
private fun DeviceSheet(state: AppState, ctrl: SessionController) {
    val live = state.wireDevices?.firstOrNull { it.name == state.deviceKey }
    if (live != null) {
        RealDeviceSheetBody(state, ctrl, live)
        return
    }
    val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
    if (!state.ekosRunning) {
        DevicePickerBody(state, ctrl, d)
        return
    }
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val on = state.isOn(d.key)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (on) c.ok else if (d.req) c.danger else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(if (on) "connected" else "not connected", style = t.MonoMid, color = if (on) c.ok else if (d.req) c.danger else c.textMuted, modifier = Modifier.weight(1f))
            if (d.req) {
                TextC(
                    "REQUIRED",
                    style = t.MonoMini,
                    color = c.accent400,
                    modifier = Modifier
                        .background(c.accent.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        val liveDriverName = state.selectedDeviceNames[d.key] ?: d.name
        d.cfg.forEach { (label, staticValue) ->
            val value = if (label == "Driver") liveDriverName else staticValue
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.text.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 11.2.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextC(label, style = t.Caption, color = c.textMuted, modifier = Modifier.width(88.dp))
                TextC(value, style = t.MonoMid, color = c.text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.4.dp))
        }
        NocturneButton(
            text = if (on) "Disconnect" else "Connect",
            onClick = { ctrl.toggleDevice(d.key) },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        TextC(
            "Saved to profile “${state.activeProfile ?: state.profileName}” — ${state.profiles.size} profiles",
            style = t.Mono115, color = c.textMuted,
        )
        val driverName = state.selectedDeviceNames[d.key] ?: d.name
        IndiPropertyPanel(deviceKey = driverName, props = state.indiProps[driverName] ?: emptyList(), ctrl = ctrl)
    }
}

/** Connected device sheet: connect toggle and the device's live INDI property panel. */
@Composable
private fun RealDeviceSheetBody(state: AppState, ctrl: SessionController, d: LiveDevice) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (d.connected) c.ok else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(
                if (d.connected) "connected" else "not connected",
                style = t.MonoMid, color = if (d.connected) c.ok else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            TextC(d.roles.joinToString(" · ") { it.name.lowercase() }, style = t.MonoMicro, color = c.textFaint)
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = if (d.connected) "Disconnect" else "Connect",
            onClick = { ctrl.toggleDevice(d.name) },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        IndiPropertyPanel(deviceKey = d.name, props = state.indiProps[d.name] ?: emptyList(), ctrl = ctrl)
    }
}

/** Device sheet before Ekos is running (profile setup): only a selection toggle, nothing to configure yet. */
@Composable
private fun DevicePickerBody(state: AppState, ctrl: SessionController, d: Device) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val selected = state.isSelected(d.key)
    val current = state.selectedDeviceNames[d.key] ?: d.name
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (selected) c.ok else if (d.req) c.danger else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(
                if (selected) "selected for this profile" else "not selected",
                style = t.MonoMid,
                color = if (selected) c.ok else if (d.req) c.danger else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (d.req) {
                TextC(
                    "REQUIRED",
                    style = t.MonoMini,
                    color = c.accent400,
                    modifier = Modifier
                        .background(c.accent.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        val used = state.realDeviceOptions(d.key) ?: d.catalog
        val installed = state.installedDriverOptions(d.key)
        var query by remember(d.key) { mutableStateOf("") }
        val q = query.trim().lowercase()
        fun matches(vararg text: String) = q.isEmpty() || text.any { it.lowercase().contains(q) }

        if (installed != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) TextC("Search ${installed.size} installed drivers…", style = t.Body13, color = c.textFaint)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(11.2.dp))
        }

        val usedShown = used.filter { matches(it) }
        if (usedShown.isNotEmpty()) {
            TextC(if (installed != null) "USED BEFORE" else "AVAILABLE DEVICES", style = t.MicroLabel, color = c.textFaint)
            Spacer(Modifier.height(5.dp))
            DeviceOptionList(usedShown.map { it to null }, current) { ctrl.selectDeviceName(d.key, it) }
        }

        if (installed != null) {
            // everything else installed on the Pi, by manufacturer; "used before" entries aren't repeated
            val rest = installed.filter { it.label !in used && matches(it.label, it.manufacturer) }
            Spacer(Modifier.height(11.2.dp))
            TextC("ALL INSTALLED DRIVERS · ${rest.size}", style = t.MicroLabel, color = c.textFaint)
            Spacer(Modifier.height(5.dp))
            if (rest.isEmpty()) {
                TextC(if (q.isEmpty()) "Nothing else installed for this role." else "No driver matches \"$query\".", style = t.Caption, color = c.textMuted)
            }
            rest.groupBy { it.manufacturer.ifBlank { "Other" } }.forEach { (maker, drivers) ->
                TextC(maker.uppercase(), style = t.MonoMini, color = c.accent400, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                DeviceOptionList(drivers.map { it.label to it.binary.ifBlank { null } }, current) { ctrl.selectDeviceName(d.key, it) }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Connect and configure once Ekos is running for this profile.",
            style = t.Mono115, color = c.textMuted,
        )
    }
}

/** Bordered list of selectable driver labels for [DevicePickerBody]; second of each pair is a dim detail (driver binary). */
@Composable
private fun DeviceOptionList(options: List<Pair<String, String?>>, current: String, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
    ) {
        options.forEachIndexed { i, (name, detail) ->
            val sel = name == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(name) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC(name, style = t.Body13, color = if (sel) c.accent400 else c.text)
                    detail?.let { TextC(it, style = t.MonoMicro, color = c.textFaint) }
                }
                if (sel) Phosphor.Icon(Phosphor.Check, size = 14.dp, tint = c.accent400)
            }
            if (i < options.lastIndex) HDivider()
        }
    }
}

/**
 * Generic INDI control panel, one card per property vector rendered from its [IndiProperty]
 * shape: switches as segmented pickers, numbers as steppers, text as fields, lights as dots.
 */
@Composable
private fun IndiPropertyPanel(deviceKey: String, props: List<IndiProperty>, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    if (props.isEmpty()) return
    Spacer(Modifier.height(11.2.dp))
    TextC("INDI CONTROLS", style = t.MicroLabel, color = c.textFaint)
    Spacer(Modifier.height(8.dp))
    props.forEach { prop ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextC(prop.label, style = t.Body13, color = c.text, modifier = Modifier.weight(1f))
                TextC(prop.group, style = t.MonoMicro, color = c.textFaint)
            }
            Spacer(Modifier.height(8.dp))
            when (prop) {
                is IndiProperty.SwitchProp -> Row {
                    prop.options.forEachIndexed { i, label ->
                        val sel = i == prop.selected
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                                .border(1.dp, if (sel) c.accent else c.divider, RoundedCornerShape(4.dp))
                                .clickable { ctrl.setIndiSwitch(deviceKey, prop.name, i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            TextC(label, style = t.MonoSmall, color = if (sel) c.accent400 else c.textMuted)
                        }
                        if (i < prop.options.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                }
                is IndiProperty.NumberProp -> Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(
                        icon = Phosphor.CaretLeft, size = 28,
                        onClick = { ctrl.setIndiNumber(deviceKey, prop.name, prop.value - prop.step) },
                    )
                    TextC(
                        formatIndiNumber(prop.format, prop.value), style = t.Mono15, color = c.text,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconBtn(
                        icon = Phosphor.CaretRight, size = 28,
                        onClick = { ctrl.setIndiNumber(deviceKey, prop.name, prop.value + prop.step) },
                    )
                }
                // One row per element: a text vector can hold several (e.g. FILTER_NAME per slot).
                is IndiProperty.TextProp -> Column {
                    prop.elements.forEachIndexed { i, (elementName, value) ->
                        if (i > 0) Spacer(Modifier.height(6.dp))
                        IndiTextElementRow(
                            index = i, showIndex = prop.elements.size > 1, value = value,
                            onChange = { ctrl.setIndiText(deviceKey, prop.name, elementName, it) },
                        )
                    }
                }
                is IndiProperty.LightProp -> Row {
                    prop.elements.forEach { (label, ipState) ->
                        val dotColor = when (ipState) {
                            1 -> c.ok
                            2 -> c.warn
                            3 -> c.danger
                            else -> c.textFaint
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
                            Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(5.dp))
                            TextC(label, style = t.MonoMicro, color = c.textMuted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Keeps its own `text` because a driver may not echo a text vector back promptly; binding to the
 * live value would snap the field back mid-typing. Keyed by [index] so each element has its own buffer.
 */
@Composable
private fun IndiTextElementRow(index: Int, showIndex: Boolean, value: String, onChange: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember(index) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIndex) {
            TextC("${index + 1}", style = t.MonoSmall, color = c.textFaint, modifier = Modifier.width(20.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .background(c.surface, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
        )
    }
}

// ── Summary ──────────────────────────────────────────────────────────────

@Composable
private fun SummarySheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val canResume = state.lastEndedJobId != null
    val hasNext = state.jobs.any { it.id != state.lastEndedJobId }
    val job = state.endedJob
    val barColors = listOf(Color(0xFF9184D9), Color(0xFF796CBF), Color(0xFF5D5294), c.accentMuted, c.accent800)
    Column {
        // From the Room-backed frame table.
        Row(Modifier.fillMaxWidth()) {
            SumStat("KEPT", state.keepCount.toString(), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("DISCARDED", state.rejectCount.toString(), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("MED HFR", state.medHfr?.let { "%.2f".format(it) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
        ) {
            TextC("INTEGRATION BY FILTER", style = t.MicroLabel, color = c.textMuted)
            Spacer(Modifier.height(9.dp))
            if (job == null || job.blocks.isEmpty()) {
                TextC("No sequence data for this session.", style = t.MonoSmall, color = c.neutral500)
            } else {
                job.blocks.forEachIndexed { i, b ->
                    SumBar(b.filter, b.pct, barColors[i % barColors.size], b.doneSpec)
                }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        // Built from this session's notifications.
        TextC("SESSION EVENTS", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        if (state.wireNotifications.isEmpty()) {
            TextC("No alerts this session.", style = t.MonoSmall, color = c.neutral500)
        } else {
            state.wireNotifications.take(10).forEach {
                TextC("${it.time} · ${it.message}", style = t.MonoSmall, color = c.neutral500)
            }
        }
        Spacer(Modifier.height(11.2.dp))
        val context = androidx.compose.ui.platform.LocalContext.current
        NocturneButton(
            text = "Export log + FITS list",
            onClick = { com.nocturne.export.exportSessionReport(context, state) },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        if (canResume) {
            Spacer(Modifier.height(8.4.dp))
            NocturneButton(
                text = "Back to session",
                onClick = ctrl::resumeSession,
                style = com.nocturne.ui.components.BtnStyle.SOLID,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
        if (hasNext) {
            Spacer(Modifier.height(8.4.dp))
            NocturneButton(
                text = "Next job?",
                onClick = ctrl::startNextJob,
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
        Spacer(Modifier.height(8.4.dp))
        NocturneButton(
            text = "Finish — park mount, cooler off",
            onClick = ctrl::finishNight,
            style = com.nocturne.ui.components.BtnStyle.DANGER,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
}

@Composable
private fun SumBar(label: String, frac: Float, color: Color, value: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextC(label, style = t.MonoSmall, color = c.text, modifier = Modifier.width(30.dp))
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .background(c.text.copy(alpha = 0.08f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(10.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.width(9.dp))
        TextC(value, style = t.MonoSmall, color = c.text)
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val c = NocturneTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) { content() }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textMuted)
        TextC(value, style = t.Mono17, color = c.text)
    }
}

@Composable
private fun SumStat(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textMuted)
        TextC(value, style = t.Mono20, color = c.text)
        if (sub != null) {
            TextC(sub, style = t.MonoMicro, color = c.textFaint)
        }
    }
}

/** Shared field-label style for sheet forms. */
@Composable
private fun FieldLabel(text: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TextC(text, style = t.CaptionMedium, color = c.textDim)
}
