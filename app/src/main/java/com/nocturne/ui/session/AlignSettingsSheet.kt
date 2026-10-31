package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.session.realFilterNames
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.alignCameraDevice
import com.nocturne.session.gainSpecialValue
import com.nocturne.ui.components.GainField
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Align settings (curated subset) ─────────────────────────────────────────

/** Ekos's alignBinning combo options; the wire value is a string like `"1x1"`, not a number. */
internal val ALIGN_BINNING_OPTIONS = listOf("1x1", "2x2", "3x3", "4x4")

/**
 * Curated subset of Ekos's Align tab: exposure, gain, filter, binning, solver accuracy.
 * [AppState.wireAlignSettings] is null until the first `align_get_all_settings` reply.
 */
@Composable
internal fun AlignSettingsSheet(state: AppState, ctrl: SessionController) {
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
internal fun CycleChip(value: String, enabled: Boolean = true, onTap: () -> Unit) {
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
