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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
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
import com.nocturne.ui.components.AltitudeChart
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.PlanChip
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

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
    val matches = TARGETS.filter { tg ->
        if (q.isNotEmpty() && !"${tg.id} ${tg.common} ${tg.coords}".lowercase().contains(q)) return@filter false
        if (state.chips.contains(1) && tg.max <= 40) return@filter false
        if (state.chips.contains(2) && !Regex("Ha|SHO|OIII").containsMatchIn(tg.band)) return@filter false
        if (state.chips.contains(3) && tg.fov == 0) return@filter false
        true
    }
    val tgt = TARGETS.firstOrNull { it.id == state.targetId } ?: TARGETS[0]

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
            TabItem(full = true) { ResultsList(state, ctrl, matches) },
            TabItem(full = true) { TargetCard(tgt) },
            TabItem(full = true) { FramingCard(state, ctrl) },
            TabItem(full = true) {
                Row(Modifier.fillMaxWidth()) {
                    com.nocturne.ui.components.NocturneButton(
                        text = "Add to sequence",
                        onClick = {
                            ctrl.addToSequence(tgt.id)
                            onGoToSequence()
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                    )
                    Spacer(Modifier.width(8.4.dp))
                    com.nocturne.ui.components.NocturneButton(
                        text = "Slew & center",
                        onClick = {},
                        icon = Phosphor.Crosshair,
                        modifier = Modifier.weight(1f).height(44.dp),
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
    matches: List<com.nocturne.session.Target>,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp)),
    ) {
        matches.forEach { tg ->
            val selected = tg.id == state.targetId
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { ctrl.selectTarget(tg.id) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    TextC("${tg.id} — ${tg.common}", style = t.Body13, color = c.text)
                    TextC(
                        "${tg.coords} · ${tg.size} · ${tg.band}",
                        style = t.MonoMicro, color = c.textFaint,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextC(
                    "${tg.max}° max",
                    style = t.MonoMicro, color = if (tg.max > 40) c.ok else c.warn,
                )
            }
            if (tg != matches.last()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider.copy(alpha = 0.6f)))
            }
        }
        TextC(
            "${matches.size} of ${TARGETS.size} · tap to frame",
            style = t.MonoMicro, color = c.textFaint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TargetCard(tgt: com.nocturne.session.Target) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    com.nocturne.ui.components.Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(tgt.id, style = t.CardTitle, color = c.text)
                TextC("${tgt.coords} · ${tgt.size}", style = t.MonoMicro, color = c.textFaint)
            }
            TextC("${tgt.usable} usable", style = t.MonoMicro, color = c.accent400)
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
                "max ${tgt.max}° @ ${tgt.peak}", style = t.MonoMicro, color = c.textMuted,
                modifier = Modifier.align(Alignment.TopEnd),
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
    com.nocturne.ui.components.Card {
        TextC("FRAMING · 2600MM + 550MM", style = t.MicroLabel, color = c.textFaint)
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
                    "1.24″/px · 2.4°×1.6°",
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
