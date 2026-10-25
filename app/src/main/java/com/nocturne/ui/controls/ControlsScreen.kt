package com.nocturne.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.CaptureProgress
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.session.primaryImageSize
import com.nocturne.session.benchFocPos
import com.nocturne.session.captureProgress
import com.nocturne.session.coolerIsOn
import com.nocturne.session.focRange
import com.nocturne.session.guideCameraDevice
import com.nocturne.session.indiSwitchElements
import com.nocturne.session.indiSwitchElement
import com.nocturne.session.focusCameraDevice
import com.nocturne.session.gainSpecialValue
import com.nocturne.session.indiNumber
import com.nocturne.session.mountParkedReal
import com.nocturne.session.mountTrackingOn
import com.nocturne.session.paTotal
import com.nocturne.session.realFilterNames
import com.nocturne.session.realSlewRateProp
import com.nocturne.ui.components.GainField
import com.nocturne.ui.components.FocusCurve
import com.nocturne.ui.components.GuideTelemetry
import com.nocturne.ui.components.BtnStyle
import com.nocturne.protocol.WireAlignSolution
import com.nocturne.ui.components.AccuracyTrendChart
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val RATES = listOf("0.5×", "1×", "8×", "64×", "max")
private val BIN_OPTIONS = listOf(1, 2, 3, 4)
private val GUIDE_BIN_OPTIONS = listOf("1x1", "2x2", "3x3", "4x4")

/**
 * Focus and Guide report an empty binning until their camera defines `CCD_BINNING` (about a minute
 * after Ekos says it is connected); Ekos' own default is 1x1, shown until the real value arrives.
 */
private const val DEFAULT_BIN = "1x1"

/**
 * Live control and per-module settings for Camera, Focus, Align, Mount, Guide and polar alignment.
 * Rig topology (profile, devices, scopes, trains) lives in Gear. Each card gates itself.
 */
