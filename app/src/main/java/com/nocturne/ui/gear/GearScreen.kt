package com.nocturne.ui.gear

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nocturne.protocol.DeviceRole
import com.nocturne.session.DEVICES
import com.nocturne.session.LiveDevice
import com.nocturne.session.SessionController
import com.nocturne.session.activeRigProfile
import com.nocturne.session.findScope
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.indiNumber
import com.nocturne.session.isOn
import com.nocturne.session.isSelected
import com.nocturne.session.missing
import com.nocturne.session.paTotal
import com.nocturne.session.ready
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

private val DEVICE_ICONS: Map<String, ImageVector> = mapOf(
    "mount" to Phosphor.CompassTool,
    "cam" to Phosphor.Camera,
    "efw" to Phosphor.CirclesThree,
    "guide" to Phosphor.CrosshairSimple,
    "focus" to Phosphor.ArrowsInLineHorizontal,
    "rotator" to Phosphor.ArrowsClockwise,
    "dome" to Phosphor.Garage,
    "weather" to Phosphor.CloudSun,
    "powerbox" to Phosphor.Plugs,
)

@Composable
private fun paColor(state: SimState): Color {
    val c = NocturneTheme.colors
    return when {
        state.paTotal < 1 -> c.ok
        state.paTotal < 3 -> c.warn
        else -> c.danger
    }
}

@Composable
fun GearScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
            add(TabItem(full = true) { ReadyBanner(state) })
            add(TabItem(full = true) { RigProfileCard(state, ctrl) })
            add(TabItem { ScopesCard(state, ctrl) })
            add(TabItem { OpticalTrainCard(state, ctrl) })
            // Which Ekos module uses which train (ProfileSettings) — only meaningful once real
            // trains exist server-side; no fixture equivalent (SimulatedController never sets
            // wireTrains), so the card itself is simply absent there, not a decorative stand-in.
            if (state.wireTrains != null) add(TabItem { ModuleAssignmentsCard(state, ctrl) })
            add(TabItem { BenchCard(ctrl) })
            add(TabItem { PaCard(state, ctrl) })
            // Curated Mount settings (M3.3) — real-rig only, no fixture equivalent, same
            // gating as ModuleAssignmentsCard above.
            if (state.isRealRig) add(TabItem { MountSettingsCard(state, ctrl) })
            // Rig-level recovery, not an Ekos concept — only worth surfacing once actually
            // connected to a real Pi (SimulatedController has nothing to reboot).
            if (state.isRealRig) add(TabItem { MaintenanceCard(state, ctrl) })
            add(TabItem(full = true) { DeviceList(state, ctrl) })
            add(TabItem(full = true) { PowerDew(state) })
            add(TabItem(full = true) { CloseRoofButton(state, ctrl) })
        },
    )
}

@Composable
private fun ReadyBanner(state: SimState) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.ekosRunning) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.textFaint.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                .border(1.dp, c.divider, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Phosphor.Icon(Phosphor.Plugs, size = 18.dp, tint = c.textFaint)
            Spacer(Modifier.width(9.dp))
            TextC("Ekos stopped — select a rig profile below", style = t.Body135, color = c.textFaint)
        }
        return
    }

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
                RigChip("● polar ${String.format("%.1f", state.paTotal)}′", paColor(state))
            }
        }
    }
}

