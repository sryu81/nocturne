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


import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import com.nocturne.data.LogRepository
import com.nocturne.session.Alert
import com.nocturne.session.missing
import com.nocturne.session.ready
import com.nocturne.session.runningJob
import com.nocturne.session.totalDoneSec
import com.nocturne.session.findTarget
import com.nocturne.session.sourceLabel
import com.nocturne.ui.components.BtnStyle
import androidx.compose.ui.graphics.Color

/**
 * Ekos on/off and the profile that starts it: the card to use when Ekos isn't running. Stopped,
 * the profiles are listed to pick from; running, the minimum-rig readiness is shown instead.
 * Profile editing stays in Gear.
 */
@Composable
internal fun EkosCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.Sparkle, size = 19.dp, tint = c.accent400)
            Spacer(Modifier.width(9.dp))
            TextC("Ekos", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
            TextC(
                if (state.ekosRunning) "RUNNING" else "NOT RUNNING",
                style = t.MonoMicro, color = if (state.ekosRunning) c.ok else c.warn,
            )
        }
        Spacer(Modifier.height(9.dp))
        if (state.ekosRunning) {
            TextC(state.activeProfile ?: "—", style = t.Body135, color = c.text)
            Spacer(Modifier.height(8.dp))
            ReadyBanner(state)
        } else if (state.profiles.isEmpty()) {
            TextC("No rig profiles yet — create one in the Gear tab.", style = t.Body13, color = c.textMuted)
        } else {
            state.profiles.forEach { p ->
                val selected = state.selectedProfile == p.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) c.accent.copy(alpha = 0.14f) else c.bg, RoundedCornerShape(4.dp))
                        .border(1.dp, if (selected) c.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { ctrl.selectProfile(p.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        TextC(p.name, style = t.Body13, color = c.text)
                        TextC("${p.deviceKeys.size} devices", style = t.MonoMicro, color = c.textFaint)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Like Ekos: Stop is locked until every device has disconnected, so cameras close cleanly.
        val devicesUp = state.ekosRunning && state.wireDevices.orEmpty().any { it.connected }
        if (devicesUp) {
            NocturneButton(
                text = "Disconnect devices",
                onClick = ctrl::disconnectDevices,
                style = BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        NocturneButton(
            text = if (state.ekosRunning) "Stop Ekos" else "Start Ekos",
            onClick = ctrl::toggleEkos,
            style = BtnStyle.OUTLINE,
            enabled = if (state.ekosRunning) !devicesUp else state.selectedProfile != null,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}

/** The running job at a glance; tap opens Sequence for the full progress. */
@Composable
internal fun SessionLine(state: AppState, onOpen: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val job = state.runningJob
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .let { if (job != null) it.clickable(onClick = onOpen) else it }
            .padding(12.dp),
    ) {
        TextC("SESSION", style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(4.dp))
        if (job == null) {
            TextC(
                if (state.jobs.isEmpty()) "No job queued — add a target from the Plan tab." else "No job running.",
                style = t.Body13, color = c.textMuted,
            )
        } else {
            val target = state.findTarget(job.targetId)?.let { if (it.custom) it.common else it.id } ?: job.targetId
            TextC(target, style = t.Body135, color = c.text)
            val remainingSec = (job.totalPlannedSec - job.totalDoneSec).coerceAtLeast(0)
            val rms = state.guideRmsTotal?.let { " · RMS %.2f\"".format(it) } ?: ""
            TextC(
                "sub ${job.totalDone}/${job.totalSubs} · ${remainingSec / 3600}h ${remainingSec % 3600 / 60}m left$rms",
                style = t.MonoSmall, color = c.accent400,
            )
        }
    }
}

/** The newest few lines of the phone's log; tap opens the full Log. */
@Composable
internal fun LogCard(repo: LogRepository, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val latest by remember { repo.observe(limit = 5) }.collectAsState(emptyList())
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openLog() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("LOG", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("open", style = t.CaptionMedium, color = c.accent)
        }
        if (latest.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            TextC("Nothing logged yet.", style = t.Body13, color = c.textMuted)
        } else {
            latest.forEach { LogRow(it) }
        }
    }
}

/** Alert count and the latest lines; tap opens the full Alerts sheet. */
@Composable
internal fun AlertsCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val alerts: List<Alert> = state.wireNotifications
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.ALERTS) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("ALERTS", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC(if (alerts.isEmpty()) "none" else "${alerts.size}", style = t.MonoMicro, color = if (alerts.isEmpty()) c.textFaint else c.warn)
        }
        if (alerts.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            TextC("No alerts this connection.", style = t.Body13, color = c.textMuted)
        } else {
            alerts.take(3).forEach { a ->
                Spacer(Modifier.height(6.dp))
                TextC(a.message, style = t.Body13, color = c.text, maxLines = 2)
                TextC("${a.time} · ${a.sourceLabel}", style = t.MonoMicro, color = c.textMuted)
            }
        }
    }
}

/** Minimum-rig readiness; only shown while Ekos runs (see [EkosCard]). */
@Composable
internal fun ReadyBanner(state: AppState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val ready = state.ready
    val color = if (ready) c.ok else c.warn
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(3.dp).height(48.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(9.dp))
        Phosphor.Icon(if (ready) Phosphor.CheckCircle else Phosphor.Warning, size = 18.dp, tint = color)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextC(if (ready) "Ready to image" else "Needs ${state.missing}", style = t.Body135, color = color, modifier = Modifier.weight(1f))
                TextC("MINIMUM RIG", style = t.MonoMicro, color = c.textFaint)
            }
            Spacer(Modifier.height(9.dp))
            Row {
                RigChip("● mount", if (state.isOn("mount")) c.ok else c.danger)
                Spacer(Modifier.width(6.dp))
                RigChip("● camera", if (state.isOn("cam")) c.ok else c.danger)
                Spacer(Modifier.width(6.dp))
                // wirePolarStage, not paTotal/paAlt/paAz, which nothing on the wire updates.
                RigChip("● polar ${state.wirePolarStage ?: "not run"}", if (state.wirePolarStage != null) c.ok else c.textFaint)
            }
        }
    }
}

@Composable
internal fun RigChip(text: String, color: Color) {
    val t = NocturneTheme.type
    Box(
        Modifier
            .background(NocturneTheme.colors.bg.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        TextC(text, style = t.MonoMicro, color = color)
    }
}

@Composable
internal fun MaintenanceCard(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.MAINTENANCE) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Warning, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Rig maintenance", style = t.Body135, color = c.text)
        TextC(
            "reboot · shutdown · time sync · GPS",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/**
 * The way back to ConnectScreen ([SessionViewModel.disconnect]) to switch rigs or reconnect
 * from scratch. Always visible.
 */
@Composable
internal fun DisconnectCard(onDisconnect: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.Plugs, size = 19.dp, tint = c.textFaint)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                TextC("Disconnect", style = t.Body135, color = c.text)
                TextC("back to Connect screen — switch rigs or reconnect", style = t.MonoMicro, color = c.textFaint)
            }
        }
        Spacer(Modifier.height(9.dp))
        NocturneButton(
            text = "Disconnect from rig",
            onClick = onDisconnect,
            style = BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}

