package com.nocturne.ui.sequence

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.BLOCKS
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.missing
import com.nocturne.session.ready
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

@Composable
fun SequenceScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { NightPlanBar() },
            TabItem(full = true) { BlocksList(state, ctrl) },
            TabItem(full = true) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .border(1.dp, Color(0xFFE9E9ED).copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .clickable { }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Phosphor.Icon(Phosphor.Plus, size = 15.dp, tint = NocturneTheme.colors.textMuted)
                        Spacer(Modifier.width(8.dp))
                        TextC("Add block", style = NocturneTheme.type.Button12, color = NocturneTheme.colors.textMuted)
                    }
                }
            },
            TabItem(full = true) { StartButton(state, ctrl) },
            TabItem(full = true) {
                if (!state.ready) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clickable { }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Phosphor.Icon(Phosphor.Plugs, size = 15.dp, tint = NocturneTheme.colors.warn)
                            Spacer(Modifier.width(7.dp))
                            TextC("Fix in Gear — a session needs mount + camera", style = NocturneTheme.type.Button12, color = NocturneTheme.colors.warn)
                        }
                    }
                }
            },
        ),
    )
}

@Composable
private fun NightPlanBar() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("NIGHT PLAN", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("21:48 → 04:12", style = t.MonoMicro, color = c.textMuted)
        }
        Spacer(Modifier.height(8.4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Color.Transparent, RoundedCornerShape(4.dp)),
        ) {
            PlanSeg(0.12f, Color(0xFF5D5294))
            PlanSeg(0.31f, c.accent)
            PlanSeg(0.06f, c.warn.copy(alpha = 0.55f))
            PlanSeg(0.33f, c.accentMuted)
            PlanSeg(0.18f, c.accent800)
        }
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            TextC("cal", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("Ha · now", style = t.MonoMicro, color = c.text, modifier = Modifier.weight(1f))
            TextC("flip", style = t.MonoMicro, color = c.warn, modifier = Modifier.weight(1f))
            TextC("OIII", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("SII", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PlanSeg(width: Float, color: Color) {
    Box(
        Modifier
            .weight(width)
            .height(26.dp)
            .background(color),
    )
}

@Composable
private fun BlocksList(state: SimState, ctrl: SessionController) {
    Column(Modifier.fillMaxWidth()) {
        BLOCKS.forEachIndexed { i, b ->
            val open = state.openBlock == i
            BlockCard(
                filter = b.filter,
                spec = b.spec,
                meta = b.meta,
                pct = b.pct,
                open = open,
                onToggle = { ctrl.toggleBlock(i) },
            )
            Spacer(Modifier.height(11.2.dp))
        }
    }
}

@Composable
private fun BlockCard(
    filter: String,
    spec: String,
    meta: String,
    pct: Float,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val first = filter == "Ha"
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.DotsSixVertical, size = 17.dp, tint = c.neutral700)
            Spacer(Modifier.width(11.2.dp))
            Box(
                Modifier
                    .background(
                        if (first) c.accent.copy(alpha = 0.2f) else c.divider.copy(alpha = 0.4f),
                        RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                TextC(filter, style = t.MonoSmall, color = if (first) c.accent400 else c.textMuted)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC(spec, style = t.Mono14, color = c.text)
                TextC(meta, style = t.MonoMicro, color = c.textFaint)
            }
            Box(
                Modifier
                    .width(30.dp)
                    .height(30.dp)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(if (open) Phosphor.CaretUp else Phosphor.CaretDown, size = 15.dp, tint = c.textMuted)
            }
        }
        Spacer(Modifier.height(9.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(c.divider.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(4.dp)
                    .background(c.accent, RoundedCornerShape(2.dp)),
            )
        }
        if (open) {
            Spacer(Modifier.height(9.dp))
            HDivider()
            Spacer(Modifier.height(9.dp))
            BlockDetails()
        }
    }
}

@Composable
private fun BlockDetails() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth()) {
        Detail("EXPOSURE", "300 s", Modifier.weight(1f))
        Detail("GAIN / OFF", "100 / 50", Modifier.weight(1f))
        Detail("BINNING", "1×1", Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Dither every", style = t.Caption, color = c.textMuted, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .border(1.dp, c.divider, RoundedCornerShape(10.dp)),
        ) {
            listOf(0, 1, 2, 3).forEach { i ->
                val sel = i == 1
                Box(
                    Modifier
                        .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    TextC(listOf("1", "2", "3", "5")[i], style = t.MonoSmall, color = if (sel) c.accent400 else c.textMuted)
                }
            }
        }
        Spacer(Modifier.width(8.4.dp))
        TextC("1.5 px", style = t.MonoSmall, color = c.textFaint)
    }
    Spacer(Modifier.height(8.4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Autofocus", style = t.Caption, color = c.textMuted, modifier = Modifier.weight(1f))
        TextC("45 min · 1.0 °C · filter change", style = t.MonoSmall, color = c.textDim)
    }
}

@Composable
private fun Detail(label: String, value: String, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier) {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        TextC(value, style = t.Mono14, color = c.text)
    }
}

@Composable
private fun StartButton(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val ready = state.ready
    val running = state.running
    val color = if (ready) c.accent else c.neutral700
    val label = when {
        !ready -> "Blocked — connect ${state.missing}"
        running -> "Pause after this sub"
        else -> "Start sequence"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickable { ctrl.toggleRun() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.Pause, size = 17.dp, tint = color)
            Spacer(Modifier.width(8.dp))
            TextC(label, style = t.Button14, color = color)
        }
    }
}
