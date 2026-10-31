package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nocturne.session.DEVICES
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.fRatio
import com.nocturne.session.opticNote
import com.nocturne.session.ScopeDef
import com.nocturne.session.isSelected
import com.nocturne.session.ready
import com.nocturne.session.train
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme


// ── Setup ────────────────────────────────────────────────────────────────

/** Rig-setup role names (wizard rows and picker titles), keyed like [DEVICES]. */
internal val DEVICE_ROLE_LABELS = mapOf(
    "mount" to "Mount", "cam" to "Main camera", "guide" to "Guide camera", "efw" to "Filter wheel",
    "focus" to "Focuser", "rotator" to "Rotator", "dome" to "Dome", "weather" to "Weather",
    "powerbox" to "Power box",
)

internal val DEVICE_ICONS: Map<String, ImageVector> = mapOf(
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

/**
 * Rig profile setup: name + device role pickers on one screen. Optical trains are edited
 * separately from the Gear tab.
 */
@Composable
internal fun SetupBody(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        FieldLabel("Profile name")
        Spacer(Modifier.height(5.dp))
        BasicTextField(
            value = state.profileName,
            onValueChange = ctrl::setProfileName,
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(11.2.dp))
        TextC(
            "Mount and camera are required; the rest can come later.",
            style = t.MonoSmall, color = c.neutral500,
        )
        Spacer(Modifier.height(8.4.dp))
        DEVICES.forEach { d ->
            val selected = state.isSelected(d.key)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .clickable { ctrl.openDevice(d.key) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(DEVICE_ICONS[d.key] ?: Phosphor.Plugs, size = 18.dp, tint = c.neutral500)
                Spacer(Modifier.width(11.2.dp))
                val chosen = state.selectedDeviceNames[d.key]?.takeIf { selected && it != "None" }
                Column(Modifier.weight(1f)) {
                    TextC(DEVICE_ROLE_LABELS[d.key] ?: d.key, style = t.MonoMicro, color = c.textFaint)
                    TextC(chosen ?: "Choose a driver…", style = t.Body13, color = if (chosen != null) c.text else c.textMuted)
                }
                TextC(
                    if (selected) "SELECTED" else if (d.req) "REQUIRED" else "OFF",
                    style = t.MonoMicro,
                    color = if (selected) c.ok else if (d.req) c.danger else c.textFaint,
                    modifier = Modifier
                        .background(
                            if (selected) c.ok.copy(alpha = 0.14f) else if (d.req) c.danger.copy(alpha = 0.16f) else c.divider.copy(alpha = 0.4f),
                            RoundedCornerShape(3.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(8.4.dp))
        }
    }
}

/** Scope entry: name + focal length + aperture, the Ekos Scopes catalog fields (`scope_add`). */
@Composable
internal fun ScopeInputFields(
    label: String,
    name: String, onNameChange: (String) -> Unit,
    focalMm: Int, onFocalChange: (Int) -> Unit,
    apertureMm: Int, onApertureChange: (Int) -> Unit,
    note: String,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    FieldLabel(label)
    Spacer(Modifier.height(5.dp))
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        singleLine = true,
        textStyle = t.Body13.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
    )
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth()) {
        MmField("Focal length", focalMm, Modifier.weight(1f), onFocalChange)
        Spacer(Modifier.width(8.4.dp))
        MmField("Aperture", apertureMm, Modifier.weight(1f), onApertureChange)
    }
    Spacer(Modifier.height(5.dp))
    TextC("$note · ${fRatio(focalMm, apertureMm)}", style = t.MonoMicro, color = c.textMuted)
}

/**
 * Small labeled digits-only mm field for scope focal length/aperture. Keeps its own `text` so it
 * can be cleared mid-edit; [onChange] fires only when the text parses.
 */
@Composable
internal fun MmField(label: String, mm: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf(mm.toString()) }
    Column(modifier) {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { it.isDigit() }.take(4)
                    text = filtered
                    filtered.toIntOrNull()?.let(onChange)
                },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.weight(1f),
            )
            TextC("mm", style = t.MonoSmall, color = c.neutral500)
        }
    }
}

