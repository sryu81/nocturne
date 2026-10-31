package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nocturne.session.RigRebootState
import com.nocturne.session.SkyAtlasStatus
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.missing
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme


// ── Rig maintenance ─────────────────────────────────────────────────────────

/**
 * Most recent last-known fix from the GPS or network provider, or null if neither has one. A
 * cached fix is precise enough for site/almanac math, so there's no Play Services dependency or
 * wait for a fresh lock. Elevation defaults to 0.0 when missing; it only affects refraction.
 */
internal fun requestLastKnownLocation(context: android.content.Context): Triple<Double, Double, Double>? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) return null
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val candidates = listOfNotNull(
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
    )
    val best = candidates.maxByOrNull { it.time } ?: return null
    return Triple(best.latitude, best.longitude, if (best.hasAltitude()) best.altitude else 0.0)
}

/**
 * Rig-level recovery. The wire has no reboot command, and a hung Ekos couldn't serve one anyway,
 * so this talks to a companion daemon on the Pi over HTTP (`pi-tools/reboot-daemon/`, [RigRebootClient]).
 */
@Composable
internal fun MaintenanceSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    var portText by remember { mutableStateOf(state.rigRebootPort.toString()) }
    var showConfirm by remember { mutableStateOf(false) }
    var showShutdownConfirm by remember { mutableStateOf(false) }

    // Location sync goes over the EkosRemote wire, not the daemon.
    val context = LocalContext.current
    var locationError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            requestLastKnownLocation(context)?.let { (lat, lon, elev) -> ctrl.syncLocationToRig(lat, lon, elev) }
                ?: run { locationError = "No cached GPS fix yet — open Maps once, then retry" }
        } else {
            locationError = "Location permission denied"
        }
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.warn.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Phosphor.Icon(Phosphor.Warning, size = 16.dp, tint = c.warn)
            Spacer(Modifier.width(10.dp))
            TextC(
                "Reboot and shut down act on the Pi itself, not just Ekos — they cut power to any " +
                    "in-progress slew, capture, or guiding. After a reboot, reconnect once the Pi is back; " +
                    "after a shutdown it stays off until you cycle its power.",
                style = t.Caption, color = c.warn,
            )
        }
        Spacer(Modifier.height(20.dp))

        TextC("REBOOT DAEMON PORT", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        TextC(
            "Same host as the rig connection. Default 9001 (pi-tools/reboot-daemon).",
            style = t.MonoMicro, color = c.textFaint,
        )
        val typedPort = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (typedPort != null && typedPort != state.rigRebootPort) {
            Spacer(Modifier.height(10.dp))
            NocturneButton(
                text = "Save port",
                onClick = { ctrl.setRigRebootPort(typedPort) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))

        NocturneButton(
            text = when (state.rigRebootState) {
                RigRebootState.SENDING -> "Sending…"
                else -> "Reboot Pi"
            },
            onClick = { showConfirm = true },
            enabled = state.rigRebootState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.rigRebootState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(10.dp))
                TextC("Reboot sent — rig will be back in ~60s.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(10.dp))
                TextC("Couldn't reboot: ${state.rigRebootError}", style = t.Caption, color = c.danger)
            }
            else -> {}
        }
        Spacer(Modifier.height(12.dp))
        NocturneButton(
            text = if (state.rigShutdownState == RigRebootState.SENDING) "Sending…" else "Shut down Pi",
            onClick = { showShutdownConfirm = true },
            enabled = state.rigShutdownState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.DANGER,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.rigShutdownState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(10.dp))
                TextC("Shutdown sent — the Pi powers off in a few seconds.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(10.dp))
                TextC("Couldn't shut down: ${state.rigShutdownError}", style = t.Caption, color = c.danger)
            }
            else -> {}
        }
        Spacer(Modifier.height(24.dp))
        HDivider()
        Spacer(Modifier.height(24.dp))

        // Location goes over the wire (kstars_set_location). Time sets both the Pi OS clock
        // (daemon) and KStars' clock (wire).
        TextC("GPS / TIME SYNC", style = t.MicroUppercase, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        NocturneButton(
            text = "Sync location from phone GPS",
            onClick = {
                locationError = null
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    requestLastKnownLocation(context)?.let { (lat, lon, elev) -> ctrl.syncLocationToRig(lat, lon, elev) }
                        ?: run { locationError = "No cached GPS fix yet — open Maps once, then retry" }
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        locationError?.let {
            Spacer(Modifier.height(8.dp))
            TextC(it, style = t.Caption, color = c.danger)
        }
        Spacer(Modifier.height(12.dp))
        NocturneButton(
            text = when (state.piTimeSyncState) {
                RigRebootState.SENDING -> "Syncing…"
                else -> "Sync time to rig"
            },
            onClick = ctrl::syncTimeToRig,
            enabled = state.piTimeSyncState != RigRebootState.SENDING,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        when (state.piTimeSyncState) {
            RigRebootState.SENT -> {
                Spacer(Modifier.height(8.dp))
                TextC("Time synced — Pi OS clock + KStars' own clock both updated.", style = t.Caption, color = c.ok)
            }
            RigRebootState.FAILED -> {
                Spacer(Modifier.height(8.dp))
                TextC(
                    "KStars' own clock still updated over the wire; Pi OS clock didn't: ${state.piTimeSyncError}",
                    style = t.Caption, color = c.warn,
                )
            }
            else -> {}
        }
        Spacer(Modifier.height(24.dp))
        HDivider()
        Spacer(Modifier.height(24.dp))
        SkyAtlasSection(state.skyAtlas, ctrl)
    }

    if (showConfirm) {
        com.nocturne.ui.components.WindowConfirmDialog(
            title = "Reboot the rig?",
            message = "This power-cycles the Pi. Anything mid-slew, mid-capture, or mid-guide " +
                "will be cut off ungracefully.",
            confirmText = "Reboot",
            onConfirm = {
                showConfirm = false
                ctrl.rebootRig()
            },
            onDismiss = { showConfirm = false },
        )
    }
    if (showShutdownConfirm) {
        com.nocturne.ui.components.WindowConfirmDialog(
            title = "Shut down the rig?",
            message = "This powers the Pi off. It will not come back until its power is cycled, and " +
                "anything mid-slew, mid-capture, or mid-guide will be cut off ungracefully.",
            confirmText = "Shut down",
            onConfirm = {
                showShutdownConfirm = false
                ctrl.shutdownRig()
            },
            onDismiss = { showShutdownConfirm = false },
        )
    }
}

/**
 * Offline sky atlas (DSS2 colour HiPS, orders 0..5) for the Plan tab's Framing card, copied from
 * the rig's own mirror over the reboot daemon. Tiles don't depend on the scope or camera, so one
 * copy frames any target at any focal length with no internet.
 */
@Composable
internal fun SkyAtlasSection(atlas: SkyAtlasStatus, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    LaunchedEffect(Unit) { ctrl.refreshSkyAtlas() }

    TextC("SKY ATLAS (OFFLINE FRAMING)", style = t.MicroUppercase, color = c.textMuted)
    Spacer(Modifier.height(8.dp))
    TextC(
        if (atlas.copying) "Copying: checked ${atlas.copyDone} / ${atlas.totalTiles} tiles"
        else "On phone: ${atlas.localTiles} / ${atlas.totalTiles} tiles" +
            if (atlas.completeOrder >= 0) " · frames from level ${atlas.completeOrder}" else " · not usable yet",
        style = t.Body13, color = c.text,
    )
    Spacer(Modifier.height(4.dp))
    TextC(
        atlas.rigTiles?.let { "On rig: $it / ${atlas.totalTiles} tiles" } ?: "On rig: checking…",
        style = t.MonoMicro, color = c.textFaint,
    )
    Spacer(Modifier.height(12.dp))
    if (atlas.copying) {
        LinearProgressIndicator(
            progress = { atlas.copyDone.toFloat() / atlas.totalTiles.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
            color = c.accent,
            trackColor = c.divider,
        )
        Spacer(Modifier.height(10.dp))
        NocturneButton(
            text = "Cancel copy",
            onClick = ctrl::cancelSkyAtlasCopy,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        NocturneButton(
            text = if (atlas.localTiles == 0) "Copy atlas from rig (~1 GB)" else "Copy missing tiles from rig",
            onClick = ctrl::copySkyAtlasFromRig,
            enabled = atlas.localTiles < atlas.totalTiles,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth(),
        )
        if (atlas.localTiles > 0) {
            Spacer(Modifier.height(10.dp))
            NocturneButton(
                text = "Delete loaded tiles (level 5) from phone",
                onClick = ctrl::clearSkyAtlas,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    atlas.lastMissing?.takeIf { it > 0 }?.let {
        Spacer(Modifier.height(8.dp))
        TextC("$it tiles not on the rig yet (its download is still running) — copy again later.", style = t.Caption, color = c.warn)
    }
    atlas.error?.let {
        Spacer(Modifier.height(8.dp))
        TextC(it, style = t.Caption, color = c.danger)
    }
}
