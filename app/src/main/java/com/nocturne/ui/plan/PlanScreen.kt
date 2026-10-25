package com.nocturne.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.formatHm
import com.nocturne.session.formatSiteTime
import com.nocturne.session.realDayFraction
import com.nocturne.session.realDayWindow
import com.nocturne.session.realLookupName
import com.nocturne.session.realNightWindow
import com.nocturne.session.realUsableSeconds
import java.time.Instant
import com.nocturne.session.SEARCH_MAG_RANGE
import com.nocturne.session.SEARCH_RESULT_CAP
import com.nocturne.session.SEARCH_TYPES
import com.nocturne.session.Target
import com.nocturne.session.displayName
import com.nocturne.session.findScope
import com.nocturne.session.findTarget
import com.nocturne.session.framingFovDeg
import com.nocturne.session.framingPixelScaleArcsecPerPx
import com.nocturne.ui.components.AltitudeChart
import com.nocturne.ui.components.altitudeToChartY
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.FovOverlayBox
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
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
    state: AppState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
    onGoToSequence: () -> Unit = {},
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val q = state.query.trim().lowercase()
    // Search goes to Ekos (astro_search_objects → astro_get_objects_info); no bundled catalog.
    val matches = state.wireSearchResults.orEmpty()
    val tgt = state.findTarget(state.targetId)
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
            TabItem(full = true) { SearchTypeRow(state, ctrl) },
            TabItem(full = true) {
                // Filters and mag only apply to a typed search; a name search ignores them.
                Row(Modifier.fillMaxWidth().alpha(if (state.searchType == null) 0.4f else 1f)) {
                    com.nocturne.session.PLAN_CHIPS.indices.forEach { i ->
                        PlanChip(
                            text = com.nocturne.session.PLAN_CHIPS[i],
                            selected = state.chips.contains(i),
                            onClick = { ctrl.toggleChip(i) },
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                        )
                    }
                    MagStepper(state, ctrl)
                }
            },
            TabItem(full = true) { ResultsList(state, ctrl, matches) },
            TabItem(full = true) { UserCatalogSection(state, ctrl, userMatches) },
        ) + if (tgt == null) listOf(
            TabItem(full = true) { NoTargetCard() },
        ) else listOf(
            TabItem(full = true) { TargetCard(state, ctrl, tgt) },
            TabItem(full = true) { FramingCard(state, ctrl, tgt) },
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

/** Object-type picker: one `SkyObject::TYPE` per search. Tapping the selected type deselects it,
 *  giving a plain name search across every catalog. */
@Composable
private fun SearchTypeRow(state: AppState, ctrl: SessionController) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        SEARCH_TYPES.forEach { type ->
            PlanChip(
                text = type.label,
                selected = state.searchType == type.skyType,
                onClick = { ctrl.setSearchType(if (state.searchType == type.skyType) null else type.skyType) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * Faintest-magnitude stepper (`maxMagnitude`). Dimmed for planets: the fork applies the limit
 * only to its DSO database query, not to KStars' planet list.
 */
@Composable
private fun MagStepper(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    // No type selected = name search, which has no magnitude filter at all.
    val applies = state.searchType != null &&
        SEARCH_TYPES.firstOrNull { it.skyType == state.searchType }?.usesMagnitude != false
    val mag = state.searchMaxMag
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlanChip(text = "−", selected = false, onClick = { if (applies) ctrl.setSearchMaxMag(mag - 1) })
        TextC(
            "mag ≤$mag",
            style = t.Caption,
            color = if (applies && mag in SEARCH_MAG_RANGE) c.text else c.textFaint,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        PlanChip(text = "+", selected = false, onClick = { if (applies) ctrl.setSearchMaxMag(mag + 1) })
    }
}

/** Shown in place of the target/framing cards until something is picked from the lists above. */
@Composable
private fun NoTargetCard() {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        TextC("No target selected — tap a search result or one of your targets above.", style = t.Body13, color = c.textMuted)
    }
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
            .height(48.dp)
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
    state: AppState,
    ctrl: SessionController,
    matches: List<Target>,
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
                .nestedScroll(remember { boundedListNestedScrollConnection() })
                .verticalScroll(rememberScrollState()),
        ) {
            matches.forEach { tg ->
                TargetRow(tg, selected = tg.id == state.targetId, onClick = { ctrl.selectTarget(tg.id) })
                if (tg != matches.last()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider.copy(alpha = 0.6f)))
                }
            }
        }
        val matchCount = state.wireSearchMatchCount
        TextC(
            when {
                state.searchType == null && state.query.trim().length < 2 && matches.isEmpty() ->
                    "Pick an object type, or type a name to search every catalog"
                matchCount == null && matches.isEmpty() -> "Waiting for Ekos to answer the first search…"
                // Only the direct name lookup hit; it falls outside this type/filter search.
                (matchCount ?: 0) == 0 && matches.isNotEmpty() -> "Name match · outside this type/filter search"
                matchCount == 0 || matchCount == null -> "No matches — try another type, a fainter mag limit, or fewer filters"
                matches.isEmpty() -> "Resolving $matchCount matches…"
                matchCount > SEARCH_RESULT_CAP -> "Brightest ${matches.size} of $matchCount · type to narrow"
                else -> "${matches.size} results · tap to frame"
            },
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

/**
 * Consumes leftover scroll/fling from a bounded inner list so a swipe that reaches the list's
 * end stops there instead of flinging the whole Plan page (Compose's default nested-scroll
 * behavior passes it on to the parent).
 */
private fun boundedListNestedScrollConnection() = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Where [instant] falls within [window] as a 0..1 fraction, same units as [AppState.realDayFraction]. Not clamped. */
private fun fractionOfWindow(instant: Instant, window: Pair<Instant, Instant>): Double {
    val total = window.second.epochSecond - window.first.epochSecond
    if (total <= 0) return 0.0
    return (instant.epochSecond - window.first.epochSecond).toDouble() / total
}

/**
 * Selected target with its altitude chart. Rise/set data is re-fetched whenever the target
 * changes (cheap enough not to track staleness) and used only once it matches this target.
 *
 * `realDayFraction` and friends read `Instant.now()`, which Compose never re-evaluates on its own;
 * `tick` forces a recomposition every 30 s so the "now" marker keeps moving.
 */
@Composable
private fun TargetCard(state: AppState, ctrl: SessionController, tgt: Target) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    LaunchedEffect(tgt.id) { ctrl.ensureTargetRiseset(tgt.id) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val targetName = tgt.realLookupName
    val riseset = state.wireTargetRiseset?.takeIf { it.name == targetName }
    val realAltitudes = riseset?.altitudes?.takeIf { it.size >= 2 }
    val nowFraction = state.realDayFraction
    val window = state.realDayWindow
    // The target's absolute daily max/transit, deliberately not clamped to tonight's dark window:
    // for a target that transits before dusk, a clamped peak collapses to the first sample after
    // dusk, so different targets all show the same time. It also matches AltitudeChart's peak line.
    val dayMaxAlt = riseset?.altitudes?.maxOrNull()
    val maxAlt = dayMaxAlt?.let { kotlin.math.round(it).toInt() } ?: tgt.max
    val peak = riseset?.transit ?: tgt.peak
    val usable = riseset?.let { state.realUsableSeconds(it) }?.let { formatHm(it) } ?: tgt.usable
    // Dusk/dawn as fractions of the chart's day window, so the dark part of the curve is shaded
    // on the same x-axis as the curve and the "now" marker.
    val nightWindow = state.realNightWindow
    val duskFraction = if (window != null && nightWindow != null) fractionOfWindow(nightWindow.first, window) else null
    val dawnFraction = if (window != null && nightWindow != null) fractionOfWindow(nightWindow.second, window) else null
    // Same peak index AltitudeChart computes for its dashed line; recomputed here because a Canvas
    // draw can't hand a value back up, so the label can sit at the same x.
    val peakFraction = realAltitudes?.withIndex()?.maxByOrNull { it.value }?.index
        ?.let { idx -> idx.toDouble() / (realAltitudes.size - 1) }
    com.nocturne.ui.components.Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TextC(tgt.displayName, style = t.CardTitle, color = c.text)
                TextC(
                    listOfNotNull(tgt.coords, tgt.size).joinToString(" · "),
                    style = t.MonoSmall, color = c.textMuted,
                )
            }
            TextC("${usable ?: "—"} usable", style = t.Mono115, color = c.accent400)
        }
        // MoonIllum comes from the same astro_get_almanac reply as dusk/dawn.
        state.wireMoonIllum?.let {
            Spacer(Modifier.height(4.dp))
            TextC("Moon ${kotlin.math.round(it * 100).toInt()}% illuminated", style = t.MonoSmall, color = c.textMuted)
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(176.dp),
        ) {
            AltitudeChart(
                Modifier.fillMaxSize(), realAltitudes = realAltitudes, realNowFraction = nowFraction,
                realDuskFraction = duskFraction, realDawnFraction = dawnFraction,
                moonAltitudes = state.wireMoonRiseset?.altitudes?.takeIf { it.size >= 2 },
            )
            // Y-axis ticks use the chart's own altitudeToChartY mapping so they line up with the curve.
            if (realAltitudes != null) {
                listOf(0, 30, 60, 90).forEach { deg ->
                    val yFraction = altitudeToChartY(deg.toDouble()) / 118f
                    TextC(
                        "$deg°", style = t.MonoMicro, color = c.textFaint,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = (maxHeight * yFraction - 6.dp).coerceAtLeast(0.dp)),
                    )
                }
            }
            TextC(
                window?.let { state.formatSiteTime(it.first) } ?: "N/A",
                style = t.MonoSmall, color = c.textMuted, modifier = Modifier.align(Alignment.BottomStart),
            )
            if (realAltitudes != null && nowFraction != null) {
                TextC(
                    "now ${state.formatSiteTime(Instant.now())}", style = t.Mono115, color = c.text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = maxWidth * nowFraction.toFloat()),
                )
            }
            TextC(
                window?.let { state.formatSiteTime(it.second) } ?: "N/A",
                style = t.MonoSmall, color = c.textMuted, modifier = Modifier.align(Alignment.BottomEnd),
            )
            TextC(
                "max ${maxAlt?.let { "$it°" } ?: "—"} @ ${peak ?: "—"}", style = t.Mono115, color = c.text,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // Labels for AltitudeChart's dusk/dawn lines, top-aligned to stay clear of the bottom row.
            if (duskFraction != null && nightWindow != null) {
                TextC(
                    "dusk ${state.formatSiteTime(nightWindow.first)}", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = (maxWidth * duskFraction.toFloat() - 20.dp).coerceAtLeast(0.dp), top = 20.dp),
                )
            }
            if (dawnFraction != null && nightWindow != null) {
                TextC(
                    "dawn ${state.formatSiteTime(nightWindow.second)}", style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = (maxWidth * dawnFraction.toFloat() - 20.dp).coerceAtLeast(0.dp), top = 20.dp),
                )
            }
            // Own row below dusk/dawn so it can't collide when the peak is near either twilight.
            if (peakFraction != null) {
                TextC(
                    "peak ${peak ?: "—"}", style = t.MonoMicro, color = c.textMuted,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = (maxWidth * peakFraction.toFloat() - 20.dp).coerceAtLeast(0.dp), top = 40.dp),
                )
            }
        }
    }
}

