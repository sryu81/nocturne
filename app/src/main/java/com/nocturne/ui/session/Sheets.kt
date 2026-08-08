package com.nocturne.ui.session

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nocturne.session.ALERTS
import com.nocturne.session.AlertIcon
import com.nocturne.session.DEVICES
import com.nocturne.session.DRIVER_INDI_PROPS
import com.nocturne.session.FILTER_CYCLE
import com.nocturne.session.Device
import com.nocturne.session.IndiProperty
import com.nocturne.session.LiveDevice
import com.nocturne.session.PA_SECS
import com.nocturne.session.RigRebootState
import com.nocturne.session.formatIndiNumber
import com.nocturne.session.realDeviceOptions
import com.nocturne.session.PREF_DEFS
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.TrainRole
import com.nocturne.session.TrainSlot
import com.nocturne.session.coolAtSetPoint
import com.nocturne.session.indiNumber
import com.nocturne.session.doneSpec
import com.nocturne.session.endedJob
import com.nocturne.session.fRatio
import com.nocturne.session.formatHm
import com.nocturne.session.get
import com.nocturne.session.opticNote
import com.nocturne.session.ScopeDef
import com.nocturne.session.coolBarPct
import com.nocturne.session.coolPowerPct
import com.nocturne.session.isOn
import com.nocturne.session.isSelected
import com.nocturne.session.keepCount
import com.nocturne.session.medHfr
import com.nocturne.session.missing
import com.nocturne.session.paTotal
import com.nocturne.session.pct
import com.nocturne.session.ready
import com.nocturne.session.rejectCount
import com.nocturne.session.eafTemp
import com.nocturne.session.benchFocPos
import com.nocturne.session.realSlewRateProp
import com.nocturne.session.focusNextAfMin
import com.nocturne.session.guideStarSnr
import com.nocturne.session.rms
import com.nocturne.session.train
import com.nocturne.session.trainRolePool
import com.nocturne.session.wiggle
import com.nocturne.ui.components.GuideTraceChart
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.NocturneSheet
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.components.VCurve
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturnePalette
import com.nocturne.ui.theme.NocturneTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tintable big circle icon used in alert rows. */
private data class AlertStyle(val color: Color, val icon: ImageVector)

private fun alertStyle(a: com.nocturne.session.Alert): AlertStyle = when (a.iconKind) {
    AlertIcon.FLIP -> AlertStyle(NocturnePalette.Warn, Phosphor.ArrowsClockwise)
    AlertIcon.SCISSORS -> AlertStyle(NocturnePalette.Danger, Phosphor.Scissors)
    AlertIcon.CLOUD -> AlertStyle(NocturnePalette.Accent, Phosphor.Cloud)
    AlertIcon.CHECKS -> AlertStyle(NocturnePalette.Ok, Phosphor.CheckCircle)
}

@Composable
private fun paColorOf(s: SimState): Color {
    val c = NocturneTheme.colors
    return when {
        s.paTotal < 1 -> c.ok
        s.paTotal < 3 -> c.warn
        else -> c.danger
    }
}

