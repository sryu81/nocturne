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
import com.nocturne.session.Device
import com.nocturne.session.IndiProperty
import com.nocturne.session.PA_SECS
import com.nocturne.session.PREF_DEFS
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.TrainRole
import com.nocturne.session.TrainSlot
import com.nocturne.session.coolAtSetPoint
import com.nocturne.session.doneSpec
import com.nocturne.session.endedJob
import com.nocturne.session.fRatio
import com.nocturne.session.formatHm
import com.nocturne.session.get
import com.nocturne.session.guideOpticNote
import com.nocturne.session.opticNote
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
    val full = sheet == SheetType.BENCH || sheet == SheetType.PA || sheet == SheetType.SETUP
    val (title, meta) = when (sheet) {
        SheetType.GUIDE -> "Guiding" to "last 2 min"
        SheetType.FOCUS -> "Focus" to "V-curve · 9 points"
        SheetType.ALERTS -> "Alerts" to "tonight"
        SheetType.SUMMARY -> "Session summary" to "21:48 → 04:12"
        SheetType.BENCH -> "Bench check" to "before you start"
        SheetType.PA -> "Polar alignment" to "three steps"
        SheetType.PREFS -> "Alert rules" to "push + on-screen"
        SheetType.AUTOFOCUS_RULES -> "Autofocus rules" to "when to refocus"
        SheetType.SETUP -> (if (state.setupEditingName != null) "Edit rig profile" else "New rig profile") to "name + device connections"
        SheetType.OPTICAL_TRAIN -> "Optical train" to "primary + secondary"
        SheetType.DEVICE -> {
            val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
            (state.selectedDeviceNames[d.key] ?: d.name) to (if (d.req) "required for a session" else "optional")
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
                SheetType.BENCH -> BenchSheet(state, ctrl, landscape)
                SheetType.PA -> PaSheet(state, ctrl, landscape)
                SheetType.PREFS -> PrefsSheet(state, ctrl)
                SheetType.AUTOFOCUS_RULES -> AutofocusRulesSheet(state, ctrl)
                SheetType.SETUP -> SetupBody(state, ctrl)
                SheetType.OPTICAL_TRAIN -> OpticalTrainSheet(state, ctrl)
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
private val JOGS = listOf(-1000, -100, -10, 10, 100, 1000)
private val RATES = listOf("0.5×", "1×", "8×", "64×", "max")

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
        ScopeInputFields(
            label = "Scope",
            name = state.scopeName, onNameChange = ctrl::setScopeName,
            focalMm = state.opticMm, onFocalChange = ctrl::setOpticMm,
            apertureMm = state.scopeApertureMm, onApertureChange = ctrl::setScopeApertureMm,
            note = opticNote(state.opticMm),
        )
        Spacer(Modifier.height(11.2.dp))
        ScopeInputFields(
            label = "Guide scope",
            name = state.guideScopeName, onNameChange = ctrl::setGuideScopeName,
            focalMm = state.guideOpticMm, onFocalChange = ctrl::setGuideOpticMm,
            apertureMm = state.guideScopeApertureMm, onApertureChange = ctrl::setGuideScopeApertureMm,
            note = guideOpticNote(state.guideOpticMm),
        )
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

/** Small labeled digits-only mm field, used for scope focal length/aperture. */
@Composable
private fun MmField(label: String, mm: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
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
                value = mm.toString(),
                onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0) },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            TextC("mm", style = t.MonoSmall, color = c.neutral500)
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
            text = "Save profile & open session",
            onClick = ctrl::finishSetup,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
    }
}

/**
 * Standalone entry point (from Gear tab) — the real Optical Trains dialog:
 * Primary/Secondary (fixed, no add/remove — matches the design brief, not the
 * desktop dialog's +/− train list), each with the same 10 roles as
 * `train_get_all` (message.cpp:236, `OpticalTrainManager::getOpticalTrains()`):
 * Mount/Camera/Rotator/Guide via/Dust cap/Scope/Filter wheel/Focuser/
 * Reducer/Light box. Every dropdown's pool is whatever's currently selected
 * in the rig profile's device/scope categories — Reducer is the one field
 * that isn't a device pick, just a per-train multiplier.
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
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextC(label, style = NocturneTheme.type.MonoSmall, color = if (sel) NocturneTheme.colors.accent400 else NocturneTheme.colors.textMuted)
                }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TrainForm(state, ctrl, slot)
    }
}

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

/** One role's picker — horizontally-scrolling chip row, since a pool can be 1-3 wide. */
@Composable
private fun RoleRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(4.dp))
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
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    TextC(opt, style = t.MonoSmall, color = if (sel) c.accent400 else c.textMuted)
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

// ── Bench ────────────────────────────────────────────────────────────────

@Composable
private fun BenchSheet(state: SimState, ctrl: SessionController, landscape: Boolean) {
    val left: @Composable () -> Unit = {
        Column {
            SnapPanel(
                tag = "2 s · bin 2",
                label = if (state.snappedMain) "★ 1 482 · HFR 2.31 · ADU 1 093" else "no test frame yet",
                snapLabel = "Snap main",
                onSnap = ctrl::snapMain,
            )
            Spacer(Modifier.height(14.dp))
            CoolerCard(state, ctrl)
        }
    }
    val right: @Composable () -> Unit = {
        Column {
            FocuserCard(state, ctrl)
            Spacer(Modifier.height(14.dp))
            MountCard(state, ctrl)
        }
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
            Spacer(Modifier.height(14.dp))
            right()
        }
    }
}