@Composable
private fun RigChip(text: String, color: Color) {
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
private fun RigProfileCard(state: SimState, ctrl: SessionController) {
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
            TextC("Rig profile", style = t.Body135, color = c.text, modifier = Modifier.weight(1f))
            if (state.ekosRunning) {
                TextC("EKOS RUNNING", style = t.MonoMicro, color = c.ok)
            }
        }
        Spacer(Modifier.height(9.dp))

        if (state.ekosRunning) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC(state.activeProfile ?: "—", style = t.Body135, color = c.text)
                    TextC("editing locked while running", style = t.MonoMicro, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(8.4.dp))
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
                    IconBtn(icon = Phosphor.X, onClick = { ctrl.deleteProfile(p.name) }, size = 28, tint = c.danger)
                    Spacer(Modifier.width(6.dp))
                    IconBtn(icon = Phosphor.CaretRight, onClick = { ctrl.editProfile(p.name) }, size = 28)
                }
                Spacer(Modifier.height(6.dp))
            }
            if (state.profileDeleteRefused != null) {
                TextC(
                    "Can't delete “${state.profileDeleteRefused}” — active or built-in profile",
                    style = t.MonoMicro, color = c.danger,
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .clickable { ctrl.openSetup() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(Phosphor.Plus, size = 16.dp, tint = c.accent400)
                Spacer(Modifier.width(8.dp))
                TextC("New rig profile", style = t.Body13, color = c.accent400)
            }
            Spacer(Modifier.height(8.4.dp))
        }

        NocturneButton(
            text = if (state.ekosRunning) "Stop Ekos" else "Start Ekos",
            onClick = ctrl::toggleEkos,
            style = BtnStyle.OUTLINE,
            enabled = state.ekosRunning || state.selectedProfile != null,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}

@Composable
private fun OpticalTrainCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val primaryMm = state.findScope(state.primaryTrain.scope)?.focalMm
    val secondaryMm = state.findScope(state.secondaryTrain.scope)?.focalMm
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.OPTICAL_TRAIN) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.CrosshairSimple, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Optical train", style = t.Body135, color = c.text)
        TextC(
            "primary ${primaryMm?.let { "$it mm" } ?: "—"} · secondary ${secondaryMm?.let { "$it mm" } ?: "—"}",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

/**
 * Which Ekos module (Camera/Focus/Mount/Guide/Align/Dark Library) uses which
 * named train — split out from the Optical Train card/sheet on user
 * feedback (the two were one long scroll, reading as unrelated concerns).
 * Only shown when [SimState.wireTrains] is non-null (see [GearScreen]) —
 * there's no fixture equivalent, [ctrl]'s [SimState.moduleTrainAssignments]
 * summary is simply meaningless under `SimulatedController`.
 */
@Composable
private fun ModuleAssignmentsCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val assigned = state.moduleTrainAssignments?.size ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.MODULE_ASSIGNMENTS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.CirclesThree, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Module assignments", style = t.Body135, color = c.text)
        TextC("$assigned of 6 modules set", style = t.MonoMicro, color = c.textFaint)
    }
}

/**
 * Scopes catalog card (M3.1) — real Ekos manages telescopes/lenses in their
 * own dialog, entirely separate from Optical Trains (`get_scopes`/`scope_add`
 * — see [ScopeDef]); placed right before Optical Train since a scope must
 * exist here before a train can reference it.
 */
@Composable
private fun ScopesCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val scopes = state.wireScopes ?: state.scopes
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.SCOPES) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Scopes", style = t.Body135, color = c.text)
        TextC(
            if (scopes.isEmpty()) "none defined" else "${scopes.size} defined",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

@Composable
private fun BenchCard(ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.BENCH) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.TestTube, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Bench check", style = t.Body135, color = c.text)
        TextC("test frames · cooler · focuser · slew", style = t.MonoMicro, color = c.textFaint)
    }
}

/**
 * Curated Mount settings (M3.3, see docs/M3.3-plan.md) — real-rig only, same
 * gating as [ModuleAssignmentsCard]. Distinct from [BenchCard]'s mount jog/
 * slew controls: this is configuration (meridian flip, limits, auto-park),
 * not live control.
 */
@Composable
private fun MountSettingsCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val m = state.wireMountSettings
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.MOUNT_SETTINGS) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.CompassTool, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Mount settings", style = t.Body135, color = c.text)
        TextC(
            if (m == null) "loading…" else {
                val flip = if (m.executeMeridianFlip) "flip on" else "flip off"
                val limits = if (m.enableAltitudeLimits || m.enableHaLimit) "limits on" else "no limits"
                "$flip · $limits"
            },
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

@Composable
private fun MaintenanceCard(state: SimState, ctrl: SessionController) {
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
            if (!state.isRealRig) "simulator" else if (state.rigRebootAvailable) "reboot ready" else "reboot not configured",
            style = t.MonoMicro, color = c.textFaint,
        )
    }
}

