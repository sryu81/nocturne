package com.nocturne.ui.home

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.AppState
import com.nocturne.session.MODULE_STATUS_LABELS
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.indiSwitchElement
import com.nocturne.session.isOn
import com.nocturne.session.mountParkedReal
import com.nocturne.session.mountTrackingOn
import com.nocturne.session.moduleStatus
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.transport.LinkStats
import com.nocturne.transport.PiHealth
import com.nocturne.transport.RigRebootClient
import com.nocturne.transport.WifiSignal
import com.nocturne.transport.rigBeacons
import com.nocturne.transport.wifiSignal
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val BEACON_FRESH_MS = 16_000L
private const val SILENT_AFTER_MS = 30_000L
private const val DAEMON_POLL_MS = 15_000L
private const val WIFI_POLL_MS = 3_000L

internal enum class Health { OK, WARN, BAD, NEUTRAL }

/** What the polled sources (not the EkosRemote socket) last said. */
data class RigHealthSnapshot(
    val wifi: WifiSignal? = null,
    /** Wall-clock ms of the Pi's last beacon; 0 = none heard. */
    val beaconAtMs: Long = 0,
    val daemon: PiHealth? = null,
    val daemonChecked: Boolean = false,
)

/**
 * Polls what the EkosRemote socket can't tell: the phone's Wi-Fi, the Pi's UDP beacon and the
 * maintenance daemon's `/health`. [snapshots] only runs while collected, so the multicast lock, the
 * socket and the polling exist only while Home or the health sheet is on screen.
 */
class RigHealthMonitor(private val context: Context, private val host: String, private val daemonPort: Int) {
    fun snapshots(): Flow<RigHealthSnapshot> = channelFlow {
        val state = MutableStateFlow(RigHealthSnapshot())
        launch {
            while (true) {
                state.update { it.copy(wifi = wifiSignal(context)) }
                delay(WIFI_POLL_MS)
            }
        }
        launch {
            // Best effort, like ConnectScreen's discovery: a bind failure just leaves it "not heard".
            runCatching { rigBeacons(context).collect { if (it.address == host) state.update { s -> s.copy(beaconAtMs = it.seenAtMs) } } }
        }
        launch {
            val client = RigRebootClient(host, daemonPort)
            while (true) {
                val health = client.health()
                state.update { it.copy(daemon = health, daemonChecked = true) }
                delay(DAEMON_POLL_MS)
            }
        }
        state.collect { send(it) }
    }
}

/** Everything the health views read, handed down with a composition local so sheets can reach it. */
class RigHealthSource(
    val status: ConnectionStatus?,
    val link: LinkStats?,
    val snapshot: StateFlow<RigHealthSnapshot>,
)

val LocalRigHealth = compositionLocalOf<RigHealthSource?> { null }

internal data class Verdict(val text: String, val health: Health)

/**
 * Names the likeliest failure from independent sources: a socket that answers or not, a Pi that
 * beacons or not, a daemon that answers or not. [nowMs] ages the last message and beacon.
 */