/** Hosts whichever sheet is open. */
@Composable
fun SheetHost(state: SimState, ctrl: SessionController, landscape: Boolean) {
    val sheet = state.sheet ?: return
    val full = sheet == SheetType.PA || sheet == SheetType.SETUP
    val (title, meta) = when (sheet) {
        SheetType.GUIDE -> "Guiding" to "last 2 min"
        SheetType.FOCUS -> "Focus" to "V-curve · 9 points"
        SheetType.ALERTS -> "Alerts" to "tonight"
        SheetType.SUMMARY -> "Session summary" to "21:48 → 04:12"
        // "three steps" is the fixture wizard's own framing — misleading verbatim in real mode,
        // where PaSheet shows raw new_polar_state stage/message passthrough instead (see its doc).
        SheetType.PA -> "Polar alignment" to (if (state.isRealRig) "live status" else "three steps")
        SheetType.PREFS -> "Alert rules" to "push + on-screen"
        SheetType.AUTOFOCUS_RULES -> "Autofocus rules" to "when to refocus"
        SheetType.SETUP -> (if (state.setupEditingName != null) "Edit rig profile" else "New rig profile") to "name + device connections"
        SheetType.OPTICAL_TRAIN -> "Optical train" to "primary + secondary roles"
        SheetType.MODULE_ASSIGNMENTS -> "Module assignments" to "which train each Ekos module uses"
        SheetType.SCOPES -> "Scopes" to "add, edit, remove"
        SheetType.MAINTENANCE -> "Rig maintenance" to "reboot the Pi if Ekos hangs"
        SheetType.MOUNT_SETTINGS -> "Mount settings" to "flip, limits, auto-park"
        SheetType.CAMERA_SETTINGS -> "Camera settings" to "save path, guide guard, dither"
        SheetType.ALIGN_SETTINGS -> "Align settings" to "exposure, gain, filter, accuracy"
        SheetType.GUIDE_SETTINGS -> "Guide settings" to "accuracy threshold, dither"
        SheetType.FOCUS_SETTINGS -> "Focus settings" to "exposure, gain, filter"
        SheetType.DEVICE -> {
            // Real connection: state.deviceKey is the live device's own name (see
            // RealDeviceList's onClick), not a fixture DEVICES[].key — must be looked up
            // against state.wireDevices, same as DeviceSheet's body does, or every real
            // device sheet falls through to DEVICES[0] ("mount") and shows whatever
            // profile name is parked there for every device (confirmed live: always
            // showed the mount's fixture display name, e.g. "EQ6-R Pro", regardless of
            // which real device — LX200/ToupTek/ZWO — was actually tapped).
            val live = state.wireDevices?.firstOrNull { it.name == state.deviceKey }
            if (live != null) {
                live.name to (if (live.connected) "connected" else "not connected")
            } else {
                val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
                (state.selectedDeviceNames[d.key] ?: d.name) to (if (d.req) "required for a session" else "optional")
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
                SheetType.GUIDE -> GuideSheet(state)
                SheetType.FOCUS -> FocusSheet(state, ctrl)
                SheetType.ALERTS -> AlertsSheet(ctrl)
                SheetType.SUMMARY -> SummarySheet(state, ctrl)
                SheetType.PA -> PaSheet(state, ctrl, landscape)
                SheetType.PREFS -> PrefsSheet(state, ctrl)
                SheetType.AUTOFOCUS_RULES -> AutofocusRulesSheet(state, ctrl)
                SheetType.SETUP -> SetupBody(state, ctrl)
                SheetType.OPTICAL_TRAIN -> OpticalTrainSheet(state, ctrl)
                SheetType.MODULE_ASSIGNMENTS -> ModuleAssignmentsSheet(state, ctrl)
                SheetType.SCOPES -> ScopesSheet(state, ctrl)
                SheetType.MAINTENANCE -> MaintenanceSheet(state, ctrl)
                SheetType.MOUNT_SETTINGS -> MountSettingsSheet(state, ctrl)
                SheetType.CAMERA_SETTINGS -> CameraSettingsSheet(state, ctrl)
                SheetType.ALIGN_SETTINGS -> AlignSettingsSheet(state, ctrl)
                SheetType.GUIDE_SETTINGS -> GuideSettingsSheet(state, ctrl)
                SheetType.FOCUS_SETTINGS -> FocusSettingsSheet(state, ctrl)
                SheetType.DEVICE -> DeviceSheet(state, ctrl)
            }
        },
        footer = if (sheet == SheetType.SETUP) {
            { SetupFooter(ctrl) }
        } else null,
    )
}

// ── Guide ────────────────────────────────────────────────────────────────

