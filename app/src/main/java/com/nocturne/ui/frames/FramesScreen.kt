package com.nocturne.ui.frames

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.frames
import com.nocturne.session.keepCount
import com.nocturne.session.rejectCount
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.HfrRunChart
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

private val HFR_RUN_POINTS = listOf(
    44f, 41f, 38f, 34f, 30f, 18f, 14f, 31f, 36f, 33f, 30f, 26f, 24f, 15f, 12f, 29f, 34f,
)

@Composable
fun FramesScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { FilterChips(state) },
            TabItem(full = true) { FrameGrid(state, ctrl) },
            TabItem(full = true) { HfrRunCard() },
        ),
    )
}

@Composable
private fun FilterChips(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .background(c.accent.copy(alpha = 0.16f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            TextC("All 42", style = t.Button12, color = c.accent400)
        }
        Spacer(Modifier.width(5.6.dp))
        OutlinePill("${state.keepCount} keep", Modifier)
        Spacer(Modifier.width(5.6.dp))
        OutlinePill("${state.rejectCount} cut", Modifier)
        Spacer(Modifier.weight(1f))
        TextC("sort HFR ↓", style = t.MonoMicro, color = c.textFaint)
    }
}

@Composable
private fun OutlinePill(text: String, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        modifier
            .border(1.dp, c.divider, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        TextC(text, style = t.MonoSmall, color = c.textMuted)
    }
}

@Composable
private fun FrameGrid(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth()) {
        state.frames.chunked(3).forEachIndexed { rowIdx, rowFrames ->
            Column(Modifier.weight(1f)) {
                rowFrames.forEach { f ->
                    FrameCell(f, onClick = { ctrl.toggleCut(f.id) }, modifier = Modifier.fillMaxWidth())
                    if (f != rowFrames.last()) Spacer(Modifier.height(6.dp))
                }
            }
            if (rowIdx < 3) Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun FrameCell(
    frame: com.nocturne.session.Frame,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val cut = frame.cut
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surfaceDeep)
            .border(
                1.dp,
                if (cut) c.danger.copy(alpha = 0.5f) else c.accent.copy(alpha = 0.45f),
                RoundedCornerShape(10.dp),
            )
            .alpha(if (cut) 0.42f else 1f)
            .clickable(onClick = onClick),
    ) {
        HatchBg(Modifier.fillMaxSize(), color = Color(0xFF2B2D38))
        TextC(
            frame.id, style = t.MonoMicro, color = c.textFaint,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 4.dp),
        )
        TextC(
            String.format("%.2f", frame.hfr), style = t.MonoMid,
            color = if (frame.hfr > 2.8) c.danger else c.textDim,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 4.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 3.dp)
                .size(18.dp)
                .background(if (cut) c.danger else c.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Phosphor.Icon(
                if (cut) Phosphor.XFill else Phosphor.CheckFill,
                size = 11.dp,
                tint = c.surfaceDeep,
            )
        }
    }
}

@Composable
private fun HfrRunCard() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("HFR ACROSS THE RUN", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("cut > 2.80", style = t.MonoMicro, color = c.textMuted)
        }
        Spacer(Modifier.height(9.dp))
        HfrRunChart(
            values = HFR_RUN_POINTS,
            modifier = Modifier.fillMaxWidth().height(60.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            TextC("22:10", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("seeing spike 01:04", style = t.MonoMicro, color = c.danger)
            TextC("03:40", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.weight(1f))
        }
    }
}
