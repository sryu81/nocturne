package com.nocturne.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nocturne.session.AppState
import com.nocturne.session.guideRmsTotal
import com.nocturne.ui.theme.NocturneTheme

/**
 * Live guiding numbers — RMS per axis + total (Ekos's running sigma), the RA/DEC drift trace, and
 * SNR/star count when the detector reports them. All from the fork's guide-stats push
 * (docs/FORK-BACKLOG.md §1); shown in the Guide sheet and the Controls tab's guide card.
 * The dashed ± lines are Capture's guide-deviation abort limit, only when that guard is enabled.
 */
@Composable
fun GuideTelemetry(state: AppState, chartHeight: Dp, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val samples = state.guideSamples
    Column(modifier) {
        if (samples.isEmpty() && state.guideRmsRA == null) {
            TextC("No guide data yet — start guiding to see RMS and drift.", style = t.Caption, color = c.textMuted)
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            RmsCell("RA", state.guideRmsRA, Modifier.weight(1f))
            RmsCell("DEC", state.guideRmsDE, Modifier.weight(1f))
            RmsCell("TOTAL", state.guideRmsTotal, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        val ra = samples.map { it.ra }
        val de = samples.map { it.de }
        val threshold = state.wireCaptureSettings?.takeIf { it.enforceGuideDeviation }?.guideDeviation
        GuideDriftChart(
            ra = ra,
            de = de,
            raColor = c.accent,
            deColor = c.info,
            thresholdArcsec = threshold,
            modifier = Modifier.fillMaxWidth().height(chartHeight),
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            TextC("— RA", style = t.MonoMicro, color = c.accent)
            Spacer(Modifier.width(10.dp))
            TextC("- - DEC", style = t.MonoMicro, color = c.info)
            Spacer(Modifier.weight(1f))
            val last = samples.lastOrNull()
            val extras = listOfNotNull(
                "scale ±%.1f\"".format(guideDriftScale(ra, de, threshold)),
                last?.let { "now %+.2f\" / %+.2f\"".format(it.ra, it.de) },
                state.guideSnr?.let { "SNR %.0f".format(it) },
                state.guideStarCount?.let { "$it ★" },
            )
            TextC(extras.joinToString(" · "), style = t.MonoMicro, color = c.textFaint)
        }
    }
}

@Composable
private fun RmsCell(label: String, value: Double?, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier) {
        TextC("RMS $label", style = t.MicroLabel, color = c.textMuted)
        TextC(value?.let { "%.2f\"".format(it) } ?: "—", style = t.Mono17, color = c.text)
    }
}