@Composable
private fun GuideSheet(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val raTrace = wiggle(state.t, 3, 90, 22.0)
    val decTrace = wiggle(state.t, 41, 90, 14.0)
    // wiggle()'s amplitude is chart-pixel space (GuideTraceChart's 108-unit viewBox,
    // center-to-edge = 54 units), calibrated to the chart's own "±2″ scale" label —
    // convert to arcsec before treating it as a real stat, not a raw pixel deviation.
    val peak = (raTrace + decTrace).maxOf { kotlin.math.abs(it) } * (2.0 / 54.0)
    Column {
        Panel {
            GuideTraceChart(
                ra = raTrace,
                dec = decTrace,
                modifier = Modifier.fillMaxWidth().height(108.dp),
            )
            Row(Modifier.padding(top = 6.dp)) {
                TextC("— RA 0.41″", style = t.MonoMicro, color = c.accent400)
                Spacer(Modifier.width(14.dp))
                TextC("— DEC 0.26″", style = t.MonoMicro, color = c.info)
                Spacer(Modifier.width(14.dp))
                TextC("±2″ scale", style = t.MonoMicro, color = c.textMuted)
            }
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("TOTAL RMS", String.format("%.2f", state.rms) + "″", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("PEAK", String.format("%.2f", peak) + "″", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("STAR SNR", String.format("%.0f", state.guideStarSnr), Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "2.1 px/″ · 2s exposure · aggr RA 70 / DEC 60 · backlash comp on",
            style = t.MonoSmall, color = c.neutral500,
        )
    }
}

// ── Focus ────────────────────────────────────────────────────────────────

@Composable
private fun FocusSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val tempDelta = state.eafTemp - state.focusTempAtLastAf
    Column {
        Panel {
            VCurve(modifier = Modifier.fillMaxWidth().height(130.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextC(
                    "${state.focusLastBestPos - 240}", style = t.MonoMicro, color = c.textMuted,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                TextC(
                    "best ${state.focusLastBestPos} · HFR ${"%.2f".format(state.focusLastHfr)}",
                    style = t.MonoMicro, color = c.text, modifier = Modifier.align(Alignment.Center),
                )
                TextC(
                    "${state.focusLastBestPos + 240}", style = t.MonoMicro, color = c.textMuted,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("POSITION", "${state.focPos}", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("TEMP Δ", "${if (tempDelta >= 0) "+" else ""}${"%.1f".format(tempDelta)}°", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("NEXT AF", "${state.focusNextAfMin} m", Modifier.weight(1f))
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
private fun AlertsSheet(ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        ALERTS.forEach { a ->
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
                    TextC(a.text, style = t.Body13, color = c.text)
                    TextC(a.time, style = t.MonoMicro, color = c.textMuted)
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

@Composable
private fun PrefsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        PREF_DEFS.forEach { p ->
            SwitchRow(
                label = p.label,
                sub = p.desc,
                checked = state.prefs[p.key] == true,
                onToggle = { ctrl.togglePref(p.key) },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 11.2.dp),
            )
            Spacer(Modifier.height(8.4.dp))
        }
        SwitchRow(
            label = "Quiet hours",
            sub = "mute non-critical push while imaging",
            checked = state.quietHoursEnabled,
            onToggle = ctrl::toggleQuietHours,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        TextC(
            "Critical alerts (unsafe weather, disconnect, mount fault) always sound, even in quiet hours.",
            style = t.MonoMicro, color = c.textMuted, modifier = Modifier.padding(end = 4.dp),
        )
    }
}

// ── Autofocus rules ─────────────────────────────────────────────────────────

/**
 * One rule for the whole running sequence — matches real Ekos, which enforces
 * refocus/HFR/temperature triggers per-session (`capture_get_all_settings`),
 * not per-job. Every block shows this same text; edit it here.
 */
@Composable
private fun AutofocusRulesSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        FieldLabel("Refocus every")
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = state.afRefocusMin.toString(),
                onValueChange = { text -> ctrl.setAutofocusRefocusMin(text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0) },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            TextC("min", style = t.MonoSmall, color = c.neutral500)
        }
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Or on temperature drift")
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = "%.1f".format(state.afTempDeltaC),
                onValueChange = { text ->
                    text.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let(ctrl::setAutofocusTempDelta)
                },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            TextC("°C", style = t.MonoSmall, color = c.neutral500)
        }
        Spacer(Modifier.height(11.2.dp))
        SwitchRow(
            label = "Refocus on filter change",
            sub = "run autofocus whenever the active filter changes",
            checked = state.afOnFilterChange,
            onToggle = ctrl::toggleAutofocusOnFilterChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Applies to the whole running sequence, not per block — matches how Ekos enforces autofocus triggers.",
            style = t.MonoMicro, color = c.textMuted,
        )
    }
}

// ── Setup ────────────────────────────────────────────────────────────────

private val PA_STEPS = listOf("Point", "Rotate + capture", "Adjust knobs")
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
 * Rig profile setup — name + device role picker, one screen, no steps.
 * Optical train (scope/guide scope) is a separate standalone entry reachable
 * from the Gear tab, same pattern as Bench check/Polar align.
 */
@Composable
private fun SetupBody(state: SimState, ctrl: SessionController) {
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
                .height(42.dp)
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
                TextC(state.selectedDeviceNames[d.key] ?: d.name, style = t.Body13, color = c.text, modifier = Modifier.weight(1f))
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

/** Scope/guide-scope entry — name + focal length + aperture, matching real Ekos's Scopes catalog fields (`scope_add`). */
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
            .height(42.dp)
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
 * Small labeled digits-only mm field, used for scope focal length/aperture.
 *
 * Local `text` state, not a `value`-derived one — same clear-and-retype fix as [DegreeField]/
 * [IntField]: rendering `mm.toString()` directly and only calling [onChange] on a successful
 * parse meant clearing the field (empty string isn't a valid int) never called [onChange], so it
 * snapped back to the old value on the next recomposition. Local text tracks whatever's actually
 * typed (including empty, mid-edit); [onChange] only fires once a full number parses.
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
                .height(38.dp)
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
 * Standalone entry point (from Gear tab, prior to Optical Train) — real
 * Ekos's own Scopes catalog dialog (`get_scopes`/`scope_add`/`scope_update`/
 * `scope_delete`, `EkosRemote-Command-Reference.md` §4): a flat add/edit/
 * remove list of telescopes/lenses, entirely separate from both the rig
 * Profile and the Optical Train's per-slot role pickers — a train's
 * Scope/Lens role just picks one of these by name (`OpticalTrainSheet`'s
 * `TrainForm`, unchanged, already a generic picker over whatever
 * `trainRolePool(SCOPE)` returns).
 */
@Composable
private fun ScopesSheet(state: SimState, ctrl: SessionController) {
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

/** Inline add/edit form for one [ScopeDef] — reuses [ScopeInputFields] for the name/focal/aperture fields. */
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
private fun SetupFooter(ctrl: SessionController) {
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
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
    }
}

// ── Mount settings (M3.3, curated subset) ───────────────────────────────────

/**
 * Curated subset of real Ekos's Mount tab (10 of 17 real fields — see
 * docs/M3.3-plan.md) — meridian flip, altitude/HA slew limits, auto-park.
 * Real-rig only: [SimState.wireMountSettings] is null under
 * [SimulatedController] (there's no fixture equivalent — matches
 * [ModuleAssignmentsSheet]'s own real-only gating) and briefly null on a
 * real rig too, until the first `mount_get_all_settings` reply lands.
 */
@Composable
private fun MountSettingsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC("Simulator has no real Mount module settings to show — connect to a rig first.", style = t.Body13, color = c.textMuted)
        return
    }
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
                Modifier.fillMaxWidth().height(42.dp)
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
    }
}

/**
 * Numeric field row shared by [MountSettingsSheet]'s degree/hour-angle inputs.
 *
 * Local `text` state, not a `value`-derived one: the old version rendered `"%.1f".format(value)`
 * directly and only called [onChange] when the typed text fully parsed as a `Double` — clearing
 * the field to type a fresh number never called [onChange] (an empty string isn't a valid
 * double), so the field snapped back to the old formatted value on the very next recomposition,
 * making it impossible to clear and retype. Local text tracks whatever's actually been typed
 * (including empty, `"-"`, a trailing `"."`, mid-edit) independently of [value]; [onChange] only
 * fires once a full number parses, same external contract as before.
 */
@Composable
private fun DegreeField(value: Double, unit: String, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("%.1f".format(value)) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
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

// ── Camera settings (M3.3 phase 5, curated subset) ──────────────────────────

/**
 * Curated subset of real Ekos's Camera tab (7 of 59 real fields — see
 * docs/M3.3-plan.md) — save path, guide-deviation abort guard, start-of-job
 * guide-drift guard, per-job dither. Real-rig only: [SimState.wireCaptureSettings]
 * is null under [SimulatedController] (no fixture equivalent, same real-only
 * gating as [MountSettingsSheet]) and briefly null on a real rig too, until
 * the first `capture_get_all_settings` reply lands.
 */
@Composable
private fun CameraSettingsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC("Simulator has no real Camera module settings to show — connect to a rig first.", style = t.Body13, color = c.textMuted)
        return
    }
    val cam = state.wireCaptureSettings
    if (cam == null) {
        TextC("Fetching camera settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Save path")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp)
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
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

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

/** Integer field row — same shape as [DegreeField] (same local-text-state fix, same reasoning) but for whole-number settings like [CameraSettingsSheet]'s dither frequency. */
@Composable
private fun IntField(value: Int, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("$value") }
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
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

// ── Align settings (M3.3 phase 3, curated subset) ───────────────────────────

/** Real Ekos's alignBinning combo options — confirmed live (`"1x1"` was the observed value, not a number). */
private val ALIGN_BINNING_OPTIONS = listOf("1x1", "2x2", "3x3", "4x4")

/**
 * Curated subset of real Ekos's Align tab (5 of 98 real fields — see
 * docs/M3.3-plan.md) — exposure, gain, filter, binning, solver accuracy
 * threshold. Real-rig only: [SimState.wireAlignSettings] is null under
 * [SimulatedController] (no fixture equivalent, same real-only gating as
 * [MountSettingsSheet]/[CameraSettingsSheet]) and briefly null on a real
 * rig too, until the first `align_get_all_settings` reply lands.
 */
@Composable
private fun AlignSettingsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC("Simulator has no real Align module settings to show — connect to a rig first.", style = t.Body13, color = c.textMuted)
        return
    }
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
        FieldLabel("Gain")
        Spacer(Modifier.height(5.dp))
        DegreeField(a.alignGain, "", ctrl::setAlignGain)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // Same tap-to-cycle idiom as the Sequence tab's own filter chip (cycleBlockFilter) —
        // reuses the same real filter-wheel position list, just via a direct setAlignFilter
        // call here instead of a dedicated cycle method (Align has only the one filter field,
        // no block/job indirection to route through).
        FieldLabel("Filter")
        Spacer(Modifier.height(5.dp))
        CycleChip(a.alignFilter) { ctrl.setAlignFilter(FILTER_CYCLE[(FILTER_CYCLE.indexOf(a.alignFilter) + 1).mod(FILTER_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Binning")
        Spacer(Modifier.height(5.dp))
        CycleChip(a.alignBinning) { ctrl.setAlignBinning(ALIGN_BINNING_OPTIONS[(ALIGN_BINNING_OPTIONS.indexOf(a.alignBinning) + 1).mod(ALIGN_BINNING_OPTIONS.size)]) }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        FieldLabel("Solver accuracy threshold")
        Spacer(Modifier.height(5.dp))
        DegreeField(a.alignAccuracyThreshold, "\"", ctrl::setAlignAccuracyThreshold)
    }
}

/** Tap-to-cycle chip shared by [AlignSettingsSheet]'s filter/binning fields. */
@Composable
private fun CycleChip(value: String, onTap: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .height(42.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        TextC(value, style = t.Body13, color = c.text)
    }
}

// ── Guide settings (M3.3 phase 4, curated subset) ───────────────────────────

/**
 * Curated subset of real Ekos's Guide tab (8 of 84 real fields — see
 * docs/M3.3-plan.md and [WireGuideSettings]'s own doc for the live-probe history) —
 * exposure/gain/binning (already live via Bench "Snap guide", shown read-only here for
 * context) plus solver accuracy threshold and dither. Real-rig only: [SimState.wireGuideSettings]
 * is null under [SimulatedController] and briefly null on a real rig too, until the first
 * `guide_get_all_settings` reply lands — same gating shape as [AlignSettingsSheet].
 */
@Composable
private fun GuideSettingsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC("Simulator has no real Guide module settings to show — connect to a rig first.", style = t.Body13, color = c.textMuted)
        return
    }
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
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Reuse calibration",
            sub = "skip re-calibrating the guider at the start of each session",
            checked = g.kcfg_ReuseGuideCalibration,
            onToggle = { ctrl.setGuideReuseCalibration(!g.kcfg_ReuseGuideCalibration) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
    }
}

// ── Focus settings (M3.3 phase 6, curated subset) ───────────────────────────

/**
 * Curated subset of real Ekos's Focus tab (6 of 84 real fields — see
 * docs/M3.3-plan.md and [WireFocusSettings]'s own doc for the live-probe history) —
 * `absTicksSpin` (used elsewhere to seed [SimState.focPos], not shown here) plus
 * exposure/gain/filter/backlash/algorithm. Real-rig only: [SimState.wireFocusSettings]
 * is null under [SimulatedController] and briefly null on a real rig too, until the first
 * `focus_get_all_settings` reply lands — same gating shape as [GuideSettingsSheet].
 */
@Composable
private fun FocusSettingsSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC("Simulator has no real Focus module settings to show — connect to a rig first.", style = t.Body13, color = c.textMuted)
        return
    }
    val f = state.wireFocusSettings
    if (f == null) {
        TextC("Fetching focus settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Exposure")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusExposure, "s", ctrl::setFocusExposure)
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Gain")
        Spacer(Modifier.height(5.dp))
        DegreeField(f.focusGain, "", ctrl::setFocusGain)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // Same tap-to-cycle idiom as AlignSettingsSheet's filter field — real filter-wheel
        // position list, shared app-wide.
        FieldLabel("Filter")
        Spacer(Modifier.height(5.dp))
        CycleChip(f.focusFilter) { ctrl.setFocusFilter(FILTER_CYCLE[(FILTER_CYCLE.indexOf(f.focusFilter) + 1).mod(FILTER_CYCLE.size)]) }
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Backlash")
        Spacer(Modifier.height(5.dp))
        IntField(f.focusBacklash, ctrl::setFocusBacklash)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // Free-text, not a cycle chip: unlike alignBinning/guideBinning (a small fixed set,
        // confirmed live), real Ekos's algorithm list isn't enumerated anywhere probed so far
        // (confirmed live value: "Linear 1 Pass") — inventing a guessed option list here would
        // be exactly the kind of wire-shape guess this project avoids elsewhere. Same free-text
        // shape as CameraSettingsSheet's "Save path" field (direct passthrough, no parse/filter
        // step, so no clear-and-retype bug risk).
        FieldLabel("Autofocus algorithm")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(42.dp)
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
    }
}

// ── Rig maintenance ─────────────────────────────────────────────────────────

/**
 * Rig-level recovery, not an Ekos concept — the EkosRemote wire has no
 * OS-level reboot command (and couldn't rely on one anyway: a hung/crashed
 * Ekos process is exactly the case a reboot needs to recover from). Talks
 * instead to a small companion daemon on the Pi over its own HTTP+token
 * channel (`pi-tools/reboot-daemon/`, [RigRebootClient]). Only meaningful
 * under a real rig — [SimState.isRealRig] gates everything below the
 * top warning.
 */
@Composable
private fun MaintenanceSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.isRealRig) {
        TextC(
            "Simulator has no real Pi to reboot — connect to a rig first.",
            style = t.Body13, color = c.textMuted,
        )
        return
    }

    var portText by remember { mutableStateOf(state.rigRebootPort.toString()) }
    var token by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }

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
                "Reboots the Pi itself, not just Ekos — cuts power to any in-progress " +
                    "slew, capture, or guiding. The app reconnects on its own once the Pi's back.",
                style = t.Caption, color = c.warn,
            )
        }
        Spacer(Modifier.height(20.dp))

        TextC("REBOOT DAEMON PORT", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
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
        Spacer(Modifier.height(16.dp))

        TextC("TOKEN", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        TextC(
            "One-time token printed by pi-tools/reboot-daemon/install.sh on the Pi.",
            style = t.MonoMicro, color = c.textFaint,
        )
        Spacer(Modifier.height(16.dp))

        NocturneButton(
            text = if (state.rigRebootTokenSet) "Update config" else "Save config",
            onClick = {
                val port = portText.toIntOrNull() ?: 9001
                if (token.isNotBlank()) ctrl.setRigRebootConfig(port, token)
            },
            enabled = token.isNotBlank(),
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        if (state.rigRebootAvailable) {
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
        } else {
            TextC("Save a token above to enable rig reboot.", style = t.Caption, color = c.textFaint)
        }
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
}

/**
 * Standalone entry point (from Gear tab) — the real Optical Trains dialog's
 * per-train role editor: Primary/Secondary (fixed, no add/remove — matches
 * the design brief, not the desktop dialog's +/− train list), each with the
 * same 10 roles as `train_get_all` (message.cpp:236,
 * `OpticalTrainManager::getOpticalTrains()`): Mount/Camera/Rotator/Guide via/
 * Dust cap/Scope/Filter wheel/Focuser/Reducer/Light box. Every dropdown's
 * pool is whatever's currently selected in the rig profile's device/scope
 * categories — Reducer is the one field that isn't a device pick, just a
 * per-train multiplier. Scope/Lens itself is a plain picker over the Scopes
 * catalog (`ScopesSheet`, reachable from its own Gear-tab card) — real Ekos
 * keeps that catalog in its own dialog too, entirely separate from Optical
 * Trains (M3.1). *Which Ekos module uses which train* is a separate concern
 * — see `ModuleAssignmentsSheet` (split out, user feedback: the two were
 * cluttering one long scroll and reading as unrelated to each other).
 */
@Composable
private fun OpticalTrainSheet(state: SimState, ctrl: SessionController) {
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
 * Standalone entry point (from Gear tab, next to Optical Train) — *which*
 * Ekos module (real tab: Camera/Focus/Mount/Guide/Align/Dark Library) uses
 * *which* named train, the real per-active-profile `ProfileSettings`
 * mechanism (`train_set`/`train_get_profiles`, confirmed against
 * `profilesettings.cpp`/`opticaltrainmanager.cpp`) — only meaningful once
 * real trains exist, no fixture equivalent (`SimulatedController` never sets
 * `wireTrains`), so [com.nocturne.ui.gear.GearScreen] simply omits this
 * card's entry point rather than showing a decorative stand-in.
 */
@Composable
private fun ModuleAssignmentsSheet(state: SimState, ctrl: SessionController) {
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
 * Real `train_set` module keys + display labels — the 6 Ekos modules that
 * each independently pick a train (confirmed against real KStars source,
 * `focus.cpp`/`mount.cpp`/`align_settings.cpp`/`guide.cpp`/`camera_config.cpp`/
 * `darklibrary.cpp`, each with its own real, working Optical Train combo box
 * — no module here is a decorative stand-in). Label is "Camera", not
 * "Capture" — the wire's own module string stays `"capture"` (that's what
 * `train_set` actually accepts, `EkosRemote-Command-Reference.md`
 * §"train_set"), but the real Ekos tab a user sees this control on is called
 * Camera. Setup/Scheduler/Analyze/Observatory have no per-module train of
 * their own (Setup predates trains existing at all; Scheduler's train picker
 * is per-*job*, not a persisted profile setting; Analyze/Observatory have no
 * Optical Train concept) — deliberately absent from this list, not missed.
 *
 * **Dark Library** (`darklibrary.cpp`): manages dark-frame/defect-map
 * calibration — captures or loads a matching master dark for whichever
 * camera its assigned train points at. Genuinely independent of Capture's
 * train (e.g. building a dark library for the guide camera while Capture
 * images through the main one).
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
private fun TrainForm(state: SimState, ctrl: SessionController, slot: TrainSlot) {
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

/**
 * One role's picker — horizontally-scrolling chip row, since a pool can be
 * 1-3 wide. Label/options sized up from the original 9.5px/11px + faint/muted
 * (user feedback: too small and dim to read comfortably) to 11px/13px +
 * dim/text — [c.accent400] for the selected chip is unchanged, already legible.
 */
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
            .height(42.dp)
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


@Composable
private fun StepPills(labels: List<String>, current: Int) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            val active = current == i
            val done = current > i
            Row(
                Modifier
                    .weight(1f)
                    .background(c.text.copy(alpha = 0.04f), RoundedCornerShape(4.dp))
                    .padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(
                            if (active) c.accent else if (done) c.ok.copy(alpha = 0.2f) else c.text.copy(alpha = 0.07f),
                            RoundedCornerShape(50),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    TextC(
                        "${i + 1}",
                        style = t.MonoMicro,
                        color = if (active) c.surfaceDeep else if (done) c.ok else c.textMuted,
                    )
                }
                Spacer(Modifier.width(6.dp))
                TextC(label, style = t.Caption10, color = c.neutral400)
            }
            if (i < labels.lastIndex) Spacer(Modifier.width(6.dp))
        }
    }
}

// ── Polar align ──────────────────────────────────────────────────────────

@Composable
private fun PaSheet(state: SimState, ctrl: SessionController, landscape: Boolean) {
    if (state.isRealRig) {
        PaRealSheet(state, ctrl)
        return
    }
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        StepPills(labels = PA_STEPS, current = state.paStep)
        Spacer(Modifier.height(14.dp))
        when (state.paStep) {
            0 -> PaStep0(ctrl)
            1 -> PaStep1(ctrl)
            else -> PaStep2(state, ctrl, landscape)
        }
    }
}

