package com.nocturne.ui.controls

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.benchFocPos
import com.nocturne.session.coolAtSetPoint
import com.nocturne.session.coolBarPct
import com.nocturne.session.coolPowerPct
import com.nocturne.session.indiNumber
import com.nocturne.session.realSlewRateProp
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val JOGS = listOf(-1000, -100, -10, 10, 100, 1000)
private val RATES = listOf("0.5×", "1×", "8×", "64×", "max")

/**
 * Per-module operational settings + live control — Camera/Guide/Mount/Align, split out of Gear
 * (which stays rig topology/setup: profile, devices, scopes, trains) once Gear started
 * accumulating both concerns. Consolidates what Bench check's live controls already did with
 * what Mount/Camera settings (M3.3) already do, plus Align (this tab) — Guide settings/control
 * land in a later phase. See docs/M3.3-plan.md's Addendum.
 *
 * Always visible, same as every other tab — individual cards inside gate themselves. The
 * Bench-derived live-control cards (Snap/Cooler/Focuser/Mount jog/Align solve) stay ungated:
 * they already work under the simulator via existing fixture fields, unchanged from how they
 * behaved inside the old Bench sheet. The curated `*_get_all_settings` cards (Mount/Camera/Align
 * settings) stay real-rig-only gated, same as they were on Gear — no fixture equivalent exists.
 */
@Composable
fun ControlsScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
            add(TabItem(full = true) { SectionHeader("PRIMARY CAMERA") })
            add(
                TabItem(full = true) {
                    val real = state.wireDevices != null
                    SnapPanel(
                        tag = "2 s · bin 2",
                        label = benchSnapLabel(real, state.snappedMain, state.wireCaptureStatus, "★ 1 482 · HFR 2.31 · ADU 1 093"),
                        snapLabel = "Snap main",
                        onSnap = ctrl::snapMain,
                    )
                },
            )
            add(TabItem(full = true) { CoolerCard(state, ctrl) })
            add(TabItem(full = true) { FocuserCard(state, ctrl) })
            if (state.isRealRig) add(TabItem { CameraSettingsCard(state, ctrl) })

            add(TabItem(full = true) { SectionHeader("GUIDE") })
            add(
                TabItem(full = true) {
                    val real = state.wireDevices != null
                    SnapPanel(
                        tag = "guide cam",
                        label = benchSnapLabel(real, state.snappedGuide, state.wireGuideStatus, "★ 214 · SNR 18.4"),
                        snapLabel = "Snap guide",
                        onSnap = ctrl::snapGuide,
                    )
                },
            )
            // Guide-module settings + start/stop control land in a later phase (M3.3 phase 4)
            // — see docs/M3.3-plan.md's Addendum for the Align-before-Guide phasing rationale.

            add(TabItem(full = true) { SectionHeader("MOUNT") })
            add(TabItem(full = true) { MountControlCard(state, ctrl) })
            if (state.isRealRig) add(TabItem { MountSettingsCard(state, ctrl) })

            add(TabItem(full = true) { SectionHeader("ALIGN") })
            add(TabItem(full = true) { AlignSolveCard(ctrl) })
            if (state.isRealRig) add(TabItem { AlignSettingsCard(state, ctrl) })
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TextC(text, style = t.MicroLabel, color = c.textFaint, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Real connection (`real` = [SimState.wireDevices] non-null): `capture_preview`/`guide_capture`
 * (M3.2) really do trigger a capture on the Pi, but the resulting image/HFR/ADU numbers arrive
 * over the Media channel, which doesn't exist yet (M4, `MediaChannel` is a stub) — so the honest
 * thing to show is the real capture/guide status push (already wired, M2), not a fabricated
 * readout. `SimulatedController` keeps the canned fixture text, unchanged.
 */
private fun benchSnapLabel(real: Boolean, snapped: Boolean, status: String?, fixtureText: String): String = when {
    !real -> if (snapped) fixtureText else "no test frame yet"
    status != null -> "status: $status"
    else -> "tap Snap to trigger a real capture (no live preview yet — needs the Media channel, M4)"
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
            style = BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
    }
}

/**
 * Real connection: the imaging camera's actual `CCD_TEMPERATURE`/`CCD_COOLER_POWER`
 * properties (confirmed live against a real ToupTek ATR2600M — see
 * [com.nocturne.session.EkosRemoteController]'s doc comment on `coolUp`/`coolDown`), read via
 * the same [indiNumber] helper the generic device sheets already use. `coolTarget` stays
 * Nocturne's own client-side "last commanded set point" either way — the real vector has no
 * separate target element to read back. Falls back to [SimulatedController]'s fixture
 * `coolNow`/`coolPowerPct` when no real camera is connected (including under
 * `SimulatedController`, which never populates `wireDevices`).
 */
