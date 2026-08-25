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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.SheetType
import com.nocturne.session.contractJob
import com.nocturne.session.expRemain
import com.nocturne.session.fNow
import com.nocturne.session.doneHM
import com.nocturne.session.filterBreakdown
import com.nocturne.session.formatSiteTime
import com.nocturne.session.mountPierSideLabel
import com.nocturne.session.plannedHM
import com.nocturne.session.realCountdownToDusk
import com.nocturne.session.realNightWindow
import com.nocturne.session.realNowFraction
import com.nocturne.session.currentBlockIndex
import com.nocturne.session.totalDone
import com.nocturne.session.totalPlannedSec
import com.nocturne.session.totalSubs
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NightArc
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

@Composable
fun SessionScreen(
    state: AppState,
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
private fun IdleSessionCard(state: AppState, modifier: Modifier) {
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
 * real flip-time data exists anywhere) — this comment used to claim it was "simply omitted under
 * a real rig," which was never actually true (`NightArc` in `Charts.kt` had no parameter to gate
 * it at all and drew it at a hardcoded fixed fraction unconditionally, confirmed a real live bug
 * 2026-08-25 by user report). Now genuinely removed at the draw-call level instead of merely
 * claimed removed here. `plannedFraction`/duration text use real block totals
 * ([SequenceJob.totalPlannedSec]/[SequenceJob.totalDoneSec]) once [SequenceJob.synced]; the
 * per-sub "X left" countdown the simulator shows has no real equivalent (blocked on the Media
 * channel, same M4 gap as `StatsRow`/`SubPreview`) so it's dropped, not fabricated, in that case.
 *
 * **Real bug found live (2026-08-09)**: all of the above is `Instant.now()`-based, which Compose
 * has no reason to ever re-evaluate on its own — with no periodic trigger, the "now" position
 * silently freezes at whatever wall-clock time this composable last happened to recompose for any
 * *other* reason (a wire event). Confirmed live: a target shown "rising" hours after it had
 * actually set, because nothing had touched `state` since. The `tick` below exists purely to force
 * a redraw every 30s so real time keeps advancing on screen without needing an unrelated event.
 */
