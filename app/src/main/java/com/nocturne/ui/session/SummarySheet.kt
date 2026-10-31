package com.nocturne.ui.session

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.doneSpec
import com.nocturne.session.endedJob
import com.nocturne.session.pct
import com.nocturne.session.keepCount
import com.nocturne.session.rejectCount
import com.nocturne.session.medHfr
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Summary ──────────────────────────────────────────────────────────────

@Composable
internal fun SummarySheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val canResume = state.lastEndedJobId != null
    val hasNext = state.jobs.any { it.id != state.lastEndedJobId }
    val job = state.endedJob
    val barColors = listOf(Color(0xFF9184D9), Color(0xFF796CBF), Color(0xFF5D5294), c.accentMuted, c.accent800)
    Column {
        // From the Room-backed frame table.
        Row(Modifier.fillMaxWidth()) {
            SumStat("KEPT", state.keepCount.toString(), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("DISCARDED", state.rejectCount.toString(), Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            SumStat("MED HFR", state.medHfr?.let { "%.2f".format(it) } ?: "—", Modifier.weight(1f))
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
        // Built from this session's notifications.
        TextC("SESSION EVENTS", style = t.MicroLabel, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        if (state.wireNotifications.isEmpty()) {
            TextC("No alerts this session.", style = t.MonoSmall, color = c.neutral500)
        } else {
            state.wireNotifications.take(10).forEach {
                TextC("${it.time} · ${it.message}", style = t.MonoSmall, color = c.neutral500)
            }
        }
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
internal fun SumBar(label: String, frac: Float, color: Color, value: String) {
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
