package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.DEVICES
import com.nocturne.session.Device
import com.nocturne.session.IndiProperty
import com.nocturne.session.LiveDevice
import com.nocturne.session.formatIndiNumber
import com.nocturne.session.installedDriverOptions
import com.nocturne.session.realDeviceOptions
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.isOn
import com.nocturne.session.isSelected
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme


// ── Device ───────────────────────────────────────────────────────────────

@Composable
internal fun DeviceSheet(state: AppState, ctrl: SessionController) {
    val live = state.wireDevices?.firstOrNull { it.name == state.deviceKey }
    if (live != null) {
        RealDeviceSheetBody(state, ctrl, live)
        return
    }
    val d = DEVICES.firstOrNull { it.key == state.deviceKey } ?: DEVICES[0]
    if (!state.ekosRunning) {
        DevicePickerBody(state, ctrl, d)
        return
    }
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val on = state.isOn(d.key)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (on) c.ok else if (d.req) c.danger else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(if (on) "connected" else "not connected", style = t.MonoMid, color = if (on) c.ok else if (d.req) c.danger else c.textMuted, modifier = Modifier.weight(1f))
            if (d.req) {
                TextC(
                    "REQUIRED",
                    style = t.MonoMini,
                    color = c.accent400,
                    modifier = Modifier
                        .background(c.accent.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        val liveDriverName = state.selectedDeviceNames[d.key] ?: d.name
        d.cfg.forEach { (label, staticValue) ->
            val value = if (label == "Driver") liveDriverName else staticValue
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.text.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 11.2.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextC(label, style = t.Caption, color = c.textMuted, modifier = Modifier.width(88.dp))
                TextC(value, style = t.MonoMid, color = c.text, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.4.dp))
        }
        NocturneButton(
            text = if (on) "Disconnect" else "Connect",
            onClick = { ctrl.toggleDevice(d.key) },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        TextC(
            "Saved to profile “${state.activeProfile ?: state.profileName}” — ${state.profiles.size} profiles",
            style = t.Mono115, color = c.textMuted,
        )
        val driverName = state.selectedDeviceNames[d.key] ?: d.name
        IndiPropertyPanel(deviceKey = driverName, props = state.indiProps[driverName] ?: emptyList(), ctrl = ctrl)
    }
}

/** Connected device sheet: connect toggle and the device's live INDI property panel. */
@Composable
internal fun RealDeviceSheetBody(state: AppState, ctrl: SessionController, d: LiveDevice) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (d.connected) c.ok else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(
                if (d.connected) "connected" else "not connected",
                style = t.MonoMid, color = if (d.connected) c.ok else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            TextC(d.roles.joinToString(" · ") { it.name.lowercase() }, style = t.MonoMicro, color = c.textFaint)
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = if (d.connected) "Disconnect" else "Connect",
            onClick = { ctrl.toggleDevice(d.name) },
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
        IndiPropertyPanel(deviceKey = d.name, props = state.indiProps[d.name] ?: emptyList(), ctrl = ctrl)
    }
}

/** Device sheet before Ekos is running (profile setup): only a selection toggle, nothing to configure yet. */
@Composable
internal fun DevicePickerBody(state: AppState, ctrl: SessionController, d: Device) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val selected = state.isSelected(d.key)
    val current = state.selectedDeviceNames[d.key] ?: d.name
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(if (selected) c.ok else if (d.req) c.danger else c.textMuted, RoundedCornerShape(50)))
            Spacer(Modifier.width(9.dp))
            TextC(
                if (selected) "selected for this profile" else "not selected",
                style = t.MonoMid,
                color = if (selected) c.ok else if (d.req) c.danger else c.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (d.req) {
                TextC(
                    "REQUIRED",
                    style = t.MonoMini,
                    color = c.accent400,
                    modifier = Modifier
                        .background(c.accent.copy(alpha = 0.18f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        val used = state.realDeviceOptions(d.key) ?: d.catalog
        val installed = state.installedDriverOptions(d.key)
        var query by remember(d.key) { mutableStateOf("") }
        val q = query.trim().lowercase()
        fun matches(vararg text: String) = q.isEmpty() || text.any { it.lowercase().contains(q) }

        if (installed != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) TextC("Search ${installed.size} installed drivers…", style = t.Body13, color = c.textFaint)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(11.2.dp))
        }

        val usedShown = used.filter { matches(it) }
        if (usedShown.isNotEmpty()) {
            TextC(if (installed != null) "USED BEFORE" else "AVAILABLE DEVICES", style = t.MicroLabel, color = c.textFaint)
            Spacer(Modifier.height(5.dp))
            DeviceOptionList(usedShown.map { it to null }, current) { ctrl.selectDeviceName(d.key, it) }
        }

        if (installed != null) {
            // everything else installed on the Pi, by manufacturer; "used before" entries aren't repeated
            val rest = installed.filter { it.label !in used && matches(it.label, it.manufacturer) }
            Spacer(Modifier.height(11.2.dp))
            TextC("ALL INSTALLED DRIVERS · ${rest.size}", style = t.MicroLabel, color = c.textFaint)
            Spacer(Modifier.height(5.dp))
            if (rest.isEmpty()) {
                TextC(if (q.isEmpty()) "Nothing else installed for this role." else "No driver matches \"$query\".", style = t.Caption, color = c.textMuted)
            }
            rest.groupBy { it.manufacturer.ifBlank { "Other" } }.forEach { (maker, drivers) ->
                TextC(maker.uppercase(), style = t.MonoMini, color = c.accent400, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                DeviceOptionList(drivers.map { it.label to it.binary.ifBlank { null } }, current) { ctrl.selectDeviceName(d.key, it) }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Connect and configure once Ekos is running for this profile.",
            style = t.Mono115, color = c.textMuted,
        )
    }
}

/** Bordered list of selectable driver labels for [DevicePickerBody]; second of each pair is a dim detail (driver binary). */
@Composable
internal fun DeviceOptionList(options: List<Pair<String, String?>>, current: String, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
    ) {
        options.forEachIndexed { i, (name, detail) ->
            val sel = name == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (sel) c.accent.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(name) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC(name, style = t.Body13, color = if (sel) c.accent400 else c.text)
                    detail?.let { TextC(it, style = t.MonoMicro, color = c.textFaint) }
                }
                if (sel) Phosphor.Icon(Phosphor.Check, size = 14.dp, tint = c.accent400)
            }
            if (i < options.lastIndex) HDivider()
        }
    }
}

