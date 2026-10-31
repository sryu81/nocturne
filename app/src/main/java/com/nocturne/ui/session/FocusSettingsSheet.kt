package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.focRange
import com.nocturne.protocol.tuning
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Focus settings (curated subset) ─────────────────────────────────────────

/** `opsfocusmechanics.ui` combo (`focusWalk`). "Classic" is Linear-only but the combo always offers it. */
internal val FOCUS_WALK_CYCLE = listOf("Classic", "Fixed Steps", "CFZ Shuffle")

/** `opsfocusprocess.ui` combo (`focusDetection`). */
internal val FOCUS_DETECTION_CYCLE = listOf("Gradient", "Centroid", "Threshold", "SEP", "Bahtinov")

/** `opsfocusprocess.ui` combo (`focusCurveFit`). */
internal val FOCUS_CURVE_FIT_CYCLE = listOf("Quadratic", "Hyperbola", "Parabola", "Gaussian")

/** `opsfocusprocess.ui` combo (`focusStarMeasure`). */
internal val FOCUS_STAR_MEASURE_CYCLE =
    listOf("HFR", "HFR Adj", "FWHM", "# Stars", "Fourier", "StdDev", "Sobel", "Laplassian", "Canny")

/**
 * Everyday subset of Ekos's Focus settings, grouped as in Ekos: **Mechanics**
 * (`opsfocusmechanics.ui`) and **Process** (`opsfocusprocess.ui`). Expert tuning (mosaic, masking,
 * adaptive, CFZ calculator, donut…) is left out. Exposure/gain/filter/binning are on the Controls
 * tab's Focuser card. `absTicksSpin` isn't shown: no command sets an absolute position.
 * [AppState.wireFocusSettings] is null until the first `focus_get_all_settings` reply.
 */
@Composable
internal fun FocusSettingsSheet(state: AppState, ctrl: SessionController) {
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
