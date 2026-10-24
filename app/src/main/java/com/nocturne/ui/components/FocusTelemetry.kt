package com.nocturne.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nocturne.session.AppState
import com.nocturne.ui.theme.NocturneTheme

/**
 * Live autofocus progress — the V-curve of every measured focus frame (position vs HFR, from the
 * stock `new_focus_state` `{hfr, pos}` push), Ekos's own plot title (curve fit / R² / scan step),
 * and the latest/best frame numbers. Shown in the Focus sheet and the Controls tab's focuser card.
 */
@Composable
fun FocusCurve(state: AppState, chartHeight: Dp, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val samples = state.focusSamples
    Column(modifier) {
        if (samples.isEmpty()) {
            TextC("No focus data yet — run autofocus to see the V-curve.", style = t.Caption, color = c.textMuted)
            return@Column
        }
        state.focusCurveTitle?.let {
            TextC(it, style = t.MonoMicro, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
        }
        val (lo, hi) = focusHfrRange(samples)
        Row(Modifier.fillMaxWidth()) {
            TextC("HFR %.2f–%.2f".format(lo, hi), style = t.MonoMicro, color = c.textFaint)
        }
        FocusVCurveChart(
            samples = samples,
            pointColor = c.info,
            bestColor = c.ok,
            latestColor = c.accent,
            modifier = Modifier.fillMaxWidth().height(chartHeight),
        )
        if (samples.all { it.pos >= 0 }) {
            Row(Modifier.fillMaxWidth()) {
                TextC("${samples.minOf { it.pos }}", style = t.MonoMicro, color = c.textFaint)
                Spacer(Modifier.weight(1f))
                TextC("position", style = t.MonoMicro, color = c.textFaint)
                Spacer(Modifier.weight(1f))
                TextC("${samples.maxOf { it.pos }}", style = t.MonoMicro, color = c.textFaint)
            }
        }
        Spacer(Modifier.height(4.dp))
        val last = samples.last()
        val best = samples.minBy { it.hfr }
        fun posText(p: Int) = if (p >= 0) "@ $p" else ""
        Row(Modifier.fillMaxWidth()) {
            TextC("◯ now %.2f %s".format(last.hfr, posText(last.pos)).trim(), style = t.MonoMicro, color = c.accent)
            Spacer(Modifier.weight(1f))
            TextC("● best %.2f %s".format(best.hfr, posText(best.pos)).trim(), style = t.MonoMicro, color = c.ok)
            Spacer(Modifier.weight(1f))
            TextC("${samples.size} frames", style = t.MonoMicro, color = c.textFaint)
        }
    }
}
