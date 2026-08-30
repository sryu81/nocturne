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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.nocturne.session.TARGETS
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
    // M3: a real connection's search bar drives astro_search_objects/astro_get_objects_info
    // (EkosRemoteController) instead of filtering the fixture catalog — see AppState.wireSearchResults.
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

/**
 * **Real bug found live (2026-08-22, user report)**: a bounded results list — [ResultsList]'s
 * matches, [UserCatalogSection]'s custom targets — has its own `verticalScroll` capped to
 * [VISIBLE_RESULT_ROWS] rows, but Compose's default nested-scroll behavior lets any leftover
 * scroll/fling velocity, once that inner list hits its own top/bottom bound, keep going into
 * whatever scrollable contains it — here, the *entire* Plan tab's own single page scroll (see
 * `TabPane`). A strong swipe that reaches the end of a short results list was carrying straight
 * through into flinging the whole page. This connection claims all left-over scroll/fling for
 * itself instead, so hitting the list's own bound is really the end of the gesture.
 */
private fun boundedListNestedScrollConnection() = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Where [instant] falls within [window] as a 0..1 fraction — same units as [AppState.realDayFraction]/[AltitudeChart]'s `realNowFraction`. Not clamped: a caller checking placement should already know the instant belongs inside the window. */
private fun fractionOfWindow(instant: Instant, window: Pair<Instant, Instant>): Double {
    val total = window.second.epochSecond - window.first.epochSecond
    if (total <= 0) return 0.0
    return (instant.epochSecond - window.first.epochSecond).toDouble() / total
}