@Composable
fun ControlsScreen(
    state: AppState,
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
                    val cam = state.wireCaptureSettings
                    val progress = if (real) state.captureProgress(state.primaryTrain.camera, cam?.captureExposureN ?: 2.0) else null
                    // FilterPosCombo is the Capture module's loaded filter, like exposure/gain/bin.
                    SnapPanel(
                        tag = if (cam != null) "${"%.0f".format(cam.captureExposureN)} s · bin ${cam.captureBinHN} · ${cam.FilterPosCombo}" else "main cam",
                        label = benchSnapLabel(real, state.snappedMain, state.wireCaptureStatus, "★ 1 482 · HFR 2.31 · ADU 1 093"),
                        // "Preview" matches Ekos's own label for this action (camera.ui previewB).
                        snapLabel = "Preview Main Cam",
                        onSnap = ctrl::snapMain,
                        progress = progress,
                        onStop = { ctrl.setIndiSwitch(state.primaryTrain.camera, "CCD_ABORT_EXPOSURE", 0) },
                        pendingNote = if (real) capturePendingNote(state) else null,
                        onCancelPending = ctrl::cancelPendingCapture,
                        // Focus frames come from the same camera, so they show here too.
                        frame = state.latestCaptureFrame ?: state.latestFocusFrame,
                        // One row; BIN/FILTER get labels so all four boxes line up.
                        previewControls = cam?.let {
                            {
                                val cc = NocturneTheme.colors
                                val tt = NocturneTheme.type
                                Row(Modifier.fillMaxWidth()) {
                                    MiniField("EXP", it.captureExposureN, "s", Modifier.weight(1f), ctrl::setCapturePreviewExposure)
                                    Spacer(Modifier.width(8.dp))
                                    GainField(it.captureGainN, state.gainSpecialValue(state.primaryTrain.camera), Modifier.weight(1f), ctrl::setCapturePreviewGain)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        TextC("BIN", style = tt.MonoMicro, color = cc.textMuted)
                                        Spacer(Modifier.height(3.dp))
                                        BinCycleChip(it.captureBinHN, BIN_OPTIONS, labelOf = { "${it}x$it" }, modifier = Modifier.fillMaxWidth()) { ctrl.setCapturePreviewBinning(it) }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        TextC("FILTER", style = tt.MonoMicro, color = cc.textMuted)
                                        Spacer(Modifier.height(3.dp))
                                        val filterNames = state.realFilterNames.orEmpty()
                                        FilterCycleChip(it.FilterPosCombo, filterNames, modifier = Modifier.fillMaxWidth()) { ctrl.setCameraFilter(it) }
                                    }
                                }
                                ImageSizeLine(state, ctrl::setCaptureFullFrame)
                            }
                        },
                    )
                },
            )
            add(TabItem(full = true) { CoolerCard(state, ctrl) })
            add(TabItem(full = true) { FocuserCard(state, ctrl) })
            add(TabItem { FocusSettingsCard(state, ctrl) })
            // Plate solving is grouped under the primary camera, marked by a sub-label.
            add(TabItem(full = true) { SectionHeader("PLATE SOLVE", sub = true) })
            add(TabItem(full = true) { AlignSolveCard(state, ctrl) })
            add(TabItem(full = true) { AlignAccuracyCard(state) })
            add(TabItem(full = true) { RotatorControlCard(state, ctrl) })
            add(TabItem { AlignSettingsCard(state, ctrl) })

            add(TabItem(full = true) { SectionHeader("MOUNT") })
            add(TabItem(full = true) { MountControlCard(state, ctrl) })
            add(TabItem { MountSettingsCard(state, ctrl) })

            add(TabItem(full = true) { SectionHeader("GUIDE") })
            add(
                TabItem(full = true) {
                    val real = state.wireDevices != null
                    val guide = state.wireGuideSettings
                    val guideCam = state.guideCameraDevice
                    val progress = if (real) state.captureProgress(guideCam, guide?.guideExposure ?: 1.0) else null
                    SnapPanel(
                        tag = if (guide != null) "${"%.0f".format(guide.guideExposure)} s · bin ${guide.guideBinning.ifBlank { DEFAULT_BIN }}" else "guide cam",
                        label = benchSnapLabel(real, state.snappedGuide, state.wireGuideStatus, "★ 214 · SNR 18.4"),
                        snapLabel = "Preview Guide Cam",
                        onSnap = ctrl::snapGuide,
                        progress = progress,
                        onStop = guideCam?.let { cam -> { ctrl.setIndiSwitch(cam, "CCD_ABORT_EXPOSURE", 0) } },
                        frame = state.latestGuideFrame,
                        // No filter chip: Guide settings have no FilterPosCombo equivalent.
                        previewControls = guide?.let {
                            {
                                val cc = NocturneTheme.colors
                                val tt = NocturneTheme.type
                                Row(Modifier.fillMaxWidth()) {
                                    MiniField("EXP", it.guideExposure, "s", Modifier.weight(1f), ctrl::setGuidePreviewExposure)
                                    Spacer(Modifier.width(8.dp))
                                    GainField(it.guideGain, state.gainSpecialValue(guideCam), Modifier.weight(1f), ctrl::setGuidePreviewGain)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        TextC("BIN", style = tt.MonoMicro, color = cc.textMuted)
                                        Spacer(Modifier.height(3.dp))
                                        BinCycleChip(it.guideBinning.ifBlank { DEFAULT_BIN }, GUIDE_BIN_OPTIONS, labelOf = { it }, modifier = Modifier.fillMaxWidth()) { ctrl.setGuidePreviewBinning(it) }
                                    }
                                }
                            }
                        },
                    )
                },
            )
            add(TabItem(full = true) { GuideControlCard(state, ctrl) })
            add(TabItem { GuideSettingsCard(state, ctrl) })

            add(TabItem(full = true) { SectionHeader("POLAR ALIGNMENT") })
            add(TabItem { PaCard(state, ctrl) })
        },
    )
}

