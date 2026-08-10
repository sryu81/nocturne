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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.SheetType
import com.nocturne.session.contractJob
import com.nocturne.session.expRemain
import com.nocturne.session.fNow
import com.nocturne.session.doneHM
import com.nocturne.session.filterBreakdown
import com.nocturne.session.flipIn
import com.nocturne.session.formatSiteTime
import com.nocturne.session.mountPierSideLabel
import com.nocturne.session.plannedHM
import com.nocturne.session.realNightWindow
import com.nocturne.session.realNowFraction
import com.nocturne.session.rms
import com.nocturne.session.totalDone
import com.nocturne.session.totalPlannedSec
import com.nocturne.session.totalSubs
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
    if (state.contractJob == null) {
        IdleSessionCard(state, modifier)
        return
    }
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { NightArcCard(state) },
            TabItem(full = true) { SubPreview(state, ctrl) },
            TabItem { StatsRow(state, ctrl) },
            TabItem(full = true) { FlipBanner(state, ctrl) },
            TabItem(full = true) { SkySite() },
            TabItem(full = true) {
                NocturneButton(
                    text = "End session & review",
                    onClick = { ctrl.endSession() },
                    icon = Phosphor.FlagCheckered,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                )
            },
        ),
    )
}

@Composable
private fun IdleSessionCard(state: SimState, modifier: Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier.fillMaxWidth().padding(NocturneTheme.spacing.s4)) {
        Card {
            TextC("No job running — add a target to the sequence from the Plan tab.", style = t.Body13, color = c.textMuted)
            if (state.mountParked) {
                Spacer(Modifier.height(6.dp))
                TextC("Mount parked · cooler off", style = t.MonoMicro, color = c.textFaint)
            }
        }
    }
}

/**
 * Real-rig dusk/dawn (`state.realNightWindow`, from `astro_get_almanac`/`astro_get_location`,
 * M2026-08) replace the "21:48 → 04:12" literals once they've arrived; the arc's "now" dot uses
 * `state.realNowFraction` — **null, not clamped**, whenever real "now" falls outside tonight's
 * dusk-dawn span (daytime, or a stale window), so an honest message is shown instead of a wrong
 * dot position. The "flip" tick has no real position to show (see `FlipBanner`'s own doc — no
 * real flip-time data exists anywhere) and is simply omitted under a real rig rather than left at
 * its old fixed pixel offset. `plannedFraction`/duration text use real block totals
 * ([SequenceJob.totalPlannedSec]/[SequenceJob.totalDoneSec]) once [SequenceJob.synced]; the
 * per-sub "X left" countdown the simulator shows has no real equivalent (blocked on the Media
 * channel, same M4 gap as `StatsRow`/`SubPreview`) so it's dropped, not fabricated, in that case.
 */