/**
 * Real per-target altitude data ([AppState.wireTargetRiseset], fetched on demand — see
 * [SessionController.ensureTargetRiseset]) replaces the chart's fixture curve and the "21:48"/
 * "now"/"04:12" fixed literals once it's arrived *for this exact target*; the fetch itself is
 * cheap enough to just re-request every time the framed target changes (`LaunchedEffect(tgt.id)`)
 * rather than tracking staleness here. "flip" has no real data anywhere in this app (see
 * `FlipBanner`'s own doc) so it's simply omitted under a real rig, same as `NightArcCard`.
 *
 * **Real bug found live (2026-08-09)**: `realDayFraction` et al. are `Instant.now()`-based, which
 * Compose has no reason to ever re-evaluate on its own — with no periodic trigger, the "now"
 * position silently freezes at whatever wall-clock time this composable last happened to
 * recompose for some *other* reason (a wire event). Confirmed live: M104 shown "rising" hours
 * after it had actually set, because nothing had touched `state` since it was framed. The `tick`
 * below exists purely to force a redraw every 30s so real time keeps advancing on screen.
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
    // Real absolute daily max/transit — reverted from a night-window-restricted version (M5,
    // docs/STATUS.md) that tried to avoid pairing a daytime peak with "0h 00m usable" (real user
    // confusion, 2026-08-22) by clamping "peak" to the highest sample *within* tonight's dark
    // window. That clamp had its own real bug, found live: [state.realNightWindow]'s dusk/dawn are
    // site-wide, not per-target — for any target whose true transit already precedes dusk (a
    // spring-sky object like M101/M51/M95 by late August, altitude just descending all night), the
    // "highest sample in the window" degenerates to the very first sample after dusk, rounded to
    // the same 30-min grid point for every such target — multiple genuinely different targets all
    // showed the identical dusk-ish time, which is what actually got reported (user's own account
    // named 3 different targets all showing "21:30"). User's call: accept the earlier
    // daytime-peak-vs-0h-usable inconsistency again rather than this — real data, always the
    // target's own actual peak, matches the dashed line [AltitudeChart] itself already draws from
    // the unclamped `realAltitudes` array (that line was never night-clamped even while this text
    // was — the two had already drifted out of sync for exactly the targets reported here).
    val dayMaxAlt = riseset?.altitudes?.maxOrNull()
    val maxAlt = dayMaxAlt?.let { kotlin.math.round(it).toInt() } ?: tgt.max
    val peak = riseset?.transit ?: tgt.peak
    val usable = riseset?.let { state.realUsableSeconds(it) }?.let { formatHm(it) } ?: tgt.usable
    // Real dusk/dawn (same source as the Session tab's night arc) expressed as fractions of this
    // chart's own day window, so they land on the same x-axis realNowFraction/the curve itself
    // use. User-requested addition (2026-08-22): shades which part of the curve is actually
    // observable-dark versus daylight, directly on the chart rather than only in NightPlanBar.
    val nightWindow = state.realNightWindow
    val duskFraction = if (window != null && nightWindow != null) fractionOfWindow(nightWindow.first, window) else null
    val dawnFraction = if (window != null && nightWindow != null) fractionOfWindow(nightWindow.second, window) else null
    // Same peak-sample index AltitudeChart computes internally for its own dashed line — recomputed
    // here (a Canvas draw can't hand a value back up) purely so this label can sit at the same x.
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
        // Real Moon illuminated fraction (user request) — same astro_get_almanac reply this app
        // already fetches for dusk/dawn, MoonIllum was just never decoded before.
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
            // Real y-axis altitude ticks (user-requested, 2026-08-22) — same altitudeToChartY
            // mapping the real curve/horizon/peak/now lines are all drawn with, so a tick's text
            // lands at the exact height its degree value corresponds to on the curve.
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
                window?.let { state.formatSiteTime(it.first) } ?: "21:48",
                style = t.MonoSmall, color = c.textMuted, modifier = Modifier.align(Alignment.BottomStart),
            )
            if (realAltitudes != null && nowFraction != null) {
                TextC(
                    "now ${state.formatSiteTime(Instant.now())}", style = t.Mono115, color = c.text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = maxWidth * nowFraction.toFloat()),
                )
            } else {
                TextC("now", style = t.Mono115, color = c.text, modifier = Modifier.align(Alignment.BottomStart).padding(start = 104.dp))
                TextC("flip", style = t.Mono115, color = c.warn, modifier = Modifier.align(Alignment.BottomStart).padding(start = 186.dp))
            }
            TextC(
                window?.let { state.formatSiteTime(it.second) } ?: "04:12",
                style = t.MonoSmall, color = c.textMuted, modifier = Modifier.align(Alignment.BottomEnd),
            )
            TextC(
                "max ${maxAlt?.let { "$it°" } ?: "—"} @ ${peak ?: "—"}", style = t.Mono115, color = c.text,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            // Time labels for the chart's own dusk/dawn dashed lines (the lines themselves are
            // drawn inside AltitudeChart) — same top-aligned placement so they don't collide with
            // the bottom-row window-edge/now labels.
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
            // Peak label — own row (top = 40.dp, below the dusk/dawn row above) so it doesn't
            // collide when the peak time sits close to either twilight edge on the x-axis.
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
 * The user catalogue — exactly one, name is editable inline, no add/remove
 * catalogues (only one ever exists). Targets within it are freely CRUD-able,
 * minimal fields (name + coords only — no astro engine to compute the rest
 * from yet, see [Target]).
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
    // Box rotation, current: the real CURRENT camera angle from the last solve — solid, c.warn.
    // User correction: this box represents where the camera actually is right now, per the
    // solve, not where it's meant to go. Prefers wireAlignSolution.PA (M5, docs/STATUS.md) — the
    // real solved PA from `new_align_state`'s solution field, sent on *every* successful solve
    // unconditionally — over wireRotatorCurrentPA, which only ever arrives when rotator_control
    // is on (confirmed false by default on this rig). Falls back to the rotator push if a solve's
    // solution hasn't landed yet but a rotator-diff push somehow has. 0° (unrotated) until either
    // has arrived this connection — honest "unknown yet" default, not a guess.
    val currentAngleRotation = (state.wireAlignSolution?.PA ?: state.wireRotatorCurrentPA)?.toFloat() ?: 0f
    // Box rotation, target: the slider's own real align_set_target_pa value — dashed, c.accent,
    // shown separately from the current box above (both were being conflated into one box
    // before this pass — the target-angle box had gone missing entirely). No calibrated offset
    // applied to either — there's no established real correspondence between these angles and
    // Compose's .rotate() direction confirmed yet, same open question as NewPolarState's vector
    // `pa` field.
    val targetAngleRotation = state.rotatorAngle.toFloat()
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
        TextC(framingTitle, style = t.Body135, color = c.textMuted)
        Spacer(Modifier.height(2.dp))
        // Not mandatory (docs/STATUS.md M5) — the whole card is skippable, "Add to sequence"
        // never gates on anything here.
        TextC("optional — skip if you don't need precise framing", style = t.Caption, color = c.textFaint)
        Spacer(Modifier.height(11.2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(c.surfaceDeep, RoundedCornerShape(10.dp)),
        ) {
            // Real DSS reference-image cutout (M5, docs/STATUS.md), NOT the main camera's own
            // captured frame — user's explicit call: framing is about the sky the target sits
            // in, not whatever this session's camera has captured so far. MediaFramePreview's
            // existing ByteArray? overload handles the null-until-fetched/offline case the same
            // honest way it always has (hatch placeholder), no new fallback logic needed here.
            MediaFramePreview(state.referenceImageJpeg, Modifier.fillMaxSize(), hatchColor = c.divider)
            if (state.referenceImageJpeg == null) {
                TextC(
                    if (state.referenceImageForTargetId == tgt.id) "fetching reference image…" else "no reference image yet",
                    style = t.Caption, color = c.textFaint,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            FovOverlayBox(
                rotationDeg = targetAngleRotation,
                aspectW = (fovDeg?.first ?: 246.0).toFloat(),
                aspectH = (fovDeg?.second ?: 166.0).toFloat(),
                color = c.accent,
                dashed = true,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.7f),
            )
            FovOverlayBox(
                rotationDeg = currentAngleRotation,
                aspectW = (fovDeg?.first ?: 246.0).toFloat(),
                aspectH = (fovDeg?.second ?: 166.0).toFloat(),
                color = c.warn,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.7f),
            )
            TextC(
                readout,
                style = t.Mono115, color = c.accent400,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 8.dp),
            )
            // Real solving status (M5, docs/STATUS.md) — wireAlignStatus was decoded but shown
            // nowhere in the app until now; real vocabulary (ekos.h): Idle/Complete/Failed/
            // Aborted/In Progress/Successful/Syncing/Slewing/Rotating/Suspended.
            state.wireAlignStatus?.let {
                TextC(
                    it, style = t.Mono115, color = c.accent400,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TargetAngleRow(angle = state.rotatorAngle, onAngleChange = ctrl::setRotatorAngle)
        Spacer(Modifier.height(8.dp))
        // Lets the user re-solve right from this card to refresh the box above without leaving
        // for Controls tab — same real align_solve/captureAndSolve as AlignSolveCard there.
        NocturneButton(
            text = "Plate solve here",
            onClick = ctrl::plateSolveHere,
            style = BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(38.dp),
        )
    }
}

/** M5 (docs/STATUS.md) — was labeled "Rotator" before this slider was repurposed as the real
 * target-PA control (align_set_target_pa); renamed for what it actually sets now. */
@Composable
private fun TargetAngleRow(angle: Double, onAngleChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val frac = (angle / 360.0).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextC("Target angle", style = t.Caption, color = c.textMuted, modifier = Modifier.width(84.dp))
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