@Composable
private fun SnapPanel(tag: String, label: String, snapLabel: String, onSnap: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .background(c.surfaceDeep, RoundedCornerShape(4.dp)),
        ) {
            HatchBg(Modifier.fillMaxSize(), color = c.surfaceRaised)
            TextC(
                tag, style = t.MonoMicro, color = c.accent400,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 5.dp, start = 6.dp),
            )
        }
        TextC(label, style = t.MonoMini, color = c.neutral500, modifier = Modifier.padding(top = 5.dp))
        NocturneButton(
            text = snapLabel,
            onClick = onSnap,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
    }
}

@Composable
private fun CoolerCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val atSet = state.coolAtSetPoint
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("COOLER", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(
                (if (atSet) "at set point" else "ramping") + " · ${state.coolPowerPct}%",
                style = t.Mono115, color = if (atSet) c.ok else c.warn,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(String.format("%.1f", state.coolNow) + " °C", style = t.Mono26, color = c.text)
                TextC("sensor now", style = t.MonoMicro, color = c.textMuted)
            }
            CoolBtn("−") { ctrl.coolDown() }
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TextC("${String.format("%.0f", state.coolTarget)}°", style = t.Mono15, color = c.accent400)
                TextC("SET POINT", style = t.Caption10, color = c.textMuted)
            }
            CoolBtn("+") { ctrl.coolUp() }
        }
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(c.text.copy(alpha = 0.1f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.coolBarPct / 100f)
                    .height(4.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun CoolBtn(label: String, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .size(38.dp)
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextC(label, style = t.Mono17, color = c.neutral400)
    }
}

@Composable
private fun FocuserCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("FOCUSER · MANUAL", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC("${state.focPos}", style = t.Mono15, color = c.text)
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            JOGS.forEach { j ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                        .clickable { ctrl.jogFocus(j) },
                    contentAlignment = Alignment.Center,
                ) {
                    TextC(if (j > 0) "+$j" else "$j", style = t.MonoSmall, color = c.neutral400)
                }
                if (j != JOGS.last()) Spacer(Modifier.width(5.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        TextC("rough focus by eye, then run autofocus · backlash 90 out", style = t.MonoMicro, color = c.textMuted)
    }
}

@Composable
private fun MountCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("MOUNT · MANUAL", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(
                state.slewDir?.let { "slewing $it at ${RATES[state.rate]}" }
                    ?: (if (state.mountSolved) "tracking · sidereal · solved" else "tracking · sidereal"),
                style = t.Mono115,
                color = if (state.slewDir != null) c.warn else c.ok,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Top) {
            DPad(state, ctrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
                ) {
                    RATES.forEachIndexed { i, label ->
                        val sel = state.rate == i
                        Box(
                            Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { ctrl.setRate(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            TextC(label, style = t.MonoMicro, color = if (sel) c.accent400 else c.neutral500)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextC(
                    "alt ${"%.1f".format(state.mountAlt)}° · az ${"%.1f".format(state.mountAz)}°" +
                        if (state.mountSolved) "\nsolved — re-solve after moving" else "\nnot solved",
                    style = t.Mono115, color = c.neutral500,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Unpark / home",
                onClick = ctrl::unparkMount,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(38.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Plate solve here",
                onClick = ctrl::plateSolveHere,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(38.dp),
            )
        }
    }
}

@Composable
private fun DPad(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(Modifier.width(142.dp).height(142.dp)) {
        val dirs = listOf(
            Triple("N", Phosphor.CaretUp, Modifier.align(Alignment.TopCenter)),
            Triple("W", Phosphor.CaretLeft, Modifier.align(Alignment.CenterStart)),
            Triple("E", Phosphor.CaretRight, Modifier.align(Alignment.CenterEnd)),
            Triple("S", Phosphor.CaretDown, Modifier.align(Alignment.BottomCenter)),
        )
        dirs.forEach { d ->
            val k = d.first
            val icon = d.second
            val mod = d.third
            val sel = state.slewDir == k
            Box(
                mod
                    .size(44.dp)
                    .background(if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                    .clickable { ctrl.setSlewDir(k) },
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(icon, size = 20.dp, tint = if (sel) c.surfaceDeep else c.neutral400)
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .border(1.dp, c.danger.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .clickable { ctrl.stopSlew() },
            contentAlignment = Alignment.Center,
        ) {
            TextC("STOP", style = t.Button12, color = c.danger)
        }
    }
}

// ── Polar align ──────────────────────────────────────────────────────────

@Composable
private fun PaSheet(state: SimState, ctrl: SessionController, landscape: Boolean) {
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
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
        ) {
            d.catalog.forEachIndexed { i, name ->
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
                if (i < d.catalog.lastIndex) HDivider()
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
                        prop.format.format(prop.value), style = t.Mono15, color = c.text,
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

@Composable
private fun FieldLabel(text: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TextC(text, style = t.MicroUppercase, color = c.textMuted)
}
