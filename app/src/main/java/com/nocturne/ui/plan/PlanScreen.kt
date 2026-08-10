package com.nocturne.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.TARGETS
import com.nocturne.session.Target
import com.nocturne.session.displayName
import com.nocturne.session.findScope
import com.nocturne.session.findTarget
import com.nocturne.session.framingFovDeg
import com.nocturne.session.framingPixelScaleArcsecPerPx
import com.nocturne.ui.components.AltitudeChart
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.PlanChip
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

/** Results list rows are capped to roughly this many visible before scrolling internally. */
private const val VISIBLE_RESULT_ROWS = 5
private val RESULT_ROW_HEIGHT = 54.dp

@Composable
fun PlanScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onGoToSequence: () -> Unit = {},
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val q = state.query.trim().lowercase()
    // M3: a real connection's search bar drives astro_search_objects/astro_get_objects_info
    // (EkosRemoteController) instead of filtering the fixture catalog — see SimState.wireSearchResults.
    val matches = state.wireSearchResults ?: TARGETS.filter { tg ->
        if (q.isNotEmpty() && !"${tg.id} ${tg.common} ${tg.coords}".lowercase().contains(q)) return@filter false
        if (state.chips.contains(1) && (tg.max ?: 0) <= 40) return@filter false
        if (state.chips.contains(2) && !Regex("Ha|SHO|OIII").containsMatchIn(tg.band ?: "")) return@filter false
        if (state.chips.contains(3) && tg.fov == 0) return@filter false
        true
    }
    val tgt = state.findTarget(state.targetId) ?: TARGETS[0]
    val userMatches = state.userTargets.filter { tg ->
        q.isEmpty() || "${tg.common} ${tg.coords}".lowercase().contains(q)
    }

    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) {
                SearchBar(
                    query = state.query,
                    onChange = ctrl::setQuery,
                    onClear = ctrl::clearQuery,
                )
            },
            TabItem(full = true) {
                Row(Modifier.fillMaxWidth()) {
                    listOf(0, 1, 2, 3).forEach { i ->
                        PlanChip(
                            text = com.nocturne.session.PLAN_CHIPS[i],
                            selected = state.chips.contains(i),
                            onClick = { ctrl.toggleChip(i) },
                            modifier = Modifier.weight(1f).padding(end = if (i < 3) 6.dp else 0.dp),
                        )
                    }
                }
            },
            TabItem(full = true) { ResultsList(state, ctrl, matches, live = state.wireSearchResults != null) },
            TabItem(full = true) { UserCatalogSection(state, ctrl, userMatches) },
            TabItem(full = true) { TargetCard(tgt) },
            TabItem(full = true) { FramingCard(state, ctrl) },
            TabItem(full = true) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        com.nocturne.ui.components.NocturneButton(
                            text = "Goto",
                            onClick = { ctrl.gotoTarget(tgt.id) },
                            icon = Phosphor.Crosshair,
                            modifier = Modifier.weight(1f).height(44.dp),
                            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                        )
                        Spacer(Modifier.width(8.4.dp))
                        com.nocturne.ui.components.NocturneButton(
                            text = "Goto & center",
                            onClick = { ctrl.gotoAndCenter(tgt.id) },
                            icon = Phosphor.Crosshair,
                            modifier = Modifier.weight(1f).height(44.dp),
                            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                        )
                    }
                    Spacer(Modifier.height(8.4.dp))
                    com.nocturne.ui.components.NocturneButton(
                        text = "Add to sequence",
                        onClick = {
                            ctrl.addToSequence(tgt.id)
                            onGoToSequence()
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                    )
                }
            },
        ),
    )
}

@Composable
private fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(c.surface, RoundedCornerShape(10.dp))
            .border(1.dp, c.divider, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Phosphor.Icon(Phosphor.MagnifyingGlass, size = 16.dp, tint = c.textFaint)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (query.isEmpty()) {
                    TextC("Catalog — name, NGC/IC, coords", style = t.Body13, color = c.textFaint)
                }
                it()
            }
        }
        Box(
            Modifier
                .size(26.dp)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Phosphor.Icon(Phosphor.X, size = 13.dp, tint = c.textFaint)
        }
    }
}

