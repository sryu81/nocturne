package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.ui.components.BtnStyle
import com.nocturne.protocol.GUIDE_BOX_SIZES
import com.nocturne.protocol.GuideAxis
import com.nocturne.protocol.GuideTuning
import com.nocturne.protocol.WireGuideSettings
import com.nocturne.protocol.axisEnabled
import com.nocturne.protocol.tuning
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Guide settings (curated subset) ─────────────────────────────────────────

/**
 * Curated subset of Ekos's Guide tab: accuracy threshold, dither, frame (dark/subframe/auto
 * star/lost star) and calibration. Exposure/gain/binning are on the Controls tab's guide preview.
 * [AppState.wireGuideSettings] is null until the first `guide_get_all_settings` reply.
 */
@Composable
internal fun GuideSettingsSheet(state: AppState, ctrl: SessionController) {
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

        GuideTuningSection(g, ctrl)
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

/** Label, unit and decimals of each [GuideTuning] row, in the order they are shown. */
internal val GUIDE_TUNING_ROWS = listOf(
    Triple(GuideTuning.RA_GAIN, "RA aggressiveness", "" to 2),
    Triple(GuideTuning.DEC_GAIN, "DEC aggressiveness", "" to 2),
    Triple(GuideTuning.RA_INTEGRAL, "RA integral gain", "" to 2),
    Triple(GuideTuning.DEC_INTEGRAL, "DEC integral gain", "" to 2),
    Triple(GuideTuning.RA_MIN_PULSE, "RA minimum pulse", "\"" to 2),
    Triple(GuideTuning.DEC_MIN_PULSE, "DEC minimum pulse", "\"" to 2),
    Triple(GuideTuning.RA_MAX_PULSE, "RA maximum pulse", "\"" to 0),
    Triple(GuideTuning.DEC_MAX_PULSE, "DEC maximum pulse", "\"" to 0),
)

/**
 * Control loop of the internal guider. Ekos re-reads these on every guide cycle, so a change acts
 * on the running guider at once. "Revert" puts back what they were when the sheet opened.
 */
@Composable
internal fun GuideTuningSection(g: WireGuideSettings, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val opened = remember { g }
    TextC("GUIDING TUNING", style = t.MicroLabel, color = c.textMuted)
    Spacer(Modifier.height(6.dp))
    TextC(
        "RA swinging back and forth, RMS climbing? The guider is over-correcting: lower the RA " +
            "aggressiveness a step (0.70 → 0.50) and watch the trace settle. Max pulse caps one correction.",
        style = t.MonoMicro, color = c.textMuted,
    )
    Spacer(Modifier.height(10.dp))
    GuideAxis.values().forEach { axis ->
        GuideSwitch("${axis.name} guiding", "correct this axis", g.axisEnabled(axis)) {
            ctrl.setGuideAxisEnabled(axis, !g.axisEnabled(axis))
        }
        Spacer(Modifier.height(8.4.dp))
    }
    GUIDE_TUNING_ROWS.forEach { (param, label, unitDecimals) ->
        FieldLabel(label)
        Spacer(Modifier.height(5.dp))
        DecimalField(g.tuning(param), unitDecimals.first, unitDecimals.second) { ctrl.setGuideTuning(param, it) }
        Spacer(Modifier.height(8.4.dp))
    }
    val changed = GuideTuning.values().any { opened.tuning(it) != g.tuning(it) } ||
        GuideAxis.values().any { opened.axisEnabled(it) != g.axisEnabled(it) }
    NocturneButton(
        text = "Revert to values when opened",
        onClick = {
            GuideTuning.values().forEach { if (opened.tuning(it) != g.tuning(it)) ctrl.setGuideTuning(it, opened.tuning(it)) }
            GuideAxis.values().forEach { if (opened.axisEnabled(it) != g.axisEnabled(it)) ctrl.setGuideAxisEnabled(it, opened.axisEnabled(it)) }
        },
        style = com.nocturne.ui.components.BtnStyle.OUTLINE,
        enabled = changed,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    )
}

@Composable
internal fun GuideSwitch(label: String, sub: String, checked: Boolean, onToggle: () -> Unit) {
    SwitchRow(
        label = label,
        sub = sub,
        checked = checked,
        onToggle = onToggle,
        modifier = Modifier.fillMaxWidth().background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
    )
}