@Composable
private fun CoolerCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val camera = state.primaryTrain.camera
    val cameraConnected = state.wireDevices?.any { it.name == camera && it.connected } == true
    val liveTemp = if (cameraConnected) state.indiNumber(camera, "CCD_TEMPERATURE") else null
    val livePowerPct = if (cameraConnected) state.indiNumber(camera, "CCD_COOLER_POWER") else null
    val sensorNow = liveTemp ?: state.coolNow
    val powerPct = livePowerPct?.roundToInt() ?: state.coolPowerPct
    val atSet = if (liveTemp != null) abs(liveTemp - state.coolTarget) < 0.5 else state.coolAtSetPoint
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("COOLER", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(
                (if (atSet) "at set point" else "ramping") + " · $powerPct%",
                style = t.Mono115, color = if (atSet) c.ok else c.warn,
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(String.format("%.1f", sensorNow) + " °C", style = t.Mono26, color = c.text)
                TextC(if (liveTemp != null) "sensor now (live)" else "sensor now", style = t.MonoMicro, color = c.textMuted)
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
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("FOCUSER · MANUAL", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC("${state.benchFocPos}", style = t.Mono15, color = c.text)
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

/**
 * Split from the old Bench `MountCard`: this keeps D-pad/rate chips/alt-az/Unpark-home only —
 * "Plate solve here" moved to [AlignSolveCard] since `align_solve` is literally the Align
 * module's own action, not Mount's.
 *
 * Known limitation carried over unchanged from the old Bench sheet (not a regression): leaving
 * this tab mid-slew does not auto-stop the mount — `stopSlew()` reads `state.slewDir` only when
 * explicitly tapped. A full tab makes "navigate away" a more casual action than dismissing a
 * sheet was, so this is worth a UX follow-up, but out of scope here.
 */
@Composable
private fun MountControlCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    // Real mount's own rate list/labels when available (see SimState.realSlewRateProp doc for
    // why this can't just be the fixed 5-option RATES fixture) — falls back to RATES/state.rate
    // for the simulator or before the real property has arrived.
    val realRates = state.realSlewRateProp
    val rateLabels = realRates?.options ?: RATES
    val selectedRateIndex = realRates?.selected ?: state.rate
    val currentRateLabel = rateLabels.getOrNull(selectedRateIndex) ?: "?"
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("MOUNT · MANUAL", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(
                state.slewDir?.let { "slewing $it at $currentRateLabel" }
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
                // Scrollable, fixed-width chips (not weight(1f)) so this holds up whether it's
                // the fixture's 5 options or a real driver's own count (10 for this LX200
                // OnStep, confirmed live — other drivers report different counts/labels again).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                        .horizontalScroll(rememberScrollState()),
                ) {
                    rateLabels.forEachIndexed { i, label ->
                        val sel = selectedRateIndex == i
                        Box(
                            Modifier
                                .width(44.dp)
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
        NocturneButton(
            text = "Unpark / home",
            onClick = ctrl::unparkMount,
            style = BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(38.dp),
        )
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

/** Extracted from the old Bench `MountCard` — `align_solve` is the Align module's own action, not Mount's. No controller change, same [ctrl.plateSolveHere] call as before. */
@Composable
private fun AlignSolveCard(ctrl: SessionController) {
    NocturneButton(
        text = "Plate solve here",
        onClick = ctrl::plateSolveHere,
        style = BtnStyle.SUBTLE,
        modifier = Modifier.fillMaxWidth().height(38.dp),
    )
}

/**
 * Curated Align settings (M3.3 phase 3, see docs/M3.3-plan.md) — real-rig only, same gating as
 * [MountSettingsCard]/[CameraSettingsCard]. Distinct from [AlignSolveCard]'s live solve action:
 * this is configuration (exposure, gain, filter, binning, solver accuracy), not live control.
 */
@Composable
private fun AlignSettingsCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val a = state.wireAlignSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.ALIGN_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Align settings", style = t.Body135, color = c.text)
        TextC(
            if (a == null) "loading…" else "${a.alignFilter} · ${a.alignBinning} · ${"%.0f".format(a.alignExposure)}s",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/**
 * Curated Mount settings (M3.3, see docs/M3.3-plan.md) — real-rig only, same
 * gating as Gear tab's ModuleAssignmentsCard. Distinct from [MountControlCard]'s
 * mount jog/slew controls: this is configuration (meridian flip, limits,
 * auto-park), not live control. Moved here from Gear tab — this is exactly
 * what the Controls tab is for.
 */
@Composable
private fun MountSettingsCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val m = state.wireMountSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.MOUNT_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.CompassTool, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Mount settings", style = t.Body135, color = c.text)
        TextC(
            if (m == null) "loading…" else {
                val flip = if (m.executeMeridianFlip) "flip on" else "flip off"
                val limits = if (m.enableAltitudeLimits || m.enableHaLimit) "limits on" else "no limits"
                "$flip · $limits"
            },
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/**
 * Curated Camera settings (M3.3 phase 5, see docs/M3.3-plan.md) — real-rig only, same gating as
 * [MountSettingsCard]. Distinct from the Sequence block editor's exposure/bin/gain/offset
 * (already live) and this section's own [CoolerCard] (already live): this covers save path + the
 * two guide-deviation abort guards + per-job dither, none of which have a home anywhere else in
 * the app. Moved here from Gear tab — this is exactly what the Controls tab is for.
 */
@Composable
private fun CameraSettingsCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val cam = state.wireCaptureSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.CAMERA_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Camera, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Camera settings", style = t.Body135, color = c.text)
        TextC(
            if (cam == null) "loading…" else {
                val guard = if (cam.enforceGuideDeviation || cam.enforceStartGuiderDrift) "guide guard on" else "no guide guard"
                val dither = if (cam.enableDitherPerJob) "dither on" else "dither off"
                "$guard · $dither"
            },
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}