@Composable
private fun ResultsList(
    state: SimState,
    ctrl: SessionController,
    matches: List<Target>,
    live: Boolean,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        Column(
            Modifier
                .heightIn(max = RESULT_ROW_HEIGHT * VISIBLE_RESULT_ROWS)
                .verticalScroll(rememberScrollState()),
        ) {
            matches.forEach { tg ->
                TargetRow(tg, selected = tg.id == state.targetId, onClick = { ctrl.selectTarget(tg.id) })
                if (tg != matches.last()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider.copy(alpha = 0.6f)))
                }
            }
        }
        TextC(
            if (live) "${matches.size} results · tap to frame" else "${matches.size} of ${TARGETS.size} · tap to frame",
            style = t.MonoMicro, color = c.textFaint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TargetRow(tg: Target, selected: Boolean, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        Modifier
            .fillMaxWidth()
            .height(RESULT_ROW_HEIGHT)
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextC(tg.displayName, style = t.Body13, color = c.text)
            val meta = listOfNotNull(tg.coords, tg.size, tg.band).joinToString(" · ")
            TextC(meta, style = t.MonoMicro, color = c.textFaint)
        }
        Spacer(Modifier.width(8.dp))
        TextC(
            tg.max?.let { "$it° max" } ?: "—",
            style = t.MonoMicro, color = tg.max?.let { if (it > 40) c.ok else c.warn } ?: c.textFaint,
        )
    }
}

@Composable
private fun TargetCard(tgt: Target) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    com.nocturne.ui.components.Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(tgt.displayName, style = t.CardTitle, color = c.text)
                TextC(
                    listOfNotNull(tgt.coords, tgt.size).joinToString(" · "),
                    style = t.MonoMicro, color = c.textFaint,
                )
            }
            TextC("${tgt.usable ?: "—"} usable", style = t.MonoMicro, color = c.accent400)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(118.dp),
        ) {
            AltitudeChart(Modifier.fillMaxSize())
            TextC("21:48", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.align(Alignment.BottomStart))
            TextC("now", style = t.MonoMicro, color = c.text, modifier = Modifier.align(Alignment.BottomStart).padding(start = 104.dp))
            TextC("flip", style = t.MonoMicro, color = c.warn, modifier = Modifier.align(Alignment.BottomStart).padding(start = 186.dp))
            TextC("04:12", style = t.MonoMicro, color = c.textFaint, modifier = Modifier.align(Alignment.BottomEnd))
            TextC(
                "max ${tgt.max?.let { "$it°" } ?: "—"} @ ${tgt.peak ?: "—"}", style = t.MonoMicro, color = c.textMuted,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/**
 * The user catalogue — exactly one, name is editable inline, no add/remove
 * catalogues (only one ever exists). Targets within it are freely CRUD-able,
 * minimal fields (name + coords only — no astro engine to compute the rest
 * from yet, see [Target]).
 */
@Composable
private fun UserCatalogSection(state: SimState, ctrl: SessionController, matches: List<Target>) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        BasicTextField(
            value = state.userCatalogName,
            onValueChange = ctrl::setUserCatalogName,
            singleLine = true,
            textStyle = t.Body135.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = RESULT_ROW_HEIGHT * VISIBLE_RESULT_ROWS)
                .verticalScroll(rememberScrollState()),
        ) {
            matches.forEach { tg ->
                UserTargetRow(
                    tg = tg,
                    selected = tg.id == state.targetId,
                    editing = state.editingUserTargetId == tg.id,
                    onSelect = { ctrl.selectTarget(tg.id) },
                    onToggleEdit = { ctrl.toggleEditUserTarget(tg.id) },
                    onSave = { name, coords -> ctrl.editUserTarget(tg.id, name, coords) },
                    onRemove = { ctrl.removeUserTarget(tg.id) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        if (state.addingUserTarget) {
            AddUserTargetForm(onAdd = ctrl::addUserTarget, onCancel = ctrl::cancelAddUserTarget)
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .clickable { ctrl.startAddUserTarget() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Phosphor.Icon(Phosphor.Plus, size = 15.dp, tint = c.accent400)
                Spacer(Modifier.width(8.dp))
                TextC("Add custom target", style = t.Body13, color = c.accent400)
            }
        }
    }
}

@Composable
private fun UserTargetRow(
    tg: Target,
    selected: Boolean,
    editing: Boolean,
    onSelect: () -> Unit,
    onToggleEdit: () -> Unit,
    onSave: (name: String, coords: String) -> Unit,
    onRemove: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.bg, RoundedCornerShape(4.dp)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                TextC(tg.common, style = t.Body13, color = c.text)
                TextC(tg.coords, style = t.MonoMicro, color = c.textFaint)
            }
            IconBtn(icon = Phosphor.X, onClick = onRemove, size = 28, tint = c.danger)
            Spacer(Modifier.width(6.dp))
            IconBtn(icon = if (editing) Phosphor.CaretUp else Phosphor.CaretDown, onClick = onToggleEdit, size = 28)
        }
        if (editing) {
            var name by remember(tg.id) { mutableStateOf(tg.common) }
            var coords by remember(tg.id) { mutableStateOf(tg.coords) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                EditableField("NAME", name) { name = it }
                Spacer(Modifier.height(8.dp))
                EditableField("COORDS", coords) { coords = it }
                Spacer(Modifier.height(8.dp))
                com.nocturne.ui.components.NocturneButton(
                    text = "Save",
                    onClick = { onSave(name, coords) },
                    style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                )
            }
        }
    }
}