@Composable
private fun NightArcCard(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val job = state.contractJob
    val realProgress = job != null && job.synced
    val real = state.isRealRig
    val window = if (real) state.realNightWindow else null
    val nowFrac = if (real) state.realNowFraction else null
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("NIGHT ARC", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC(
                if (window != null) "${state.formatSiteTime(window.first)} → ${state.formatSiteTime(window.second)}" else "21:48 → 04:12",
                style = t.MonoMicro, color = c.textMuted,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (real && nowFrac == null) {
            Box(Modifier.fillMaxWidth().height(176.dp), contentAlignment = Alignment.Center) {
                TextC(
                    if (window == null) {
                        "fetching real dusk/dawn…"
                    } else {
                        "outside tonight's dark window (${state.formatSiteTime(window.first)}–${state.formatSiteTime(window.second)})"
                    },
                    style = t.Body13, color = c.textMuted,
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(176.dp),
            ) {
                val shotFraction = nowFrac ?: state.fNow
                val plannedFraction = if (realProgress && window != null) {
                    val spanSec = (window.second.epochSecond - window.first.epochSecond).coerceAtLeast(1)
                    (job!!.totalPlannedSec.toDouble() / spanSec).coerceIn(0.0, 1.0)
                } else {
                    0.78
                }
                NightArc(
                    shotFraction = shotFraction,
                    plannedFraction = plannedFraction,
                    nowFraction = shotFraction,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextC(if (realProgress) job!!.doneHM else "1:00", style = t.Mono34, color = c.text)
                    TextC(
                        if (realProgress) "OF ${job!!.plannedHM} INTEGRATED" else "OF 3:20 INTEGRATED",
                        style = t.Caption10, color = c.textFaint,
                    )
                    TextC(
                        if (realProgress) "sub ${job!!.totalDone}/${job.totalSubs}" else "sub 13/40 · ${state.expRemain} left",
                        style = t.Mono115, color = c.accent400,
                    )
                }
                TextC(
                    if (window != null) state.formatSiteTime(window.first) else "21:48", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp),
                )
                TextC(
                    if (window != null) state.formatSiteTime(window.second) else "04:12", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp),
                )
                if (!real) {
                    TextC(
                        "flip", style = t.MonoMicro, color = c.warn,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 62.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextC(
                if (realProgress) job!!.filterBreakdown else "Ha 12/40 · OIII 0/30",
                style = t.MonoMicro, color = c.textMuted, modifier = Modifier.weight(1f),
            )
            TextC("dither in 2", style = t.MonoMicro, color = c.textFaint)
        }
    }
}

/**
 * Real connection (`state.isRealRig`): a real `capture_preview` is triggerable but the resulting
 * image/HFR/ADU numbers arrive over the Media channel, which doesn't exist yet (M4, `MediaChannel`
 * is a stub) — so the honest thing to show is that gap, not a fabricated readout. Simulator keeps
 * the canned fixture text, unchanged. Same idiom as `ControlsScreen.kt`'s `benchSnapLabel`.
 */
private fun subPreviewLine(real: Boolean, fixtureText: String): String =
    if (real) "no live preview yet — needs the Media channel, M4" else fixtureText

@Composable
private fun SubPreview(state: SimState, ctrl: SessionController) {
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
            TextC(
                subPreviewLine(state.isRealRig, "★ 1 482 · HFR 2.31 · ADU 1 093"),
                style = t.MonoMid, color = c.textDim, modifier = Modifier.weight(1f),
            )
            IconBtn(icon = Phosphor.ArrowsOut, onClick = ctrl::openSubPreview, size = 30)
        }
    }
}

/** Full-screen sub preview — tap anywhere or the system back button dismisses it. */
@Composable
fun SubPreviewOverlay(state: SimState, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .fillMaxSize()
            .background(c.surfaceDeep)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onDismiss,
            ),
    ) {
        HatchBg(Modifier.fillMaxSize())
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        ) {
            ChipTag("SUB 013", accent = true)
            Spacer(Modifier.width(6.dp))
            ChipTag("Ha 300s g100", accent = false)
        }
        IconBtn(
            icon = Phosphor.X,
            onClick = onDismiss,
            size = 34,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
        TextC(
            subPreviewLine(state.isRealRig, "★ 1 482 · HFR 2.31 · ADU 1 093"),
            style = t.Mono17, color = c.textDim,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
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

/** No real HFR/RMS/SNR numbers exist anywhere on the wire yet — genuinely blocked on the Media channel (M4). */
@Composable
private fun StatsRow(state: SimState, ctrl: SessionController) {
    val real = state.isRealRig
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MiniStat(
            label = "HFR",
            value = if (real) "—" else "2.31",
            sub = if (real) "not available (M4)" else "▼ 0.04",
            subColor = if (real) NocturneTheme.colors.textFaint else NocturneTheme.colors.ok,
            onClick = { ctrl.openSheet(SheetType.FOCUS) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "RMS",
            value = if (real) "—" else String.format("%.2f", state.rms) + "\"",
            sub = if (real) "not available (M4)" else null,
            subColor = NocturneTheme.colors.textFaint,
            onClick = { ctrl.openSheet(SheetType.GUIDE) },
            modifier = Modifier.weight(1f),
            content = if (real) null else {
                {
                    Spacer(Modifier.height(4.dp))
                    MiniTrace(t = state.t, modifier = Modifier.fillMaxWidth().height(16.dp))
                }
            },
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "SNR",
            value = if (real) "—" else "41.2",
            sub = if (real) "not available (M4)" else "bkg 1 093",
            subColor = NocturneTheme.colors.textMuted,
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

/**
 * Real rig: no wire RPC exists for a manual flip trigger at all (`executeMeridianFlip` is an
 * enable/disable *setting*, not a command — see `EkosRemoteController`'s doc on
 * `requestFlipNow`/`requestDeferFlip`, which have no real override and would silently no-op on a
 * real rig if left tappable) — so FLIP NOW/DEFER are disabled with an honest explanation instead
 * of a fake countdown. Real pier side/auto-flip state shown in their place (no T-minus number
 * exists on the wire — would need real RA + local sidereal time, neither modeled anywhere today).
 */
@Composable
private fun FlipBanner(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val real = state.isRealRig
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
            if (real) {
                val pier = state.mountPierSideLabel ?: "unknown"
                val autoFlip = when (state.wireMountSettings?.executeMeridianFlip) {
                    true -> "auto-flip on"
                    false -> "auto-flip off"
                    null -> "auto-flip ?"
                }
                TextC("pier $pier · $autoFlip", style = t.Mono15, color = c.text)
                TextC(
                    "no real trigger — flip is an enable/disable setting, not a manual command",
                    style = t.MonoMicro, color = c.textFaint,
                )
            } else {
                TextC("${state.flipIn} · auto, pauses guiding", style = t.Mono15, color = c.text)
            }
        }
        Box(
            Modifier
                .border(1.dp, c.warn.copy(alpha = if (real) 0.25f else 1f), RoundedCornerShape(8.dp))
                .let { if (real) it else it.clickable { ctrl.requestFlipNow() } }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            TextC("FLIP NOW", style = t.Button12, color = c.warn.copy(alpha = if (real) 0.4f else 1f))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .border(1.dp, c.warn.copy(alpha = if (real) 0.15f else 0.5f), RoundedCornerShape(8.dp))
                .let { if (real) it else it.clickable { ctrl.requestDeferFlip() } }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            TextC("DEFER", style = t.Button12, color = c.warn.copy(alpha = if (real) 0.4f else 1f))
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
