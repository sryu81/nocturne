package com.nocturne.ui.home

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
import com.nocturne.data.LogRepository
import com.nocturne.session.guideRmsTotal
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.mountTrackingOn
import com.nocturne.session.mountParkedReal
import com.nocturne.session.isOn
import com.nocturne.session.indiSwitchElement
import com.nocturne.session.SheetType
import com.nocturne.session.contractJob
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


/** The first page after connecting: a glanceable rig overview (plan 1 in docs/PLAN.md). */
@Composable
fun HomeScreen(
    state: AppState,
    ctrl: SessionController,
    landscape: Boolean,
    onGoToSequence: () -> Unit,
    onDisconnect: () -> Unit,
    logRepo: LogRepository,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) { NightArcCard(state) },
            TabItem(full = true) { EkosCard(state, ctrl) },
            TabItem(full = true) { EmergencyStopCard(state, ctrl) },
            TabItem(full = true) { RigHealthSummaryCard(state, ctrl) },
            TabItem(full = true) { SessionLine(state, onGoToSequence) },
            TabItem(full = true) { AlertsCard(state, ctrl) },
            TabItem(full = true) { LogCard(logRepo, ctrl) },
            TabItem { MaintenanceCard(state, ctrl) },
            TabItem { DisconnectCard(onDisconnect) },
        ),
    )
}

/**
 * Dusk/dawn come from `state.realNightWindow` (`astro_get_almanac`/`astro_get_location`). The
 * "now" dot ([AppState.realNowFraction]) is null rather than clamped outside tonight's window,
 * so a message replaces a wrong position. No flip tick: there is no flip-time data on the wire.
 * Planned/done use block totals once [SequenceJob.synced].
 *
 * "Now" is `Instant.now()`-based, which Compose never re-evaluates by itself; `tick` forces a
 * recompose every 30s so it doesn't freeze between wire events.
 */
@Composable
internal fun NightArcCard(state: AppState) {
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
                if (window != null) "${state.formatSiteTime(window.first)} → ${state.formatSiteTime(window.second)}" else "N/A",
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
                val shotFraction = nowFrac
                val plannedFraction = if (realProgress && window != null) {
                    val spanSec = (window.second.epochSecond - window.first.epochSecond).coerceAtLeast(1)
                    (job!!.totalPlannedSec.toDouble() / spanSec).coerceIn(0.0, 1.0)
                } else {
                    null
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
                    TextC(if (realProgress) job!!.doneHM else "—", style = t.Mono34, color = c.text)
                    TextC(
                        if (realProgress) "OF ${job!!.plannedHM} INTEGRATED" else "NO JOB PUSHED",
                        style = t.Caption10, color = c.textFaint,
                    )
                    TextC(
                        if (realProgress) "sub ${job!!.totalDone}/${job.totalSubs}" else "sub N/A",
                        style = t.Mono115, color = c.accent400,
                    )
                }
                TextC(
                    if (window != null) state.formatSiteTime(window.first) else "N/A", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp),
                )
                TextC(
                    if (window != null) state.formatSiteTime(window.second) else "N/A", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TextC(
                if (realProgress) job!!.filterBreakdown else "filters N/A",
                style = t.MonoMicro, color = c.textMuted, modifier = Modifier.weight(1f),
            )
            // No dither event on the wire; this is the same every-N-subs countdown Ekos computes
            // from doneCount, so it's as exact as doneCount is.
            TextC(
                if (realProgress) job!!.currentBlockIndex?.let { job.blocks[it] }?.let { b ->
                    when {
                        b.ditherEvery == null || b.ditherEvery <= 0 -> "dither off"
                        else -> "dither in ${b.ditherEvery - (b.doneCount % b.ditherEvery)}"
                    }
                } ?: "dither N/A" else "dither N/A",
                style = t.MonoMicro, color = c.textFaint,
            )
        }
    }
}

