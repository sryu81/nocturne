package com.nocturne.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
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
import com.nocturne.session.IndiProperty
import com.nocturne.session.PA_SECS
import com.nocturne.session.PREF_DEFS
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.coolAtSetPoint
import com.nocturne.session.guideOpticNote
import com.nocturne.session.opticNote
import com.nocturne.session.coolBarPct
import com.nocturne.session.coolPowerPct
import com.nocturne.session.isOn
import com.nocturne.session.missing
import com.nocturne.session.paTotal
import com.nocturne.session.ready
import com.nocturne.session.rms
import com.nocturne.session.wiggle
import com.nocturne.ui.components.GuideTraceChart
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
        SheetType.SETUP -> (if (state.setupEditingName != null) "Edit rig profile" else "New rig profile") to "step ${state.setupStep + 1} of 4"
        SheetType.DEVICE -> {
            val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
            d.name to (if (d.req) "required for a session" else "optional")
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
                SheetType.FOCUS -> FocusSheet(state)
                SheetType.ALERTS -> AlertsSheet(ctrl)
                SheetType.SUMMARY -> SummarySheet()
                SheetType.BENCH -> BenchSheet(state, ctrl, landscape)
                SheetType.PA -> PaSheet(state, ctrl, landscape)
                SheetType.PREFS -> PrefsSheet(state, ctrl)
                SheetType.SETUP -> SetupBody(state, ctrl)
                SheetType.DEVICE -> DeviceSheet(state, ctrl)
            }
        },
        footer = if (sheet == SheetType.SETUP && state.setupStep < 3) {
            { SetupFooter(state, ctrl) }
        } else null,
    )
}

// ── Guide ────────────────────────────────────────────────────────────────