@Composable
private fun PaCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .clickable { ctrl.openSheet(SheetType.PA) }
            .padding(12.dp),
    ) {
        Phosphor.Icon(Phosphor.Target, size = 20.dp, tint = c.accent400)
        Spacer(Modifier.height(5.dp))
        TextC("Polar align", style = t.Body135, color = c.text)
        TextC(
            "${String.format("%.1f", state.paTotal)}′ total error",
            style = t.MonoMicro, color = paColor(state),
        )
    }
}

@Composable
private fun DeviceList(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    if (!state.ekosRunning) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(14.dp))
                .border(1.dp, c.divider, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Phosphor.Icon(Phosphor.Plugs, size = 18.dp, tint = c.textFaint)
            Spacer(Modifier.width(9.dp))
            TextC("No devices — start Ekos to connect", style = t.MonoSmall, color = c.textFaint)
        }
        return
    }

    val live = state.wireDevices
    if (live != null) {
        RealDeviceList(live, ctrl)
        return
    }

    val activeKeys = state.activeRigProfile?.deviceKeys ?: emptyList()
    val devices = DEVICES.filter { it.key in activeKeys }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        devices.forEachIndexed { i, d ->
            val on = state.isOn(d.key)
            DeviceRow(
                icon = DEVICE_ICONS[d.key] ?: Phosphor.Plugs,
                name = state.selectedDeviceNames[d.key] ?: d.name,
                detail = if (on) d.detail else "not connected",
                state = if (on) "LINKED" else if (d.req) "REQUIRED" else "OFF",
                stateColor = if (on) c.ok else if (d.req) c.danger else c.textFaint,
                stateBg = if (on) c.ok.copy(alpha = 0.14f) else if (d.req) c.danger.copy(alpha = 0.16f) else c.divider.copy(alpha = 0.4f),
                onClick = { ctrl.openDevice(d.key) },
            )
            if (i < devices.lastIndex) HDivider()
        }
    }
}

/** Real `get_devices` list (M3) — bucketed by [DeviceRole] icon instead of the fixed 9-category catalog. */
private val ROLE_ICONS: Map<DeviceRole, ImageVector> = mapOf(
    DeviceRole.TELESCOPE to Phosphor.CompassTool,
    DeviceRole.CCD to Phosphor.Camera,
    DeviceRole.GUIDER to Phosphor.CrosshairSimple,
    DeviceRole.FOCUSER to Phosphor.ArrowsInLineHorizontal,
    DeviceRole.FILTER to Phosphor.CirclesThree,
    DeviceRole.DOME to Phosphor.Garage,
    DeviceRole.WEATHER to Phosphor.CloudSun,
    DeviceRole.ROTATOR to Phosphor.ArrowsClockwise,
)