/**
 * Real-mode Polar Align — raw `new_polar_state` passthrough, not a stage-driven wizard. Real
 * pushes arrive as independent partial shapes ("scattered across several functions" per the
 * protocol reference — `{"stage"}` alone, `{"message"}` alone, `{"vector":...}`, `{"enabled"}`
 * alone), confirmed live via a direct probe (`polar_start` → `{"stage":"First Capture"}` then
 * `{"message":"...capturing the first image..."}` as two separate frames, `{"stage":"First
 * Solve"}`/`{"message":"Solving the first image..."}` next, `polar_stop` → `{"stage":"Idle"}` +
 * reset instructional message). No documented stage count/order/vocabulary for the full cycle —
 * mapping these onto fixed step-pills (like the fixture wizard above) would be exactly the kind
 * of wire-shape guess this project's established norm forbids (see `WireAlignSettings`'s
 * `alignBinning` history). Shows the raw text instead; a richer stage-driven UI is a reasonable
 * follow-up once more real PAH runs establish a stable vocabulary.
 *
 * [SimState.polarRunning] is a client-side optimistic flag driving the Start/Stop button only —
 * same shape as [FocuserCard][com.nocturne.ui.controls]'s autofocus row — never derived from the
 * wire text.
 */
@Composable
private fun PaRealSheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(
            state.wirePolarStage ?: if (state.polarRunning) "running…" else "not started",
            style = t.Mono26, color = if (state.polarRunning) c.warn else c.text,
        )
        state.wirePolarEnabled?.let {
            Spacer(Modifier.height(4.dp))
            TextC(if (it) "PAH active" else "inactive", style = t.MonoSmall, color = c.textMuted)
        }
        Spacer(Modifier.height(9.dp))
        TextC(
            state.wirePolarMessage ?: "Put the mount either in the home position pointed toward the celestial pole, or pointed anywhere near the meridian, then tap Start.",
            style = t.Body13, color = c.textMuted,
        )
        Spacer(Modifier.height(20.dp))
        TextC(
            "Real PAH drives its own mount rotation sequence once started — watch the mount. Stop cancels immediately, mid-motion if it's already rotating.",
            style = t.MonoMicro, color = c.warn,
        )
        Spacer(Modifier.height(14.dp))
        com.nocturne.ui.components.NocturneButton(
            text = if (state.polarRunning) "Stop" else "Start",
            onClick = { if (state.polarRunning) ctrl.stopPolarAlign() else ctrl.startPolarAlign() },
            style = if (state.polarRunning) com.nocturne.ui.components.BtnStyle.SOLID else com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
}