@Composable
private fun SectionHeader(text: String, sub: Boolean = false) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    if (sub) {
        // Sub-label within a section, styled like card headers so it doesn't read as a new section.
        TextC(text, style = t.MicroLabel, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
        return
    }
    TextC(text, style = t.Body135, color = c.text, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Capture states in which a requested capture is held before exposing (`ekos.h` captureStates).
 * "Setting Temperature" is the common one: with enforce temperature on, a preview waits until the
 * sensor reaches the set point, indefinitely if the cooler can't get there.
 */
private fun capturePendingNote(state: AppState): String? {
    val status = state.wireCaptureStatus ?: return null
    return when (status) {
        "Setting Temperature" -> {
            val now = state.indiNumber(state.primaryTrain.camera, "CCD_TEMPERATURE")
            val target = state.wireCaptureSettings?.cameraTemperatureN ?: state.coolTarget
            "waiting for temperature " + (now?.let { "%.1f".format(it) } ?: "N/A") + " → " +
                (target?.let { "%.1f °C".format(it) } ?: "N/A") + " before exposing (enforce temperature is on)"
        }
        "Setting Rotator", "Waiting", "Paused", "Meridian Flip" -> "capture held: $status — will start by itself when it clears"
        else -> null
    }
}

/**
 * Status line under a preview. The wire carries no HFR/ADU for a preview, so a connected rig
 * shows the capture/guide status push instead of a readout.
 */
private fun benchSnapLabel(real: Boolean, snapped: Boolean, status: String?, fixtureText: String): String = when {
    !real -> if (snapped) fixtureText else "no test frame yet"
    status != null -> "status: $status"
    else -> "tap Snap to trigger a real capture"
}

/**
 * Preview frame, status and Snap/Stop for one camera.
 *
 * `previewControls`: `capture_preview`/`guide_capture` take no parameters; Ekos uses the module's
 * loaded exposure/gain/bin, so these controls edit those via `*_set_all_settings`. Null until the
 * module's `*_get_all_settings` reply arrives.
 */
@Composable
private fun SnapPanel(
    tag: String,
    label: String,
    snapLabel: String,
    onSnap: () -> Unit,
    previewControls: (@Composable () -> Unit)? = null,
    /** Exposure countdown/busy state ([com.nocturne.session.captureProgress]); null until the camera's property arrives. */
    progress: CaptureProgress? = null,
    /** Writes `CCD_ABORT_EXPOSURE`; null when there's no camera to target yet. */
    onStop: (() -> Unit)? = null,
    /** `/media/ekos` frame for this camera; null renders the hatch placeholder. */
    frame: com.nocturne.protocol.MediaFrame? = null,
    /** Non-null while a requested capture hasn't started exposing (e.g. waiting for temperature),
     *  shown with [onCancelPending] so a late exposure is never a surprise. */
    pendingNote: String? = null,
    onCancelPending: (() -> Unit)? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val busy = progress?.busy == true
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                // Capped: in landscape the card spans the full grid width, and 4:3 alone would make
                // the box several screens tall. The frame is fitted inside either way.
                .heightIn(max = 260.dp)
                .background(c.surfaceDeep, RoundedCornerShape(4.dp)),
        ) {
            MediaFramePreview(frame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            if (frame == null) {
                TextC("no frame yet", style = t.Caption, color = c.textFaint, modifier = Modifier.align(Alignment.Center))
            }
            TextC(
                tag, style = t.Mono115, color = c.accent400,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 5.dp, start = 6.dp),
            )
        }
        TextC(label, style = t.MonoSmall, color = c.text, modifier = Modifier.padding(top = 5.dp))
        // Countdown from the camera's CCD_EXPOSURE (guide_capture emits no status push).
        // remainingSec can be null briefly as a capture starts.
        if (busy) {
            val remaining = progress?.remainingSec
            TextC(
                if (remaining != null) "capturing · %.1fs left".format(remaining) else "capturing…",
                style = t.MonoSmall, color = c.accent400, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (previewControls != null) {
            Spacer(Modifier.height(8.dp))
            previewControls()
        }
        if (pendingNote != null && !busy) {
            TextC(pendingNote, style = t.MonoSmall, color = c.warn, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (pendingNote != null && !busy && onCancelPending != null) {
            Row(Modifier.fillMaxWidth()) {
                NocturneButton(
                    text = snapLabel, onClick = onSnap, style = BtnStyle.OUTLINE, enabled = false,
                    modifier = Modifier.weight(1f).height(34.dp),
                )
                Spacer(Modifier.width(8.dp))
                NocturneButton(
                    text = "Cancel", onClick = onCancelPending, style = BtnStyle.DANGER,
                    modifier = Modifier.weight(1f).height(34.dp),
                )
            }
        } else if (busy && onStop != null) {
            Row(Modifier.fillMaxWidth()) {
                NocturneButton(
                    text = snapLabel, onClick = onSnap, style = BtnStyle.OUTLINE, enabled = false,
                    modifier = Modifier.weight(1f).height(34.dp),
                )
                Spacer(Modifier.width(8.dp))
                NocturneButton(
                    text = "Stop", onClick = onStop, style = BtnStyle.DANGER,
                    modifier = Modifier.weight(1f).height(34.dp),
                )
            }
        } else {
            NocturneButton(
                text = snapLabel,
                onClick = onSnap,
                style = BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(34.dp),
            )
        }
    }
}

/**
 * Small labeled decimal field. Keeps its own `text` so partial input (empty, `"-"`, a trailing
 * `"."`) survives recomposition; [onChange] fires only when the text parses.
 */
@Composable
private fun MiniField(label: String, value: Double?, unit: String, modifier: Modifier, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf(value?.let { "%.1f".format(it) } ?: "") }
    var focused by remember { mutableStateOf(false) }
    // Follow Ekos while the user isn't typing: a scheduler job or another client changes the
    // module's loaded value (e.g. exposure) and a stale box would contradict the status line.
    LaunchedEffect(value, focused) {
        if (!focused && value != null) text = "%.1f".format(value)
    }
    Column(modifier) {
        TextC(label, style = t.MonoMicro, color = c.textMuted)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) TextC("N/A", style = t.Body13, color = c.textFaint)
                BasicTextField(
                    value = text,
                    onValueChange = { new ->
                        val filtered = new.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }
                        text = filtered
                        filtered.toDoubleOrNull()?.let(onChange)
                    },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
            }
            if (unit.isNotEmpty()) TextC(unit, style = t.MonoSmall, color = c.neutral500)
        }
    }
}

