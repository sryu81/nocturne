package com.nocturne.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.session.AppState
import com.nocturne.session.SessionController
import com.nocturne.session.currentTarget
import com.nocturne.session.formatDec
import com.nocturne.session.formatDegrees
import com.nocturne.session.formatHa
import com.nocturne.session.formatRa
import com.nocturne.session.formatSeparation
import com.nocturne.session.targetSeparationDeg
import com.nocturne.session.zenithAngle
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import kotlinx.coroutines.delay

@Composable
internal fun MountCoordsGrid(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1_000); value = System.currentTimeMillis() }
    }
    val live = state.mountCoordsAt?.let { now - it < 5_000 } == true
    val j2000 = state.mountJ2000
    val color = if (live) c.text else c.neutral500
    @Composable
    fun RowScope.cell(label: String, value: String, weight: Float = 1f) {
        Column(Modifier.weight(weight)) {
            TextC(label, style = t.MonoMicro, color = c.textFaint)
            TextC(value, style = t.MonoSmall, color = if (value == "N/A") c.textMuted else color, maxLines = 1)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            cell("RA", formatRa(if (j2000) state.mountRa0 else state.mountRa), 1.0f)
            cell("DEC", formatDec(if (j2000) state.mountDec0 else state.mountDec), 1.15f)
            cell("HA", formatHa(state.mountHa), 0.9f)
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            cell("ALT", formatDegrees(state.mountAlt), 1.0f)
            cell("AZ", formatDegrees(state.mountAz), 1.15f)
            cell("ZA", formatDegrees(zenithAngle(state.mountAlt)), 0.9f)
        }
        Spacer(Modifier.height(3.dp))
        TextC(
            (if (j2000) "J2000" else "JNow") + " · tap to switch" + if (!live && state.mountCoordsAt != null) " · not updating" else "",
            style = t.MonoMicro, color = c.textFaint,
            modifier = Modifier.clickable { ctrl.setMountJ2000(!j2000) },
        )
    }
}

/**
 * The target the user is working on: its name, its J2000 coordinates, and how far the mount is from it.
 * Shared by Home's "Target and mount" card and the Controls mount card. The target is the Plan tab's pick
 * (see [currentTarget]); with none picked it falls back to the name Ekos last sent the mount to.
 */
@Composable
internal fun TargetStrip(state: AppState, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val target = state.currentTarget()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = if (target != null) c.accent400 else c.textFaint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (target == null) {
                TextC("No target picked", style = t.Body135, color = c.textMuted)
                TextC("Pick one in the Plan tab", style = t.MonoMicro, color = c.textFaint)
            } else {
                TextC(target.name, style = t.Body135, color = c.text, maxLines = 1)
                TextC(
                    when {
                        target.coords != null -> target.coords + " · J2000"
                        !target.fromPlan -> "set outside the app (Ekos)"
                        else -> "coordinates N/A"
                    },
                    style = t.MonoMicro, color = c.textMuted, maxLines = 1,
                )
            }
        }
        state.targetSeparationDeg()?.let { sep ->
            Spacer(Modifier.width(8.dp))
            TextC(formatSeparation(sep), style = t.MonoSmall, color = if (sep < 1.0 / 60.0) c.ok else c.textMuted)
        }
    }
}