@Composable
private fun PaStep0(ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(c.surfaceDeep, RoundedCornerShape(4.dp)),
        ) {
            HatchBg(Modifier.fillMaxSize(), color = c.surfaceRaised)
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "No need to see Polaris. Point anywhere near the meridian, 30–60° altitude, and let the routine plate-solve three frames as the mount rotates.",
            style = t.Mono115, color = c.neutral500,
        )
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Solve here & start",
            onClick = ctrl::paNext,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        )
    }
}

@Composable
private fun PaStep1(ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Row(Modifier.fillMaxWidth()) {
            PaFrame("solved 0°", done = true, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            PaFrame("solved 30°", done = true, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            PaFrame("60°…", done = false, Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Mount is rotating 60° in RA. Keep hands off the tripod — two frames solved, one to go.",
            style = t.Mono115, color = c.neutral500,
        )
        Spacer(Modifier.height(11.2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(c.text.copy(alpha = 0.1f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.66f)
                    .height(4.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Capture third frame",
            onClick = ctrl::paNext,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        )
    }
}

@Composable
private fun PaFrame(label: String, done: Boolean, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        modifier
            .aspectRatio(1f)
            .background(
                if (done) c.accent.copy(alpha = 0.16f)
                else c.surfaceDeep,
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!done) HatchBg(Modifier.fillMaxSize(), color = c.surfaceRaised)
        TextC(label, style = t.MonoMicro, color = if (done) c.accent400 else c.textMuted)
    }
}

@Composable
private fun PaStep2(state: SimState, ctrl: SessionController, landscape: Boolean) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val left: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaDial(state)
            Spacer(Modifier.width(14.dp))
            Column {
                TextC(String.format("%.1f", state.paTotal) + "′", style = t.Mono30, color = paColorOf(state))
                TextC("TOTAL ERROR · ${if (state.paTotal < 1) "good — under 1′" else "keep adjusting"}", style = t.Caption10, color = c.textMuted)
                Spacer(Modifier.height(4.dp))
                TextC("ALT ${String.format("%.1f", abs(state.paAlt))}′ — ${if (state.paAlt >= 0) "lower altitude knob" else "raise altitude knob"}", style = t.Mono115, color = c.neutral400)
                TextC("AZ ${String.format("%.1f", abs(state.paAz))}′ — ${if (state.paAz >= 0) "turn azimuth east" else "turn azimuth west"}", style = t.Mono115, color = c.neutral400)
            }
        }
    }
    val right: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Phosphor.Icon(Phosphor.ArrowsClockwise, size = 15.dp, tint = c.accent)
                Spacer(Modifier.width(9.dp))
                TextC("RE-SOLVE EVERY", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
                TextC(
                    if (state.t % PA_SECS[state.paRate] == 0) "solving…"
                    else "next in ${PA_SECS[state.paRate] - (state.t % PA_SECS[state.paRate])} s",
                    style = t.Mono115, color = c.accent400,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
            ) {
                listOf("1 s", "2 s", "5 s", "10 s").forEachIndexed { i, label ->
                    val sel = state.paRate == i
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { ctrl.setPaRate(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TextC(label, style = t.MonoSmall, color = if (sel) c.accent400 else c.neutral500)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextC(
                if (state.paRate <= 1)
                    "fast — short exposures, noisier solve; good for coarse knob turns"
                else "slow — longer exposures, steadier reading; use for the last arcminute",
                style = t.MonoMicro, color = c.textMuted,
            )
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Turn the mount's own alt and az knobs — the app never moves them. Motorised wedges appear here as a Slew control instead.",
            style = t.MonoMicro, color = c.textMuted,
        )
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Accept & finish",
            onClick = ctrl::closeSheet,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(46.dp),
        )
    }
    if (landscape) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { left() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { right() }
        }
    } else {
        Column {
            left()
            Spacer(Modifier.height(11.2.dp))
            right()
        }
    }
}

@Composable
private fun PaDial(state: SimState) {
    val c = NocturneTheme.colors
    val dotColor = paColorOf(state)
    Canvas(Modifier.size(150.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.width / 2f
        drawCircle(c.bg, radius = r)
        drawCircle(
            c.text.copy(alpha = 0.1f), radius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
        )
        drawCircle(
            c.text.copy(alpha = 0.08f), radius = r - 26.dp.toPx(),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
        )
        drawCircle(
            c.ok.copy(alpha = 0.35f), radius = r - 52.dp.toPx(),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
        )
        drawLine(c.text.copy(alpha = 0.07f), Offset(cx, 8.dp.toPx()), Offset(cx, size.height - 8.dp.toPx()), 1f)
        drawLine(c.text.copy(alpha = 0.07f), Offset(8.dp.toPx(), cy), Offset(size.width - 8.dp.toPx(), cy), 1f)
        val dotX = cx + state.paAz.toFloat() * 0.05f * r
        val dotY = cy - state.paAlt.toFloat() * 0.05f * r
        drawCircle(dotColor, radius = 5.5.dp.toPx(), center = Offset(dotX, dotY))
    }
}

// ── Device ───────────────────────────────────────────────────────────────

@Composable
private fun DeviceSheet(state: SimState, ctrl: SessionController) {
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
        IndiPropertyPanel(deviceKey = driverName, props = state.indiProps[driverName] ?: DRIVER_INDI_PROPS[driverName] ?: emptyList(), ctrl = ctrl)
    }
}

/**
 * Real-connection device sheet (M3) — `state.wireDevices` present means a
 * real EkosRemote link, so this shows the actual device's connect toggle +
 * live INDI property panel instead of the fixture [Device] catalog's
 * static `cfg` rows.
 */
@Composable
private fun RealDeviceSheetBody(state: SimState, ctrl: SessionController, d: LiveDevice) {
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

/**
 * Device sheet body shown before Ekos is running (rig setup / profile edit) —
 * nothing is actually connected yet, so this is just a selection toggle, not
 * live connect/configure controls.
 */
@Composable
private fun DevicePickerBody(state: SimState, ctrl: SessionController, d: Device) {
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
        TextC("AVAILABLE DEVICES", style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(5.dp))
        val options = state.realDeviceOptions(d.key) ?: d.catalog
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
        ) {
            options.forEachIndexed { i, name ->
                val sel = name == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { ctrl.selectDeviceName(d.key, name) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextC(name, style = t.Body13, color = if (sel) c.accent400 else c.text, modifier = Modifier.weight(1f))
                    if (sel) Phosphor.Icon(Phosphor.Check, size = 14.dp, tint = c.accent400)
                }
                if (i < options.lastIndex) HDivider()
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Connect and configure once Ekos is running for this profile.",
            style = t.Mono115, color = c.textMuted,
        )
    }
}

/**
 * Generic INDI control panel — one card per property vector, rendered purely
 * from its [IndiProperty] shape so any device (real or simulated) works the
 * same way: switches as segmented pickers, numbers as steppers, text as an
 * editable field, lights as read-only status dots.
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
                                .height(34.dp)
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
                is IndiProperty.TextProp -> BasicTextField(
                    value = prop.value,
                    onValueChange = { ctrl.setIndiText(deviceKey, prop.name, it) },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(c.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp),
                )
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

// ── Summary ──────────────────────────────────────────────────────────────

@Composable
private fun SummarySheet(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val canResume = state.lastEndedJobId != null
    val hasNext = state.jobs.any { it.id != state.lastEndedJobId }
    val job = state.endedJob
    // Frames carry no per-sub exposure/filter of their own (fixture list, §8) — approximate
    // using the ended job's first block's exposure, same assumption the export report makes.
    val exposureSec = job?.blocks?.firstOrNull()?.exposureSec ?: 0
    val barColors = listOf(Color(0xFF9184D9), Color(0xFF796CBF), Color(0xFF5D5294), c.accentMuted, c.accent800)
    Column {
        Row(Modifier.fillMaxWidth()) {
            SumStat("KEPT", formatHm(state.keepCount * exposureSec), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("DISCARDED", formatHm(state.rejectCount * exposureSec), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("MED HFR", "%.2f".format(state.medHfr), Modifier.weight(1f))
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
        TextC(
            "Lost 20m — cloud 01:04–01:18, one failed plate solve.\nBattery 12.1 V at teardown · dew never reached ambient.",
            style = t.MonoSmall, color = c.neutral500,
        )
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
private fun SumStat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textMuted)
        TextC(value, style = t.Mono20, color = c.text)
    }
}

/** Shared field-label style (Optical Train's Reducer, Scope editor's Name, Setup's Profile name, ...) — bumped from 10px/textMuted (user feedback: too small/dim) to 11px medium/textDim. */
@Composable
private fun FieldLabel(text: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TextC(text, style = t.CaptionMedium, color = c.textDim)
}