/**
 * The single user catalogue: inline-editable name, targets with name + coords only (nothing
 * computes the other [Target] fields for them).
 */
@Composable
private fun UserCatalogSection(state: AppState, ctrl: SessionController, matches: List<Target>) {
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
                .nestedScroll(remember { boundedListNestedScrollConnection() })
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
                .height(40.dp)
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
private fun FramingCard(state: AppState, ctrl: SessionController, tgt: Target) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    LaunchedEffect(tgt.id) { ctrl.ensureReferenceImage(tgt.id) }
    // Solid box: where the camera is now, per the last solve. The align solution's PA comes with
    // every successful solve; the rotator push only arrives when rotator_control is on (off by
    // default). 0° until either arrives.
    val currentAngleRotation = (state.wireAlignSolution?.PA ?: state.wireRotatorCurrentPA)?.toFloat() ?: 0f
    // Dashed box: the target PA from the slider (align_set_target_pa). Neither box applies an
    // offset; the sign convention between these angles and Compose's .rotate() is unverified.
    val targetAngleRotation = state.rotatorAngle.toFloat()
    val focalMm = state.findScope(state.primaryTrain.scope)?.focalMm
    val cameraLabel = state.primaryTrain.camera.takeIf { it != "None" }
    val framingTitle = buildString {
        append("FRAMING")
        cameraLabel?.let { append(" · $it") }
        focalMm?.let { append(" · ${it}mm") }
    }
    // Pixel scale/FOV need the primary camera's CCD_INFO; a placeholder readout shows until then.
    val pixelScale = state.framingPixelScaleArcsecPerPx
    val fovDeg = state.framingFovDeg
    val readout = if (pixelScale != null && fovDeg != null) {
        "%.2f″/px · %.1f°×%.1f°".format(pixelScale, fovDeg.first, fovDeg.second)
    } else {
        "N/A"
    }
    com.nocturne.ui.components.Card {
        TextC(framingTitle, style = t.Body135, color = c.textMuted)
        Spacer(Modifier.height(2.dp))
        // Framing is optional; "Add to sequence" never gates on anything here.
        TextC("optional — skip if you don't need precise framing", style = t.Caption, color = c.textFaint)
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextC(readout, style = t.Mono115, color = c.accent400, modifier = Modifier.weight(1f))
            // Align status, ekos.h vocabulary: Idle/Complete/Failed/Aborted/In Progress/
            // Successful/Syncing/Slewing/Rotating/Suspended.
            state.wireAlignStatus?.let { TextC(it, style = t.Mono115, color = c.accent400) }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.surfaceDeep),
        ) {
            // A DSS reference cutout, not the camera's last frame: framing is about the sky around
            // the target. Shows a hatch placeholder until fetched.
            MediaFramePreview(state.referenceImageJpeg, Modifier.fillMaxSize(), hatchColor = c.divider)
            if (state.referenceImageJpeg == null) {
                TextC(
                    when {
                        state.referenceImageOnlineSkipped -> "not in offline atlas · online lookup off on rig network"
                        state.referenceImageForTargetId == tgt.id -> "fetching reference image…"
                        else -> "no reference image yet"
                    },
                    style = t.Caption, color = c.textFaint,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            // The cutout is square and fitted to the box's height, so the FOV boxes live in a
            // square of the same size; their width is the camera FOV as a fraction of the
            // cutout's. Skipped until both fields of view are known rather than drawing a guess.
            val cutoutFov = state.referenceImageFovDeg
            if (fovDeg != null && cutoutFov != null && state.referenceImageJpeg != null) {
                Box(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(1f)) {
                    val widthFraction = (fovDeg.first / cutoutFov).toFloat().coerceIn(0.05f, 1f)
                    FovOverlayBox(
                        rotationDeg = targetAngleRotation,
                        aspectW = fovDeg.first.toFloat(),
                        aspectH = fovDeg.second.toFloat(),
                        color = c.accent,
                        dashed = true,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(widthFraction),
                    )
                    FovOverlayBox(
                        rotationDeg = currentAngleRotation,
                        aspectW = fovDeg.first.toFloat(),
                        aspectH = fovDeg.second.toFloat(),
                        color = c.warn,
                        modifier = Modifier.align(Alignment.Center).fillMaxWidth(widthFraction),
                    )
                }
            }
            if (state.referenceImageJpeg != null) {
                TextC(
                    if (state.referenceImageOffline) "DSS2 · offline atlas" else "DSS2 · CDS online",
                    style = t.MonoMicro, color = c.textFaint,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp, start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TargetAngleRow(angle = state.rotatorAngle, onAngleChange = ctrl::setRotatorAngle)
        Spacer(Modifier.height(8.dp))
        // Re-solve from here to refresh the current box; same captureAndSolve as the Controls tab.
        NocturneButton(
            text = "Plate solve here",
            onClick = ctrl::plateSolveHere,
            style = BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(38.dp),
        )
    }
}

/** Target position angle slider (align_set_target_pa), 0–360°. */
@Composable
private fun TargetAngleRow(angle: Double, onAngleChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val frac = (angle / 360.0).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Target angle", style = t.Caption, color = c.textMuted, modifier = Modifier.width(96.dp))
        // The knob is inset by its radius so it isn't clipped at either end of the track.
        Canvas(
            Modifier
                .weight(1f)
                .height(28.dp)
                .pointerInput(Unit) {
                    val inset = 7.dp.toPx()
                    fun update(x: Float) {
                        onAngleChange(((x - inset) / (size.width - 2 * inset)).toDouble().coerceIn(0.0, 1.0) * 360.0)
                    }
                    detectDragGestures(
                        onDragStart = { offset -> update(offset.x) },
                        onDrag = { change, _ -> update(change.position.x) },
                    )
                },
        ) {
            val y = size.height / 2f
            val inset = 7.dp.toPx()
            val trackH = 4.dp.toPx()
            val knobX = inset + (size.width - 2 * inset) * frac
            drawRoundRect(
                color = c.divider,
                topLeft = androidx.compose.ui.geometry.Offset(inset, y - trackH / 2),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )
            drawRoundRect(
                color = c.accent,
                topLeft = androidx.compose.ui.geometry.Offset(inset, y - trackH / 2),
                size = androidx.compose.ui.geometry.Size(knobX - inset, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
            )
            drawCircle(c.text, radius = 7.dp.toPx(), center = androidx.compose.ui.geometry.Offset(knobX, y))
        }
        Spacer(Modifier.width(8.dp))
        TextC("${"%.1f".format(angle)}°", style = t.Mono13, color = c.text, modifier = Modifier.width(56.dp))
    }
}
