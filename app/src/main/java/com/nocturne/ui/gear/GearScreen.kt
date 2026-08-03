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
import com.nocturne.session.DEVICES
import com.nocturne.session.SessionController
import com.nocturne.session.activeRigProfile
import com.nocturne.session.SheetType
import com.nocturne.session.SimState
import com.nocturne.session.isOn
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
    "weather" to Phosphor.CloudSun,
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
        items = listOf(
            TabItem(full = true) { ReadyBanner(state) },
            TabItem(full = true) { RigProfileCard(state, ctrl) },
            TabItem { BenchCard(ctrl) },
            TabItem { PaCard(state, ctrl) },
            TabItem(full = true) { DeviceList(state, ctrl) },
            TabItem(full = true) { PowerDew() },
            TabItem(full = true) { CloseRoofButton() },
        ),
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
                name = d.name,
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

@Composable
private fun PowerDew() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC("POWER · DEW", style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            TextC("3.42 A · 12.1 V", style = t.MonoSmall, color = c.accent400)
        }
        Spacer(Modifier.height(11.2.dp))
        DewRow("Dew · main", 0.62f)
        Spacer(Modifier.height(11.2.dp))
        DewRow("Dew · guide", 0.40f)
    }
}

@Composable
private fun DewRow(label: String, frac: Float) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC(label, style = t.Caption, color = c.textDim, modifier = Modifier.width(78.dp))
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
            drawRoundRect(
                color = c.accent,
                topLeft = androidx.compose.ui.geometry.Offset(0f, y - trackH / 2),
                size = androidx.compose.ui.geometry.Size(size.width * frac, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )
            drawCircle(c.text, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * frac, y))
        }
        Spacer(Modifier.width(8.dp))
        TextC("${(frac * 100).toInt()}%", style = t.Mono13, color = c.text, modifier = Modifier.width(34.dp))
    }
}

@Composable
private fun CloseRoofButton() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .border(1.dp, c.danger.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(Phosphor.Garage, size = 18.dp, tint = c.danger)
            Spacer(Modifier.width(9.dp))
            TextC("Hold to close roof", style = t.Button13, color = c.danger)
        }
    }
}
