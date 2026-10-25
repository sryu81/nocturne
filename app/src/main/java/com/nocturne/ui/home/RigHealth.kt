package com.nocturne.ui.home

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nocturne.session.AppState
import com.nocturne.session.MODULE_STATUS_LABELS
import com.nocturne.session.moduleStatus
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.transport.LinkStats
import com.nocturne.transport.PiHealth
import com.nocturne.transport.RigRebootClient
import com.nocturne.transport.rigBeacons
import com.nocturne.transport.wifiSignal
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme
import kotlinx.coroutines.delay

private const val BEACON_FRESH_MS = 16_000L
private const val SILENT_AFTER_MS = 30_000L
private const val DAEMON_POLL_MS = 15_000L

private enum class Health { OK, WARN, BAD, NEUTRAL }

/**
 * Where the chain from the phone to the rig breaks: Wi-Fi, the Pi's beacon, the EkosRemote socket,
 * Ekos, the media socket and the maintenance daemon each answer independently, so the card can
 * tell a phone out of range from a dead Pi from a frozen KStars. Below it the module states with
 * their last change, and the Pi's own health from the daemon. Everything here is polled only
 * while Home is on screen.
 */
@Composable
internal fun RigHealthCard(
    state: AppState,
    status: ConnectionStatus?,
    link: LinkStats?,
    host: String?,
    daemonPort: Int,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var wifi by remember { mutableStateOf(wifiSignal(context)) }
    var beaconAt by remember { mutableLongStateOf(0L) }
    var daemon by remember { mutableStateOf<PiHealth?>(null) }
    var daemonChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            wifi = wifiSignal(context)
            delay(1_000)
        }
    }
    LaunchedEffect(host) {
        if (host == null) return@LaunchedEffect
        // Best effort, like ConnectScreen's discovery: a bind failure just leaves the row "not heard".
        runCatching { rigBeacons(context).collect { if (it.address == host) beaconAt = it.seenAtMs } }
    }
    LaunchedEffect(host, daemonPort) {
        if (host == null) return@LaunchedEffect
        val client = RigRebootClient(host, daemonPort)
        while (true) {
            daemon = client.health()
            daemonChecked = true
            delay(DAEMON_POLL_MS)
        }
    }

    val socketUp = status?.state == ConnectionState.ONLINE || status?.state == ConnectionState.SOCKET_OPEN
    val msgAge = link?.lastMessageAtMs?.takeIf { it > 0 }?.let { now - it }
    val beaconFresh = beaconAt > 0 && now - beaconAt < BEACON_FRESH_MS
    val daemonOk = daemon != null
    val silent = socketUp && msgAge != null && msgAge > SILENT_AFTER_MS

    // The verdict names the likeliest failure; the rows below are the evidence.
    val verdict: Pair<String, Health> = when {
        silent -> "KStars is connected but has been silent for ${age(msgAge!!)} — it may be frozen." to Health.BAD
        !socketUp && daemonOk -> "The Pi is up but KStars isn't answering — restart KStars, not the Pi." to Health.BAD
        !socketUp && daemonChecked && !beaconFresh -> "The Pi isn't reachable — out of Wi-Fi range, or it lost power." to Health.BAD
        socketUp && daemonChecked && !daemonOk -> "KStars answers, but the Pi's maintenance daemon doesn't." to Health.WARN
        else -> "Every link is answering." to Health.OK
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        TextC("RIG HEALTH", style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(4.dp))
        TextC(verdict.first, style = t.Body13, color = color(verdict.second))
        Spacer(Modifier.height(10.dp))

        HealthRow(
            "Wi-Fi",
            wifi?.let { "${bars(it.level)} ${it.rssiDbm} dBm" } ?: "not on Wi-Fi",
            when {
                wifi == null -> Health.NEUTRAL
                wifi!!.level >= 3 -> Health.OK
                wifi!!.level == 2 -> Health.WARN
                else -> Health.BAD
            },
        )
        HealthRow(
            "Pi beacon",
            if (beaconAt > 0) "heard ${age(now - beaconAt)} ago" else "not heard",
            if (beaconFresh) Health.OK else Health.WARN,
        )
        HealthRow(
            "KStars (9000)",
            when (status?.state) {
                ConnectionState.ONLINE, ConnectionState.SOCKET_OPEN -> buildString {
                    append(if (status.state == ConnectionState.ONLINE) "connected" else "connected, Ekos off")
                    msgAge?.let { append(" · last message ${age(it)} ago") }
                    link?.rttMs?.let { append(" · $it ms") }
                }
                ConnectionState.CONNECTING -> "reconnecting…"
                else -> "not connected"
            },
            when {
                silent -> Health.BAD
                socketUp -> Health.OK
                else -> Health.BAD
            },
        )
        HealthRow(
            "Ekos",
            if (state.ekosRunning) "running · ${state.activeProfile ?: "—"}" else "not running",
            if (state.ekosRunning) Health.OK else Health.NEUTRAL,
        )
        HealthRow(
            "Images (media)",
            when {
                link == null || !link.mediaOpen -> "closed"
                link.lastMediaAtMs > 0 -> "open · last image ${age(now - link.lastMediaAtMs)} ago"
                else -> "open · none yet"
            },
            if (link?.mediaOpen == true) Health.OK else Health.NEUTRAL,
        )
        HealthRow(
            "Daemon (9001)",
            when {
                daemon != null -> "answers · ${daemon!!.rttMs} ms"
                daemonChecked -> "not answering"
                else -> "checking…"
            },
            if (daemon != null) Health.OK else if (daemonChecked) Health.WARN else Health.NEUTRAL,
        )

        Spacer(Modifier.height(12.dp))
        TextC("MODULES", style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(4.dp))
        MODULE_STATUS_LABELS.forEach { label ->
            val value = state.moduleStatus(label)
            val changedAt = state.moduleChangedAt[label]
            HealthRow(
                label,
                if (value == null) "no report yet" else value + (changedAt?.let { " · ${age(now - it)} ago" } ?: ""),
                moduleHealth(value),
            )
        }

        daemon?.let { pi ->
            Spacer(Modifier.height(12.dp))
            TextC("PI", style = t.MicroLabel, color = c.textFaint)
            Spacer(Modifier.height(4.dp))
            pi.cpuTempC?.let {
                HealthRow("CPU", "%.0f °C".format(it) + (pi.load1?.let { l -> " · load %.2f".format(l) } ?: ""),
                    if (it >= 80) Health.BAD else if (it >= 70) Health.WARN else Health.OK)
            }
            pi.throttled?.let {
                HealthRow(
                    "Power",
                    when {
                        pi.underVoltageNow -> "under-voltage now ($it)"
                        pi.throttledSinceBoot -> "throttled since boot ($it)"
                        else -> "no under-voltage"
                    },
                    if (pi.underVoltageNow) Health.BAD else if (pi.throttledSinceBoot) Health.WARN else Health.OK,
                )
            }
            pi.sd?.let { HealthRow("SD card", "${gb(it.free)} free of ${gb(it.total)}", space(it.free, it.total)) }
            pi.usb.forEach { HealthRow("USB ${it.path}", "${gb(it.free)} free of ${gb(it.total)}", space(it.free, it.total)) }
            pi.uptimeSec?.let { HealthRow("Uptime", age(it * 1000), Health.NEUTRAL) }
            pi.clockSkewSec(now)?.let { skew ->
                val abs = kotlin.math.abs(skew)
                HealthRow(
                    "Clock",
                    if (abs <= 5) "in sync with the phone" else "off by ${age(abs * 1000)} — sync it in Rig maintenance",
                    if (abs <= 5) Health.OK else if (abs <= 60) Health.WARN else Health.BAD,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String, health: Health) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .background(color(health), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(8.dp))
        TextC(label, style = t.MonoSmall, color = c.textMuted, modifier = Modifier.width(112.dp))
        TextC(value, style = t.MonoSmall, color = c.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun color(health: Health): Color {
    val c = NocturneTheme.colors
    return when (health) {
        Health.OK -> c.ok
        Health.WARN -> c.warn
        Health.BAD -> c.danger
        Health.NEUTRAL -> c.textFaint
    }
}

/** Fail/abort/error words are bad, anything else reported is fine; same idea as the status row. */
private fun moduleHealth(status: String?): Health = when {
    status == null -> Health.NEUTRAL
    status.contains("fail", true) || status.contains("abort", true) || status.contains("error", true) -> Health.BAD
    else -> Health.OK
}

private fun space(free: Long, total: Long): Health {
    val frac = if (total > 0) free.toDouble() / total else 1.0
    return if (frac < 0.05) Health.BAD else if (frac < 0.15) Health.WARN else Health.OK
}

private fun gb(bytes: Long): String = if (bytes >= 1L shl 40) "%.1f TB".format(bytes / 1099511627776.0) else "%.1f GB".format(bytes / 1073741824.0)

private fun bars(level: Int): String = "▂▄▆█".take(level.coerceIn(0, 4)).padEnd(4, '·')

/** "12s", "4m", "2h 5m", "3d 4h": coarse, since it is shown to a person watching a rig. */
private fun age(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h ${s % 3600 / 60}m"
        else -> "${s / 86_400}d ${s % 86_400 / 3600}h"
    }
}