@Composable
private fun NightArcCard(state: AppState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val job = state.contractJob
    val realProgress = job != null && job.synced
    val window = state.realNightWindow
    val nowFrac = state.realNowFraction
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("NIGHT ARC", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC(
                if (window != null) "${state.formatSiteTime(window.first)} → ${state.formatSiteTime(window.second)}" else "21:48 → 04:12",
                style = t.MonoMicro, color = c.textMuted,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (nowFrac == null) {
            Box(Modifier.fillMaxWidth().height(176.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextC(
                        if (window == null) {
                            "fetching real dusk/dawn…"
                        } else {
                            "outside tonight's dark window (${state.formatSiteTime(window.first)}–${state.formatSiteTime(window.second)})"
                        },
                        style = t.Body13, color = c.textMuted,
                    )
                    state.realCountdownToDusk?.let { countdown ->
                        Spacer(Modifier.height(11.2.dp))
                        TextC(countdown, style = t.Mono34, color = c.ok)
                    }
                }
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
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextC(
                if (realProgress) job!!.filterBreakdown else "Ha 12/40 · OIII 0/30",
                style = t.MonoMicro, color = c.textMuted, modifier = Modifier.weight(1f),
            )
            // Was a bare "dither in 2" literal, no relation to anything real (found live, user
            // report, 2026-08-25) — real per-block `ditherEvery`/`doneCount` already exist
            // (block editor, EsqWriter) but nothing here ever read them. No real dither-fired
            // wire event exists to reset this against an actual dither (confirmed: EkosEvent has
            // no such case) — this is the same "every N subs" countdown Ekos itself computes
            // locally from doneCount, not a server-pushed status, so it's exact as long as
            // doneCount itself is (see doneCount's own M3 waterfall-fill-approximation caveat).
            TextC(
                if (realProgress) job!!.currentBlockIndex?.let { job.blocks[it] }?.let { b ->
                    when {
                        b.ditherEvery == null || b.ditherEvery <= 0 -> "dither off"
                        else -> "dither in ${b.ditherEvery - (b.doneCount % b.ditherEvery)}"
                    }
                } ?: "dither —" else "dither in 2",
                style = t.MonoMicro, color = c.textFaint,
            )
        }
    }
}

/**
 * Fallback line while no real frame has arrived yet — real ones do now (M4.2, `latestCaptureFrame`,
 * routed off `/media/ekos`'s `uuid == ""` frames). No per-sub index/HFR/ADU exists on the wire at
 * all (confirmed against source, docs/M4-plan.md) — the header's own real `exposure`/`gain` are
 * shown instead of a fabricated "SUB 013"-style counter once a frame lands.
 */
private const val SUB_PREVIEW_LINE = "no live preview yet — waiting on a real capture"

/** e.g. "300s · g100" from the frame header's own real fields — null pieces are dropped, not guessed. */
private fun AppState.captureFrameTag(): String? {
    val h = latestCaptureFrame?.header ?: return null
    return listOfNotNull(h.exposure?.let { "${it}s" }, h.gain?.let { "g$it" }).joinToString(" · ").ifBlank { null }
}

@Composable
private fun SubPreview(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val frame = state.latestCaptureFrame
    Box(
        Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceDeep)
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        MediaFramePreview(frame, Modifier.fillMaxSize())
        if (frame != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                ChipTag("CAPTURE", accent = true)
                state.captureFrameTag()?.let {
                    Spacer(Modifier.width(6.dp))
                    ChipTag(it, accent = false)
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (frame == null) {
                TextC(
                    SUB_PREVIEW_LINE,
                    style = t.MonoMid, color = c.textDim, modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconBtn(icon = Phosphor.ArrowsOut, onClick = ctrl::openSubPreview, size = 30)
        }
    }
}

/** Full-screen sub preview — tap anywhere or the system back button dismisses it. */
@Composable
fun SubPreviewOverlay(state: AppState, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val frame = state.latestCaptureFrame
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
        MediaFramePreview(frame, Modifier.fillMaxSize())
        if (frame != null) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            ) {
                ChipTag("CAPTURE", accent = true)
                state.captureFrameTag()?.let {
                    Spacer(Modifier.width(6.dp))
                    ChipTag(it, accent = false)
                }
            }
        }
        IconBtn(
            icon = Phosphor.X,
            onClick = onDismiss,
            size = 34,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
        if (frame == null) {
            TextC(
                SUB_PREVIEW_LINE,
                style = t.Mono17, color = c.textDim,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
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

/**
 * HFR (M4.2) comes from the real capture frame header's own `hfr` field — real, once a frame has
 * landed. RMS/SNR stay honest placeholders: confirmed against source (docs/M4-plan.md "Guide/Focus
 * real telemetry does not exist on this protocol") that `new_guide_state` never carries either
 * value, on this or any future Media-channel work — not a "not yet wired" gap, a permanent one.
 */
@Composable
private fun StatsRow(state: AppState, ctrl: SessionController) {
    val hfr = state.latestCaptureFrame?.header?.hfr
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        MiniStat(
            label = "HFR",
            value = hfr?.let { "%.2f".format(it) } ?: "—",
            sub = if (hfr == null) "not available yet" else null,
            subColor = NocturneTheme.colors.textFaint,
            onClick = { ctrl.openSheet(SheetType.FOCUS) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "RMS",
            value = "—",
            sub = "no wire telemetry",
            subColor = NocturneTheme.colors.textFaint,
            onClick = { ctrl.openSheet(SheetType.GUIDE) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.4.dp))
        MiniStat(
            label = "SNR",
            value = "—",
            sub = "no wire telemetry",
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
private fun FlipBanner(state: AppState, ctrl: SessionController) {
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
        }
        // FLIP NOW/DEFER are always disabled — no real wire command exists for either (see the
        // "no real trigger" text above); no simulator left to enable them (removed 2026-08-22,
        // see docs/simulator-removal-plan.md). Kept visible with an honest disabled state rather
        // than removed, same pattern as rotator/dome (ControlsScreen.kt) and the other genuinely
        // unwired controls this app is honest about instead of hiding.
        Box(
            Modifier
                .border(1.dp, c.warn.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            TextC("FLIP NOW", style = t.Button12, color = c.warn.copy(alpha = 0.4f))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .border(1.dp, c.warn.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            TextC("DEFER", style = t.Button12, color = c.warn.copy(alpha = 0.4f))
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