@Composable
private fun AddUserTargetForm(onAdd: (name: String, coords: String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        EditableField("NAME", name) { name = it }
        Spacer(Modifier.height(8.dp))
        EditableField("COORDS", coords) { coords = it }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            com.nocturne.ui.components.NocturneButton(
                text = "Cancel",
                onClick = onCancel,
                style = com.nocturne.ui.components.BtnStyle.SUBTLE,
                modifier = Modifier.weight(1f).height(38.dp),
            )
            Spacer(Modifier.width(8.dp))
            com.nocturne.ui.components.NocturneButton(
                text = "Add",
                onClick = { onAdd(name, coords) },
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.weight(1f).height(38.dp),
            )
        }
    }
}

@Composable
private fun EditableField(label: String, value: String, onChange: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(label, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(c.surface, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = t.Mono14.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FramingCard(state: SimState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    // Preserves the prototype's exact -11° pose at the default 118.4° angle; tracks live from there.
    val displayRotation = (-11.0 - (state.rotatorAngle - 118.4)).toFloat()
    // Was a literal "FRAMING · 2600MM + 550MM" (the fixture's own default camera/scope,
    // TrainAssignment("ASI2600MM Pro", "Field APO" @ 550mm) baked in as a static string —
    // never wired to either real or fixture optical-train data since M1's port). Now reflects
    // whichever camera/scope is actually assigned to the primary train.
    val focalMm = state.findScope(state.primaryTrain.scope)?.focalMm
    val cameraLabel = state.primaryTrain.camera.takeIf { it != "None" }
    val framingTitle = buildString {
        append("FRAMING")
        cameraLabel?.let { append(" · $it") }
        focalMm?.let { append(" · ${it}mm") }
    }
    // Real pixel-scale/FOV once the primary camera's CCD_INFO has arrived (EkosRemoteController
    // only) — falls back to the same placeholder readout the card always showed otherwise, so
    // the simulator (and the brief window before CCD_INFO arrives) look unchanged.
    val pixelScale = state.framingPixelScaleArcsecPerPx
    val fovDeg = state.framingFovDeg
    val readout = if (pixelScale != null && fovDeg != null) {
        "%.2f″/px · %.1f°×%.1f°".format(pixelScale, fovDeg.first, fovDeg.second)
    } else {
        "1.24″/px · 2.4°×1.6°"
    }
    com.nocturne.ui.components.Card {
        TextC(framingTitle, style = t.MicroLabel, color = c.textFaint)
        Spacer(Modifier.height(11.2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(c.surfaceDeep, RoundedCornerShape(10.dp)),
        ) {
            HatchBg(Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(196.dp)
                    .height(132.dp)
                    .rotate(displayRotation)
                    .shadow(22.dp, RoundedCornerShape(0.dp), ambientColor = c.accent.copy(alpha = 0.35f), spotColor = c.accent.copy(alpha = 0.35f))
                    .border(1.dp, c.accent, RoundedCornerShape(0.dp)),
            ) {
                TextC(
                    readout,
                    style = t.MonoMicro, color = c.accent400,
                    modifier = Modifier.padding(top = 4.dp, start = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        RotatorRow(angle = state.rotatorAngle, onAngleChange = ctrl::setRotatorAngle)
    }
}

@Composable
private fun RotatorRow(angle: Double, onAngleChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val frac = (angle / 360.0).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Rotator", style = t.Caption, color = c.textMuted, modifier = Modifier.width(56.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(Unit) {
                    fun update(x: Float) {
                        onAngleChange((x / size.width).toDouble().coerceIn(0.0, 1.0) * 360.0)
                    }
                    detectDragGestures(
                        onDragStart = { offset -> update(offset.x) },
                        onDrag = { change, _ -> update(change.position.x) },
                    )
                },
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
        TextC("${"%.1f".format(angle)}°", style = t.Mono13, color = c.text, modifier = Modifier.width(56.dp))
    }
}
