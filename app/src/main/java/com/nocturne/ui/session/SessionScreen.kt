package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.SheetType
import com.nocturne.session.expRemain
import com.nocturne.session.fNow
import com.nocturne.session.flipIn
import com.nocturne.session.rms
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.MiniTrace
import com.nocturne.ui.components.NightArc
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

@Composable
fun SessionScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { NightArcCard(state) },
            TabItem(full = true) { SubPreview() },
            TabItem { StatsRow(state, ctrl) },
            TabItem(full = true) { FlipBanner(state) },
            TabItem(full = true) { SkySite() },
            TabItem(full = true) {
                NocturneButton(
                    text = "End session & review",
                    onClick = { ctrl.openSheet(SheetType.SUMMARY) },
                    icon = Phosphor.FlagCheckered,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                )
            },
        ),
    )
}

@Composable
private fun NightArcCard(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("NIGHT ARC", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("21:48 → 04:12", style = t.MonoMicro, color = c.textMuted)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(176.dp),
        ) {
            NightArc(
                shotFraction = state.fNow,
                plannedFraction = 0.78,
                nowFraction = state.fNow,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextC("1:00", style = t.Mono34, color = c.text)
                TextC("OF 3:20 INTEGRATED", style = t.Caption10, color = c.textFaint)
                TextC(
                    "sub 13/40 · ${state.expRemain} left",
                    style = t.Mono115, color = c.accent400,
                )
            }
            TextC(
                "21:48", style = t.MonoMicro, color = c.textFaint,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp),
            )
            TextC(
                "04:12", style = t.MonoMicro, color = c.textFaint,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp),
            )
            TextC(
                "flip", style = t.MonoMicro, color = c.warn,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 62.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            TextC("Ha 12/40 · OIII 0/30", style = t.MonoMicro, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC("dither in 2", style = t.MonoMicro, color = c.textFaint)
        }
    }
}

@Composable
private fun SubPreview() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceDeep)
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        HatchBg(Modifier.fillMaxSize())
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            ChipTag("SUB 013", accent = true)
            Spacer(Modifier.width(6.dp))
            ChipTag("Ha 300s g100", accent = false)
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextC("★ 1 482 · HFR 2.31 · ADU 1 093", style = t.MonoMid, color = c.textDim, modifier = Modifier.weight(1f))
            IconBtn(icon = Phosphor.ArrowsOut, onClick = {}, size = 30)
        }
    }
}

@Composable
private fun ChipTag(text: String, accent: Boolean) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .background(c.bg.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
            .border(1.dp, if (accent) c.accent.copy(alpha = 0.5f) else c.divider, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        TextC(text, style = t.MonoMicro, color = if (accent) c.accent400 else c.neutral400)
    }
}

@Composable
private fun StatsRow(state: SimState, ctrl: SessionController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MiniStat(
            label = "HFR", value = "2.31", sub = "▼ 0.04", subColor = NocturneTheme.colors.ok,
            onClick = { ctrl.openSheet(SheetType.FOCUS) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "RMS", value = String.format("%.2f", state.rms) + "\"",
            onClick = { ctrl.openSheet(SheetType.GUIDE) },
            modifier = Modifier.weight(1f),
            content = {
                Spacer(Modifier.height(4.dp))
                MiniTrace(t = state.t, modifier = Modifier.fillMaxWidth().height(16.dp))
            },
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "SNR", value = "41.2", sub = "bkg 1 093", subColor = NocturneTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    subColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .height(88.dp)
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        TextC(value, style = t.Mono21, color = c.text)
        if (content != null) {
            content()
        } else if (sub != null) {
            TextC(sub, style = t.MonoMid, color = subColor ?: c.textMuted)
        }
    }
}

@Composable
private fun FlipBanner(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.warn.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
            .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(11.2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Phosphor.Icon(Phosphor.ArrowsClockwise, size = 18.dp, tint = c.warn)
        Spacer(Modifier.width(11.2.dp))
        Column(Modifier.weight(1f)) {
            TextC("MERIDIAN FLIP", style = t.MicroLabel, color = c.warn)
            TextC("${state.flipIn} · auto, pauses guiding", style = t.Mono15, color = c.text)
        }
        Box(
            Modifier
                .border(1.dp, c.warn.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable { }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            TextC("DEFER", style = t.Button12, color = c.warn)
        }
    }
}

@Composable
private fun SkySite() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("SKY & SITE", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("roof open · safe", style = t.MonoMicro, color = c.ok)
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            SkyStat("−3.1°", "AMB", Modifier.weight(1f))
            SkyStat("−5.4°", "DEW", Modifier.weight(1f))
            SkyStat("21.3", "SQM", Modifier.weight(1f))
            SkyStat("4%", "CLOUD", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SkyStat(value: String, label: String, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier) {
        TextC(value, style = t.Mono17, color = c.text)
        TextC(label, style = t.Caption10, color = c.textFaint)
    }
}