/**
 * Tap-to-cycle binning chip. Generic because Capture sends binning as `Int` (`2`) and
 * Guide/Focus as `String` (`"2x2"`).
 */
@Composable
private fun <T> BinCycleChip(value: T, options: List<T>, labelOf: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        modifier
            .height(40.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .clickable { onSelect(options[(options.indexOf(value) + 1).mod(options.size)]) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // No "bin" prefix: the BIN label above the chip says it, and "bin 2x2" doesn't fit the narrow chips.
        TextC(labelOf(value), style = t.Body13, color = c.text, maxLines = 1)
    }
}

/**
 * Tap-to-cycle filter chip over the filter wheel's slot [names]; shows N/A and doesn't cycle when unknown.
 * Sets the module's own filter, which is what a preview or autofocus run uses, independent of
 * any queued sequence.
 */
@Composable
private fun FilterCycleChip(value: String, names: List<String>, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val idx = names.indexOf(value)
    Box(
        modifier
            .height(40.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .clickable(enabled = names.isNotEmpty()) { onSelect(names[(idx + 1).mod(names.size)]) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextC(value.ifBlank { "N/A" }, style = t.Body13, color = if (value.isBlank()) c.textMuted else c.text)
    }
}

/**
 * The main camera's output size: Ekos's capture frame (applied to CCD_FRAME before each exposure)
 * against the full sensor, plus the last frame's size. A crop shows in the warning colour, since
 * a new optical train can inherit another camera's frame.
 */
@Composable
private fun ImageSizeLine(state: AppState, onFullFrame: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val size = state.primaryImageSize() ?: return
    val last = state.latestCaptureFrame?.header?.resolution?.replace("x", "×")
    val binned = if (size.binH > 1 || size.binV > 1) " · bin ${size.binH}×${size.binV} → ${size.outW} × ${size.outH} px" else ""
    val text = if (size.cropped) {
        "IMAGE ${size.frameW} × ${size.frameH} · CROP of ${size.sensorW} × ${size.sensorH} at x ${size.x}, y ${size.y}$binned"
    } else {
        "IMAGE ${size.frameW} × ${size.frameH}" + (if (size.sensorW != null) " · full sensor" else "") + binned
    }
    Spacer(Modifier.height(6.dp))
    TextC(
        text + (last?.let { " · last frame $it" } ?: ""),
        style = t.MonoMicro,
        color = if (size.cropped) c.warn else c.textMuted,
    )
    if (size.cropped) {
        Spacer(Modifier.height(6.dp))
        NocturneButton(
            text = "Set full frame (${size.sensorW} × ${size.sensorH})",
            onClick = onFullFrame,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Cooler from the camera's `CCD_TEMPERATURE`/`CCD_COOLER_POWER`. `coolTarget` is seeded from the
 * Capture module's setpoint (`cameraTemperatureN`) and sent on Set, not per keystroke.
 */
@Composable
private fun CoolerCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val camera = state.primaryTrain.camera
    val cameraConnected = state.wireDevices?.any { it.name == camera && it.connected } == true
    val liveTemp = if (cameraConnected) state.indiNumber(camera, "CCD_TEMPERATURE") else null
    val livePowerPct = if (cameraConnected) state.indiNumber(camera, "CCD_COOLER_POWER") else null
    val powerPct = livePowerPct?.roundToInt()
    val powerText = powerPct?.let { "$it%" } ?: "power N/A"
    val target = state.coolTarget
    val atSet = if (liveTemp != null && target != null) abs(liveTemp - target) < 0.5 else null
    // CCD_COOLER / fan switches; null = property not seen yet.
    val coolerOn = if (cameraConnected) state.coolerIsOn(camera) else null
    val fanElements = if (cameraConnected) state.indiSwitchElements(camera, CAMERA_FAN_PROP) else emptyList()
    val fanNow = if (cameraConnected) state.indiSwitchElement(camera, CAMERA_FAN_PROP) else null
    val enforce = state.wireCaptureSettings?.cameraTemperatureS
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
                when {
                    coolerOn == false -> "cooler off"
                    atSet == true -> "at set point · $powerText"
                    atSet == false && powerPct == 0 -> "not cooling · $powerText"
                    atSet == false -> "ramping · $powerText"
                    else -> powerText
                },
                style = t.Mono115,
                color = when { coolerOn == false || atSet == null -> c.textMuted; atSet -> c.ok; powerPct == 0 -> c.danger; else -> c.warn },
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                TextC(liveTemp?.let { String.format("%.1f °C", it) } ?: "N/A", style = t.Mono26, color = if (liveTemp != null) c.text else c.textMuted)
                TextC("sensor now", style = t.MonoMicro, color = c.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            MiniField("SET POINT", state.coolTarget, "°C", Modifier.width(96.dp), ctrl::setCoolTarget)
            Spacer(Modifier.width(6.dp))
            NocturneButton(
                text = "Set", onClick = ctrl::applyCoolTarget, style = BtnStyle.SUBTLE,
                enabled = cameraConnected, modifier = Modifier.width(64.dp).height(40.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Cooler " + when (coolerOn) { true -> "ON"; false -> "OFF"; null -> "—" },
                onClick = { ctrl.setCoolerEnabled(coolerOn != true) },
                style = if (coolerOn == true) BtnStyle.SOLID else BtnStyle.SUBTLE,
                enabled = cameraConnected,
                modifier = Modifier.weight(1f).height(36.dp),
            )
            if (fanElements.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                NocturneButton(
                    text = "Fan " + fanLabel(fanNow),
                    // Cycles OFF → ON (→ speed 2 → … on multi-speed models) → OFF.
                    onClick = {
                        val i = fanElements.indexOf(fanNow)
                        ctrl.setCameraFan(fanElements[(i + 1).mod(fanElements.size)])
                    },
                    style = if (fanNow != null && fanNow != "INDI_DISABLED") BtnStyle.SOLID else BtnStyle.SUBTLE,
                    modifier = Modifier.weight(1f).height(36.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            label = "Enforce temperature",
            sub = "captures and previews wait until the sensor reaches the set point",
            checked = enforce == true,
            onToggle = { ctrl.setTemperatureEnforced(enforce != true) },
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(c.text.copy(alpha = 0.1f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((powerPct ?: 0).coerceIn(0, 100) / 100f)
                    .height(4.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** ToupTek-family fan switch (indi_toupbase `TC_FAN_SPEED`). */
private const val CAMERA_FAN_PROP = "TC_FAN_SPEED"

private fun fanLabel(element: String?): String = when {
    element == null -> "—"
    element == "INDI_DISABLED" -> "OFF"
    element == "INDI_ENABLED" -> "ON"
    element.startsWith("FAN_SPEED") -> "speed " + element.removePrefix("FAN_SPEED")
    else -> element
}

/**
 * Focus In/Out, focus-camera settings, and autofocus start/stop. [AppState.focusRunning] is
 * optimistic on tap, then follows the focus status ([com.nocturne.session.FOCUS_IDLE_STATES]).
 * The V-curve ([FocusCurve]) shows each measured frame.
 */
@Composable
private fun FocuserCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val pos = state.benchFocPos
    // Hard end-stops (ABS_FOCUS_POSITION min/max); null until the property arrives. Ekos refuses
    // moves past them too, but Focus In/Out are also disabled here as a second guard.
    val range = state.focRange
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(11.2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("FOCUSER · MANUAL", style = t.MicroLabel, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(
                (pos?.toString() ?: "N/A") + (range?.let { "  (${it.first}–${it.last})" } ?: ""),
                style = t.Mono15, color = if (pos != null) c.text else c.textMuted,
            )
        }
        if (range != null && pos != null) {
            Spacer(Modifier.height(6.dp))
            FocuserRangeBar(pos, range)
        }
        // The Focus module's own exposure/gain/bin/filter: `focus_start` takes no parameters, so
        // autofocus uses whatever these are set to.
        state.wireFocusSettings?.let { fs ->
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniField("EXP", fs.focusExposure, "s", Modifier.weight(1f), ctrl::setFocusExposure)
                Spacer(Modifier.width(8.dp))
                GainField(fs.focusGain, state.gainSpecialValue(state.focusCameraDevice), Modifier.weight(1f), ctrl::setFocusGain)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    TextC("BIN", style = t.MonoMicro, color = c.textMuted)
                    Spacer(Modifier.height(3.dp))
                    BinCycleChip(fs.focusBinning.ifBlank { DEFAULT_BIN }, GUIDE_BIN_OPTIONS, labelOf = { it }, modifier = Modifier.fillMaxWidth()) { ctrl.setFocusBinning(it) }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    TextC("FILTER", style = t.MonoMicro, color = c.textMuted)
                    Spacer(Modifier.height(3.dp))
                    val filterNames = state.realFilterNames.orEmpty()
                    FilterCycleChip(fs.focusFilter, filterNames, modifier = Modifier.fillMaxWidth()) { ctrl.setFocusFilter(it) }
                }
            }
        }
        // No amount choice: the buttons send steps=0, so Ekos moves by the Mechanics "Initial Step
        // Size" (`focusTicks`), shown on the buttons.
        Spacer(Modifier.height(9.dp))
        val step = state.wireFocusSettings?.focusTicks
        Row(Modifier.fillMaxWidth()) {
            val atMin = range != null && pos != null && pos <= range.first
            val atMax = range != null && pos != null && pos >= range.last
            NocturneButton(
                text = "Focus In" + (step?.let { " (−$it)" } ?: ""),
                onClick = ctrl::focusStepIn,
                enabled = !atMin,
                style = BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            NocturneButton(
                text = "Focus Out" + (step?.let { " (+$it)" } ?: ""),
                onClick = ctrl::focusStepOut,
                enabled = !atMax,
                style = BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(9.dp))
        TextC("rough focus by eye, then run autofocus · backlash 90 out", style = t.MonoMicro, color = c.textMuted)
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC(
                "AUTOFOCUS · " + (state.wireFocusStatus?.let { "status: $it" } ?: if (state.focusRunning) "running…" else "idle"),
                style = t.Mono115, color = if (state.focusRunning) c.warn else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            NocturneButton(
                text = if (state.focusRunning) "Stop" else "Start autofocus",
                onClick = { if (state.focusRunning) ctrl.stopAutofocus() else ctrl.startAutofocus() },
                style = if (state.focusRunning) BtnStyle.SOLID else BtnStyle.SUBTLE,
                modifier = Modifier.height(34.dp),
            )
        }
        if (state.focusRunning || state.focusSamples.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            FocusCurve(state, chartHeight = 110.dp)
        }
    }
}

/** Position within the focuser's hard end-stops. */
@Composable
private fun FocuserRangeBar(pos: Int, range: IntRange) {
    val c = NocturneTheme.colors
    val span = (range.last - range.first).coerceAtLeast(1)
    val frac = ((pos - range.first).toFloat() / span).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(c.divider, RoundedCornerShape(2.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(4.dp)
                .background(c.accent, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * D-pad, slew rates, alt/az, Park/Unpark and tracking (Ekos mount commands), plus Home. Ekos has
 * no home command, so Home writes the INDI `TELESCOPE_HOME` switch directly; it no-ops on
 * drivers without that property. (OnStep's `HomePause` is unrelated: it pauses at a meridian flip.)
 *
 * Leaving this tab mid-slew does not stop the mount; only STOP does.
 */
@Composable
private fun MountControlCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    // The driver's own rate list (counts and labels vary by driver); RATES until it arrives.
    val realRates = state.realSlewRateProp
    val rateLabels = realRates?.options ?: RATES
    val selectedRateIndex = realRates?.selected ?: state.rate
    val currentRateLabel = selectedRateIndex?.let { rateLabels.getOrNull(it) } ?: "N/A"
    val parked = state.mountParkedReal
    val trackingOn = state.mountTrackingOn
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
                when {
                    state.slewDir != null -> "slewing ${state.slewDir} at $currentRateLabel"
                    parked -> "parked"
                    trackingOn == null -> "tracking N/A"
                    !trackingOn -> "tracking off"
                    state.mountSolved -> "tracking · sidereal · solved"
                    else -> "tracking · sidereal"
                },
                style = t.Mono115,
                color = when {
                    trackingOn == null -> c.textMuted
                    state.slewDir != null || parked || !trackingOn -> c.warn
                    else -> c.ok
                },
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.Top) {
            DPad(state, ctrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Scrollable fixed-width chips: the number of slew rates varies by driver.
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
                                .width(48.dp)
                                .height(40.dp)
                                .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { ctrl.setRate(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            TextC(label, style = t.MonoSmall, color = if (sel) c.accent400 else c.neutral500)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextC(
                    "alt " + (state.mountAlt?.let { "%.1f°".format(it) } ?: "N/A") +
                        " · az " + (state.mountAz?.let { "%.1f°".format(it) } ?: "N/A") +
                        if (state.mountSolved) "\nsolved — re-solve after moving" else "\nnot solved",
                    style = t.Mono115, color = c.neutral500,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Park",
                onClick = ctrl::parkMount,
                style = if (parked) BtnStyle.SOLID else BtnStyle.SUBTLE,
                enabled = !parked,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Unpark",
                onClick = ctrl::unparkMount,
                style = if (!parked) BtnStyle.SOLID else BtnStyle.SUBTLE,
                enabled = parked,
                modifier = Modifier.weight(1f).height(44.dp),
            )
        }
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Home",
                onClick = { ctrl.setIndiSwitch(state.primaryTrain.mount, "TELESCOPE_HOME", 1) },
                style = BtnStyle.SUBTLE,
                enabled = state.wireDevices != null,
                modifier = Modifier.weight(1f).height(44.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Tracking: " + when (trackingOn) { true -> "ON"; false -> "OFF"; null -> "N/A" },
                onClick = { ctrl.setMountTracking(trackingOn != true) },
                style = if (trackingOn == true) BtnStyle.SOLID else BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(44.dp),
            )
        }
    }
}

@Composable
private fun DPad(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(Modifier.width(152.dp).height(152.dp)) {
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
                    .size(48.dp)
                    .background(if (sel) c.accent else c.text.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                    .clickable { ctrl.setSlewDir(k) },
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(icon, size = 22.dp, tint = if (sel) c.surfaceDeep else c.neutral400)
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .border(1.dp, c.danger.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .clickable { ctrl.stopSlew() },
            contentAlignment = Alignment.Center,
        ) {
            TextC("STOP", style = t.Button12, color = c.danger)
        }
    }
}

/**
 * Guide start/stop (`guide_stop` is Ekos's `abort()`, not a pause), loop and clear calibration.
 * [AppState.guiding] is optimistic on tap, then follows `new_guide_state`
 * (busy unless in [com.nocturne.session.GUIDE_IDLE_STATES]).
 */
@Composable
private fun GuideControlCard(state: AppState, ctrl: SessionController) {
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
            TextC(
                "GUIDING · " + (state.wireGuideStatus?.let { "status: $it" } ?: if (state.guiding) "running…" else "idle"),
                style = t.Mono115, color = if (state.guiding) c.warn else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            NocturneButton(
                text = if (state.guiding) "Stop" else "Start guiding",
                onClick = { if (state.guiding) ctrl.stopGuiding() else ctrl.startGuiding() },
                style = if (state.guiding) BtnStyle.SOLID else BtnStyle.SUBTLE,
                modifier = Modifier.height(34.dp),
            )
        }
        // Idle-only, as on the desktop. Stop above also ends a loop.
        Spacer(Modifier.height(8.dp))
        Row {
            NocturneButton(
                text = "Loop",
                onClick = ctrl::loopGuide,
                style = BtnStyle.SUBTLE,
                enabled = !state.guiding,
                modifier = Modifier.weight(1f).height(34.dp),
            )
            Spacer(Modifier.width(8.dp))
            NocturneButton(
                text = "Clear calibration",
                onClick = ctrl::clearGuideCalibration,
                style = BtnStyle.SUBTLE,
                enabled = !state.guiding,
                modifier = Modifier.weight(1f).height(34.dp),
            )
        }
        if (state.guideSamples.isNotEmpty() || state.guideRmsRA != null) {
            Spacer(Modifier.height(10.dp))
            // Tap for the full Guide sheet (bigger chart + guide frame) — its only other entry
            // point is the Sequence tab's RMS tile, which is hidden when no job is running.
            GuideTelemetry(
                state, chartHeight = 64.dp,
                modifier = Modifier.clickable { ctrl.openSheet(SheetType.GUIDE) },
            )
        }
    }
}

/**
 * Plate solve here, with the align status below it (`ekos.h` vocabulary: Idle/Complete/Failed/
 * Aborted/In Progress/Successful/Syncing/Slewing/Rotating/Suspended).
 */
@Composable
private fun AlignSolveCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        NocturneButton(
            text = "Plate solve here",
            onClick = ctrl::plateSolveHere,
            style = BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(38.dp),
        )
        state.wireAlignStatus?.let {
            Spacer(Modifier.height(6.dp))
            TextC(it, style = t.Caption, color = c.textMuted)
        }
    }
}

/**
 * Per-solve pointing-accuracy trend from Align's `solution.targetDiff` ([WireAlignSolution]),
 * sent on every successful solve. Hidden until the first solve of this connection.
 */
@Composable
private fun AlignAccuracyCard(state: AppState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val history = state.alignAccuracyHistory
    if (history.isEmpty()) return
    val threshold = state.wireAlignSettings?.alignAccuracyThreshold
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextC("Target accuracy", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
            TextC("${"%.1f".format(history.last())}\"", style = t.Mono115, color = c.accent400)
        }
        Spacer(Modifier.height(8.dp))
        AccuracyTrendChart(
            values = history,
            thresholdArcsec = threshold,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
        if (threshold != null) {
            Spacer(Modifier.height(4.dp))
            TextC("dashed line = solver accuracy threshold (${"%.0f".format(threshold)}\")", style = t.MonoMicro, color = c.textFaint)
        }
    }
}

/**
 * Rotator control and current → target PA readback, refreshed by each solve (Ekos's manual
 * rotator dialog also just calls `captureAndSolve()`). Optional; nothing gates on it.
 *
 * `rotator_control` gates the whole feature (`align_goto.cpp` `checkIfRotationRequired()`): when
 * off, no rotator is driven and the no-hardware readback never populates.
 */
@Composable
private fun RotatorControlCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val hasRealRotator = state.primaryTrain.rotator != "None"
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        SwitchRow(
            label = "Rotator control",
            sub = if (hasRealRotator) {
                "Real ${state.primaryTrain.rotator} — drives to the Plan tab's target PA automatically"
            } else {
                "No rotator device — Ekos reports the diff below so you can turn the camera by hand"
            },
            checked = state.rotatorAutoControl,
            onToggle = { ctrl.setRotatorAutoControl(!state.rotatorAutoControl) },
        )
        if (state.rotatorAutoControl) {
            val current = state.wireRotatorCurrentPA
            val target = state.wireRotatorTargetPA
            val threshold = state.wireRotatorThreshold
            if (current != null && target != null) {
                val diff = kotlin.math.abs(target - current)
                val within = threshold != null && diff <= threshold
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                    TextC("Now ${"%.1f".format(current)}° → Target ${"%.1f".format(target)}°", style = t.Mono13, color = c.text)
                    Spacer(Modifier.weight(1f))
                    TextC(
                        if (within) "within threshold" else "Δ${"%.1f".format(diff)}°",
                        style = t.Caption,
                        color = if (within) c.accent else c.textMuted,
                    )
                }
            } else {
                TextC("waiting for a solve…", style = t.Caption, color = c.textFaint, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
        }
        // Scheduler::pause() isn't reachable over the wire (not in message.cpp, not Q_INVOKABLE),
        // so Stop/Start stands in: Stop marks the job ABORTED, Start resumes its progress when
        // kcfg_RememberJobProgress is on.
        if (state.schedulerRunning) {
            Spacer(Modifier.height(4.dp))
            TextC(
                "A sequence is running — adjusting now will interrupt the current exposure. " +
                    "Stop Scheduler (Sequence tab) pauses it cleanly, Start resumes.",
                style = t.Caption, color = c.textFaint,
            )
        }
    }
}

/** Opens the Align settings sheet (exposure, gain, filter, binning, solver accuracy). */
@Composable
private fun AlignSettingsCard(state: AppState, ctrl: SessionController) {
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

/** Opens the Guide settings sheet (accuracy threshold, dither, reuse calibration). */
@Composable
private fun GuideSettingsCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val g = state.wireGuideSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.GUIDE_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.CrosshairSimple, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Guide settings", style = t.Body135, color = c.text)
        TextC(
            if (g == null) "loading…" else "±${"%.0f".format(g.guiderAccuracyThreshold)}\" · ${if (g.kcfg_DitherEnabled) "dither on" else "dither off"}",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/** Opens the Focus settings sheet (exposure, gain, filter, backlash, autofocus algorithm). */
@Composable
private fun FocusSettingsCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val f = state.wireFocusSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.FOCUS_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.ArrowsInLineHorizontal, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Focus settings", style = t.Body135, color = c.text)
        TextC(
            if (f == null) "loading…" else "${f.focusFilter} · ${f.focusAlgorithm}",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/** Opens the Mount settings sheet (meridian flip, limits, auto-park). */
@Composable
private fun MountSettingsCard(state: AppState, ctrl: SessionController) {
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


/** Opens the polar alignment sheet; shows Ekos's current polar-align stage. */
@Composable
private fun PaCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.PA) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Polar align", style = t.Body135, color = c.text)
        TextC(
            state.wirePolarStage ?: if (state.polarRunning) "running…" else "not started",
            style = t.MonoMicro, color = if (state.polarRunning) c.warn else c.textFaint,
        )
    }
}