@Composable
private fun GuideSheet(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Panel {
            GuideTraceChart(
                ra = wiggle(state.t, 3, 90, 22.0),
                dec = wiggle(state.t, 41, 90, 14.0),
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
            Stat("PEAK", "1.42″", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("STAR SNR", "38", Modifier.weight(1f))
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
private fun FocusSheet(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Panel {
            VCurve(modifier = Modifier.fillMaxWidth().height(130.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextC("18 180", style = t.MonoMicro, color = c.textMuted, modifier = Modifier.align(Alignment.CenterStart))
                TextC("best 18 422 · HFR 2.27", style = t.MonoMicro, color = c.text, modifier = Modifier.align(Alignment.Center))
                TextC("18 660", style = t.MonoMicro, color = c.textMuted, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("POSITION", "${state.focPos}", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("TEMP Δ", "−0.6°", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("NEXT AF", "28 m", Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Run autofocus now",
            onClick = {},
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
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextC("Quiet hours", style = t.Body13, color = c.text, modifier = Modifier.weight(1f))
            TextC("off while imaging", style = t.Mono115, color = c.accent400)
        }
        Spacer(Modifier.height(8.4.dp))
        TextC(
            "Critical alerts (unsafe weather, disconnect, mount fault) always sound, even in quiet hours.",
            style = t.MonoMicro, color = c.textMuted, modifier = Modifier.padding(end = 4.dp),
        )
    }
}

// ── Setup ────────────────────────────────────────────────────────────────

private val SETUP_STEPS = listOf("Profile", "Connect", "Check", "Done")
private val PA_STEPS = listOf("Point", "Rotate + capture", "Adjust knobs")
private val DEVICE_ICONS: Map<String, ImageVector> = mapOf(
    "mount" to Phosphor.CompassTool,
    "cam" to Phosphor.Camera,
    "efw" to Phosphor.CirclesThree,
    "guide" to Phosphor.CrosshairSimple,
    "focus" to Phosphor.ArrowsInLineHorizontal,
    "rotator" to Phosphor.ArrowsClockwise,
    "weather" to Phosphor.CloudSun,
)
private val JOGS = listOf(-1000, -100, -10, 10, 100, 1000)
private val RATES = listOf("0.5×", "1×", "8×", "64×", "max")

@Composable
private fun SetupBody(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        StepPills(
            labels = SETUP_STEPS,
            current = state.setupStep,
        )
        Spacer(Modifier.height(14.dp))
        when (state.setupStep) {
            0 -> SetupStep0(state, ctrl)
            1 -> SetupStep1(state, ctrl)
            2 -> SetupStep2(ctrl)
            else -> SetupStep3(state, ctrl)
        }
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

@Composable
private fun SetupStep0(state: SimState, ctrl: SessionController) {
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
        FieldLabel("Optics")
        Spacer(Modifier.height(5.dp))
        FocalLengthField(mm = state.opticMm, onMmChange = ctrl::setOpticMm)
        Spacer(Modifier.height(5.dp))
        TextC(opticNote(state.opticMm), style = t.MonoMicro, color = c.textMuted)
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Guide optics")
        Spacer(Modifier.height(5.dp))
        FocalLengthField(mm = state.guideOpticMm, onMmChange = ctrl::setGuideOpticMm)
        Spacer(Modifier.height(5.dp))
        TextC(guideOpticNote(state.guideOpticMm), style = t.MonoMicro, color = c.textMuted)
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Site")
        Spacer(Modifier.height(5.dp))
        Panel {
            TextC("52.37 N · 4.89 E · 12 m — from phone GPS", style = t.MonoMid, color = c.neutral400)
        }
    }
}

/** Numeric mm input — plain text field, digits only. */
@Composable
private fun FocalLengthField(mm: Int, onMmChange: (Int) -> Unit) {
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
            value = mm.toString(),
            onValueChange = { text -> onMmChange(text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0) },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
        TextC("mm", style = t.MonoSmall, color = c.neutral500)
    }
}

@Composable
private fun SetupStep1(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(
            "Mount and camera are required; the rest can come later.",
            style = t.MonoSmall, color = c.neutral500,
        )
        Spacer(Modifier.height(8.4.dp))
        DEVICES.forEach { d ->
            val on = state.isOn(d.key)
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
                TextC(d.name, style = t.Body13, color = c.text, modifier = Modifier.weight(1f))
                TextC(
                    if (on) "LINKED" else if (d.req) "REQUIRED" else "OFF",
                    style = t.MonoMicro,
                    color = if (on) c.ok else if (d.req) c.danger else c.textFaint,
                    modifier = Modifier
                        .background(
                            if (on) c.ok.copy(alpha = 0.14f) else if (d.req) c.danger.copy(alpha = 0.16f) else c.divider.copy(alpha = 0.4f),
                            RoundedCornerShape(3.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(8.4.dp))
        }
    }
}

@Composable
private fun SetupStep2(ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .clickable { ctrl.openSheet(SheetType.BENCH) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Phosphor.Icon(Phosphor.TestTube, size = 20.dp, tint = c.accent400)
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC("Bench check", style = t.Body135, color = c.text)
                TextC("test frames · cooler · focuser · slew", style = t.MonoMicro, color = c.textMuted)
            }
            Phosphor.Icon(Phosphor.CaretRight, size = 15.dp, tint = c.neutral700)
        }
        Spacer(Modifier.height(8.4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .clickable { ctrl.openSheet(SheetType.PA) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = c.accent400)
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC("Polar align", style = t.Body135, color = c.text)
                TextC("3.2′ total error", style = t.MonoMicro, color = c.ok)
            }
            Phosphor.Icon(Phosphor.CaretRight, size = 15.dp, tint = c.neutral700)
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Both are optional to save the profile, but a session won't start clean without them.",
            style = t.MonoMicro, color = c.textMuted,
        )
    }
}

@Composable
private fun SetupStep3(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(14.dp),
        ) {
            TextC(state.profileName, style = t.Mono15, color = c.text)
            TextC(
                "${state.opticMm} mm · ${opticNote(state.opticMm)}",
                style = t.MonoSmall, color = c.neutral500, modifier = Modifier.padding(top = 6.dp),
            )
            TextC(
                if (state.ready) "Ready to image" else "Needs ${state.missing}" + " · polar ${String.format("%.1f", state.paTotal)}′",
                style = t.MonoSmall, color = if (state.ready) c.ok else c.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Saved. Next time this profile loads in one tap and every device reconnects with these settings.",
            style = t.Mono115, color = c.textMuted,
        )
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Save profile & open session",
            onClick = ctrl::finishSetup,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
    }
}

@Composable
private fun SetupFooter(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    Row(Modifier.fillMaxWidth()) {
        NocturneButton(
            text = "Back",
            onClick = ctrl::setupBack,
            style = com.nocturne.ui.components.BtnStyle.SUBTLE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Spacer(Modifier.width(8.4.dp))
        NocturneButton(
            text = if (state.setupStep == 3) "Done" else "Continue",
            onClick = ctrl::setupNext,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
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
                state.slewDir?.let { "slewing $it at ${RATES[state.rate]}" } ?: "tracking · sidereal",
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
                    "RA 20h59m12s\nDEC +44°31′08″\nalt 49.2° · az 71.6°",
                    style = t.Mono115, color = c.neutral500,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Unpark / home",
                onClick = {},
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(38.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Plate solve here",
                onClick = {},
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
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
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
        d.cfg.forEach { (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.text.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                    .clickable { }
                    .padding(horizontal = 11.2.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextC(label, style = t.Caption, color = c.textMuted, modifier = Modifier.width(88.dp))
                TextC(value, style = t.MonoMid, color = c.text, modifier = Modifier.weight(1f))
                Phosphor.Icon(Phosphor.CaretRight, size = 14.dp, tint = c.neutral700)
            }
            Spacer(Modifier.height(8.4.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Swap hardware",
                onClick = {},
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = if (on) "Disconnect" else "Connect",
                onClick = { ctrl.toggleDevice(d.key) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.weight(1f).height(44.dp),
            )
        }
        Spacer(Modifier.height(8.4.dp))
        TextC(
            "Saved to profile “${state.activeProfile ?: state.profileName}” — ${state.profiles.size} profiles",
            style = t.Mono115, color = c.textMuted,
        )
        IndiPropertyPanel(deviceKey = d.key, props = state.indiProps[d.key] ?: emptyList(), ctrl = ctrl)
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
private fun SummarySheet() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Row(Modifier.fillMaxWidth()) {
            SumStat("KEPT", "3h 10m", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("DISCARDED", "20m", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("MED HFR", "2.34", Modifier.weight(1f))
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
            SumBar("Ha", 0.74f, Color(0xFF9184D9), "2h 05m")
            SumBar("OIII", 0.38f, Color(0xFF796CBF), "1h 05m")
            SumBar("SII", 0.08f, Color(0xFF5D5294), "0h 20m")
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Lost 20m — cloud 01:04–01:18, one failed plate solve.\nBattery 12.1 V at teardown · dew never reached ambient.",
            style = t.MonoSmall, color = c.neutral500,
        )
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Export log + FITS list",
            onClick = {},
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
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