internal fun rigVerdict(status: ConnectionStatus?, link: LinkStats?, snap: RigHealthSnapshot, nowMs: Long): Verdict {
    val socketUp = status?.state == ConnectionState.ONLINE || status?.state == ConnectionState.SOCKET_OPEN
    val msgAge = link?.lastMessageAtMs?.takeIf { it > 0 }?.let { nowMs - it }
    val beaconFresh = snap.beaconAtMs > 0 && nowMs - snap.beaconAtMs < BEACON_FRESH_MS
    val daemonOk = snap.daemon != null
    return when {
        socketUp && msgAge != null && msgAge > SILENT_AFTER_MS ->
            Verdict("KStars is connected but has been silent for ${age(msgAge)}: it may be frozen.", Health.BAD)
        !socketUp && daemonOk -> Verdict("The Pi is up but KStars isn't answering: restart KStars, not the Pi.", Health.BAD)
        !socketUp && snap.daemonChecked && !beaconFresh -> Verdict("The Pi isn't reachable: out of Wi-Fi range, or it lost power.", Health.BAD)
        socketUp && snap.daemonChecked && !daemonOk -> Verdict("KStars answers, but the Pi's maintenance daemon doesn't.", Health.WARN)
        else -> Verdict("Every link is answering.", Health.OK)
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

/**
 * Home's compact health card: the verdict, a line of link figures, the mount and cooler state, and a
 * power warning when the Pi reports one. Tap opens [RigHealthSheet].
 */
@Composable
internal fun RigHealthSummaryCard(state: AppState, ctrl: SessionController) {
    val source = LocalRigHealth.current
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val snap by (source?.snapshot ?: remember { MutableStateFlow(RigHealthSnapshot()) }).collectAsState()
    val now = rememberNow()
    val verdict = rigVerdict(source?.status, source?.link, snap, now)

    val figures = listOfNotNull(
        snap.wifi?.let { "Wi-Fi ${it.rssiDbm} dBm" },
        source?.link?.rttMs?.let { "KStars $it ms" },
        snap.daemon?.let { "daemon ${it.rttMs} ms" },
    ).joinToString(" · ")
    val camera = state.primaryTrain.camera
    val cameraConnected = state.wireDevices?.any { it.name == camera && it.connected } == true
    val cooler = if (cameraConnected) state.indiSwitchElement(camera, "CCD_COOLER")?.let { if (it == "COOLER_ON") "cooler on" else "cooler off" } else null
    val mount = when {
        !state.isOn("mount") -> null
        state.mountParkedReal -> "mount parked"
        state.mountTrackingOn == true -> "mount tracking"
        state.mountTrackingOn == false -> "mount idle"
        else -> null
    }
    val rig = listOfNotNull(mount, cooler).joinToString(" · ")
    val pi = snap.daemon

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.RIG_HEALTH) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("RIG HEALTH", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("details", style = t.CaptionMedium, color = c.accent)
        }
        Spacer(Modifier.height(4.dp))
        TextC(verdict.text, style = t.Body13, color = color(verdict.health))
        if (figures.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            TextC(figures, style = t.MonoSmall, color = c.textMuted)
        }
        if (rig.isNotEmpty()) TextC(rig, style = t.MonoSmall, color = c.textMuted)
        if (pi?.underVoltageNow == true) {
            TextC("Pi under-voltage now", style = t.MonoSmall, color = c.danger)
        } else if (pi?.throttledSinceBoot == true) {
            TextC("Pi throttled since boot", style = t.MonoSmall, color = c.warn)
        }
    }
}

/** The long form: every link, every module with its last change, and the Pi's own health. */
@Composable
fun RigHealthSheet(state: AppState) {
    val source = LocalRigHealth.current
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val status = source?.status
    val link = source?.link
    val snap by (source?.snapshot ?: remember { MutableStateFlow(RigHealthSnapshot()) }).collectAsState()
    val now = rememberNow()
    val verdict = rigVerdict(status, link, snap, now)
    val socketUp = status?.state == ConnectionState.ONLINE || status?.state == ConnectionState.SOCKET_OPEN
    val msgAge = link?.lastMessageAtMs?.takeIf { it > 0 }?.let { now - it }
    val beaconFresh = snap.beaconAtMs > 0 && now - snap.beaconAtMs < BEACON_FRESH_MS
    val silent = socketUp && msgAge != null && msgAge > SILENT_AFTER_MS
    val wifi = snap.wifi
    val daemon = snap.daemon

    Column {
        TextC(verdict.text, style = t.Body135, color = color(verdict.health))
        Spacer(Modifier.height(12.dp))

        HealthRow(
            "Wi-Fi",
            wifi?.let { "${bars(it.level)} ${it.rssiDbm} dBm" } ?: "not on Wi-Fi",
            when {
                wifi == null -> Health.NEUTRAL
                wifi.level >= 3 -> Health.OK
                wifi.level == 2 -> Health.WARN
                else -> Health.BAD
            },
        )
        HealthRow(
            "Pi beacon",
            if (snap.beaconAtMs > 0) "heard ${age(now - snap.beaconAtMs)} ago" else "not heard",
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
            if (silent || !socketUp) Health.BAD else Health.OK,
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
                daemon != null -> "answers · ${daemon.rttMs} ms"
                snap.daemonChecked -> "not answering"
                else -> "checking…"
            },
            if (daemon != null) Health.OK else if (snap.daemonChecked) Health.WARN else Health.NEUTRAL,
        )

        Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
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
                    if (abs <= 5) "in sync with the phone" else "off by ${age(abs * 1000)}: sync it in Rig maintenance",
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
internal fun color(health: Health): Color {
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

internal fun bars(level: Int): String = "▂▄▆█".take(level.coerceIn(0, 4)).padEnd(4, '·')

/** "12s", "4m", "2h 5m", "3d 4h": coarse, since it is shown to a person watching a rig. */
internal fun age(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h ${s % 3600 / 60}m"
        else -> "${s / 86_400}d ${s % 86_400 / 3600}h"
    }
}