@Composable
private fun RealDeviceList(devices: List<LiveDevice>, ctrl: SessionController) {
    val c = NocturneTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        devices.forEachIndexed { i, d ->
            DeviceRow(
                icon = d.roles.firstNotNullOfOrNull { ROLE_ICONS[it] } ?: Phosphor.Plugs,
                name = d.name,
                detail = if (d.connected) d.roles.joinToString(" · ") { it.name.lowercase() } else "not connected",
                state = if (d.connected) "LINKED" else "OFF",
                stateColor = if (d.connected) c.ok else c.textFaint,
                stateBg = if (d.connected) c.ok.copy(alpha = 0.14f) else c.divider.copy(alpha = 0.4f),
                onClick = { ctrl.openDevice(d.name) },
            )
            if (i < devices.lastIndex) HDivider()
        }
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    name: String,
    detail: String,
    state: String,
    stateColor: Color,
    stateBg: Color,
    onClick: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Phosphor.Icon(icon, size = 19.dp, tint = c.textMuted)
        Spacer(Modifier.width(11.2.dp))
        Column(Modifier.weight(1f)) {
            TextC(name, style = t.Body135, color = c.text)
            TextC(detail, style = t.MonoMicro, color = c.textFaint)
        }
        Box(
            Modifier
                .background(stateBg, RoundedCornerShape(3.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            TextC(state, style = t.MonoTiny, color = stateColor)
        }
    }
}

/** Only shown when a Powerbox is selected in the rig profile; dims to idle when Ekos isn't running. */
@Composable
private fun PowerDew(state: SimState) {
    if (!state.isSelected("powerbox")) return
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val on = state.ekosRunning
    val deviceName = state.selectedDeviceNames["powerbox"]
    val current = deviceName?.let { state.indiNumber(it, "SENSOR_CURRENT") }
    val voltage = deviceName?.let { state.indiNumber(it, "SENSOR_VOLTAGE") }
    val dewMain = deviceName?.let { state.indiNumber(it, "DEW_A") } ?: 0.0
    val dewGuide = deviceName?.let { state.indiNumber(it, "DEW_B") } ?: 0.0
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("POWER · DEW", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            val readout = when {
                !on -> "not connected"
                current != null && voltage != null -> "%.2f A · %.1f V".format(current, voltage)
                else -> "—"
            }
            TextC(readout, style = t.MonoSmall, color = if (on) c.accent400 else c.textFaint)
        }
        Spacer(Modifier.height(11.2.dp))
        DewRow("Dew · main", if (on) (dewMain / 100.0).toFloat() else 0f, enabled = on)
        Spacer(Modifier.height(11.2.dp))
        DewRow("Dew · guide", if (on) (dewGuide / 100.0).toFloat() else 0f, enabled = on)
    }
}

@Composable
private fun DewRow(label: String, frac: Float, enabled: Boolean) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val barColor = if (enabled) c.accent else c.textFaint
    val textColor = if (enabled) c.textDim else c.textFaint
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC(label, style = t.Caption, color = textColor, modifier = Modifier.width(78.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp),
        ) {
            val y = size.height / 2f
            val trackH = 4.dp.toPx()
            drawRoundRect(
                color = c.divider,
                topLeft = androidx.compose.ui.geometry.Offset(0f, y - trackH / 2),
                size = androidx.compose.ui.geometry.Size(size.width, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )
            if (enabled) {
                drawRoundRect(
                    color = barColor,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, y - trackH / 2),
                    size = androidx.compose.ui.geometry.Size(size.width * frac, trackH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
                )
                drawCircle(c.text, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * frac, y))
            }
        }
        Spacer(Modifier.width(8.dp))
        TextC(if (enabled) "${(frac * 100).toInt()}%" else "—", style = t.Mono13, color = textColor, modifier = Modifier.width(34.dp))
    }
}

/** Only shown when a Dome is selected in the rig profile; disabled when Ekos isn't running. */
@Composable
private fun CloseRoofButton(state: SimState, ctrl: SessionController) {
    if (!state.isSelected("dome")) return
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val enabled = state.ekosRunning
    if (!enabled) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, c.textFaint.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Phosphor.Icon(Phosphor.Garage, size = 18.dp, tint = c.textFaint)
                Spacer(Modifier.width(9.dp))
                TextC("Dome offline — start Ekos", style = t.Button13, color = c.textFaint)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth()) {
        RoofButton(
            label = "Open roof",
            enabled = !state.domeOpen,
            onClick = { ctrl.setDomeOpen(true) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.4.dp))
        RoofButton(
            label = "Close roof",
            enabled = state.domeOpen,
            onClick = { ctrl.setDomeOpen(false) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RoofButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val color = if (enabled) c.danger else c.textFaint
    Box(
        modifier
            .height(52.dp)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.Garage, size = 18.dp, tint = color)
            Spacer(Modifier.width(9.dp))
            TextC(label, style = t.Button13, color = color)
        }
    }
}