/**
 * Generic INDI control panel, one card per property vector rendered from its [IndiProperty]
 * shape: switches as segmented pickers, numbers as steppers, text as fields, lights as dots.
 */
@Composable
internal fun IndiPropertyPanel(deviceKey: String, props: List<IndiProperty>, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    if (props.isEmpty()) return
    Spacer(Modifier.height(11.2.dp))
    TextC("INDI CONTROLS", style = t.MicroLabel, color = c.textFaint)
    Spacer(Modifier.height(8.dp))
    props.forEach { prop ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(11.2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextC(prop.label, style = t.Body13, color = c.text, modifier = Modifier.weight(1f))
                TextC(prop.group, style = t.MonoMicro, color = c.textFaint)
            }
            Spacer(Modifier.height(8.dp))
            when (prop) {
                is IndiProperty.SwitchProp -> Row {
                    prop.options.forEachIndexed { i, label ->
                        val sel = i == prop.selected
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                                .border(1.dp, if (sel) c.accent else c.divider, RoundedCornerShape(4.dp))
                                .clickable { ctrl.setIndiSwitch(deviceKey, prop.name, i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            TextC(label, style = t.MonoSmall, color = if (sel) c.accent400 else c.textMuted)
                        }
                        if (i < prop.options.lastIndex) Spacer(Modifier.width(6.dp))
                    }
                }
                is IndiProperty.NumberProp -> Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(
                        icon = Phosphor.CaretLeft, size = 28,
                        onClick = { ctrl.setIndiNumber(deviceKey, prop.name, prop.value - prop.step) },
                    )
                    TextC(
                        formatIndiNumber(prop.format, prop.value), style = t.Mono15, color = c.text,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconBtn(
                        icon = Phosphor.CaretRight, size = 28,
                        onClick = { ctrl.setIndiNumber(deviceKey, prop.name, prop.value + prop.step) },
                    )
                }
                // One row per element: a text vector can hold several (e.g. FILTER_NAME per slot).
                is IndiProperty.TextProp -> Column {
                    prop.elements.forEachIndexed { i, (elementName, value) ->
                        if (i > 0) Spacer(Modifier.height(6.dp))
                        IndiTextElementRow(
                            index = i, showIndex = prop.elements.size > 1, value = value,
                            onChange = { ctrl.setIndiText(deviceKey, prop.name, elementName, it) },
                        )
                    }
                }
                is IndiProperty.LightProp -> Row {
                    prop.elements.forEach { (label, ipState) ->
                        val dotColor = when (ipState) {
                            1 -> c.ok
                            2 -> c.warn
                            3 -> c.danger
                            else -> c.textFaint
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
                            Box(Modifier.size(8.dp).background(dotColor, RoundedCornerShape(50)))
                            Spacer(Modifier.width(5.dp))
                            TextC(label, style = t.MonoMicro, color = c.textMuted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Keeps its own `text` because a driver may not echo a text vector back promptly; binding to the
 * live value would snap the field back mid-typing. Keyed by [index] so each element has its own buffer.
 */
@Composable
internal fun IndiTextElementRow(index: Int, showIndex: Boolean, value: String, onChange: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember(index) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIndex) {
            TextC("${index + 1}", style = t.MonoSmall, color = c.textFaint, modifier = Modifier.width(20.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it; onChange(it) },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .background(c.surface, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
        )
    }
}