/**
 * Ekos's Scopes catalog (`get_scopes`/`scope_add`/`scope_update`/`scope_delete`, see
 * docs/PROTOCOL.md): a flat list of telescopes/lenses, separate from the profile. A train's
 * Scope role picks one of these by name.
 */
@Composable
internal fun ScopesSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val scopes = state.wireScopes ?: state.scopes
    Column {
        scopes.forEach { scope ->
            val editing = state.editingScopeId == scope.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (editing) c.accent.copy(alpha = 0.14f) else c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, if (editing) c.accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { ctrl.toggleEditScope(scope.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC(scope.name, style = t.Body13, color = c.text)
                    TextC(
                        "${scope.focalMm} mm · ${fRatio(scope.focalMm, scope.apertureMm)} · ${scope.apertureMm} mm aperture",
                        style = t.MonoSmall, color = c.textDim,
                    )
                }
                IconBtn(icon = Phosphor.X, onClick = { ctrl.removeScope(scope.id) }, size = 28, tint = c.danger)
                Spacer(Modifier.width(6.dp))
                IconBtn(icon = Phosphor.CaretRight, onClick = { ctrl.toggleEditScope(scope.id) }, size = 28)
            }
            if (editing) {
                Spacer(Modifier.height(8.4.dp))
                ScopeEditorForm(
                    initial = scope,
                    onSave = { name, focalMm, apertureMm ->
                        ctrl.updateScope(scope.id, name, scope.vendor, scope.type, focalMm, apertureMm)
                        ctrl.toggleEditScope(scope.id)
                    },
                    onCancel = { ctrl.toggleEditScope(scope.id) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (state.addingScope) {
            ScopeEditorForm(
                initial = null,
                onSave = { name, focalMm, apertureMm -> ctrl.addScope(name, vendor = "", type = "", focalMm = focalMm, apertureMm = apertureMm) },
                onCancel = ctrl::cancelAddScope,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .clickable { ctrl.startAddScope() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(Phosphor.Plus, size = 16.dp, tint = c.accent400)
                Spacer(Modifier.width(8.dp))
                TextC("New scope", style = t.Body13, color = c.accent400)
            }
        }
    }
}

/** Inline add/edit form for one [ScopeDef]. */
@Composable
internal fun ScopeEditorForm(initial: ScopeDef?, onSave: (name: String, focalMm: Int, apertureMm: Int) -> Unit, onCancel: () -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var focalMm by remember(initial?.id) { mutableStateOf(initial?.focalMm ?: 550) }
    var apertureMm by remember(initial?.id) { mutableStateOf(initial?.apertureMm ?: 80) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        ScopeInputFields(
            label = "Name",
            name = name, onNameChange = { name = it },
            focalMm = focalMm, onFocalChange = { focalMm = it },
            apertureMm = apertureMm, onApertureChange = { apertureMm = it },
            note = opticNote(focalMm),
        )
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            NocturneButton(
                text = "Cancel", onClick = onCancel,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(40.dp),
            )
            Spacer(Modifier.width(8.4.dp))
            NocturneButton(
                text = "Save", onClick = { onSave(name, focalMm, apertureMm) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f).height(40.dp),
            )
        }
    }
}

@Composable
internal fun SetupFooter(state: AppState, ctrl: SessionController) {
    val ready = listOf("mount", "cam").all { state.isSelected(it) && state.selectedDeviceNames[it] != "None" }
    Row(Modifier.fillMaxWidth()) {
        NocturneButton(
            text = "Cancel",
            onClick = ctrl::setupBack,
            style = com.nocturne.ui.components.BtnStyle.SUBTLE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Spacer(Modifier.width(8.4.dp))
        NocturneButton(
            text = "Save profile",
            onClick = ctrl::finishSetup,
            enabled = ready,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.weight(1f).height(44.dp),
        )
    }
}
