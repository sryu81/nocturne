package com.nocturne.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nocturne.data.FrameEntity
import com.nocturne.session.AppState
import com.nocturne.session.FrameCategory
import com.nocturne.session.SessionController
import com.nocturne.session.formatDecDegrees
import com.nocturne.session.formatRaHours
import com.nocturne.session.keepCount
import com.nocturne.session.rejectCount
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HfrRunChart
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.MediaFramePreviewFile
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIMESTAMP_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/**
 * Real capture frames, Room-backed via [FrameEntity]/`state.`[AppState.frameRows] (M4.3) —
 * replaces the honest "not available (M4)" placeholder that stood here since the simulator-removal
 * pass (2026-08-22). Navigation restructured M4.5 Part C: top-level Preview/Plan picker, Plan
 * drills into a per-target list before a target's own grid — [FrameEntity.target] is null for a
 * Preview/test capture, real for a Plan one (`AppState.activeFrameSource`).
 */
@Composable
fun FramesScreen(
    state: AppState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewRows = state.frameRows.filter { it.target == null }
    val planRows = state.frameRows.filter { it.target != null }

    when {
        state.frameCategory == null -> CategoryPicker(previewRows.size, planRows.size, ctrl, landscape, modifier)
        state.frameCategory == FrameCategory.PREVIEW -> FrameListScreen(
            title = "Preview",
            rows = previewRows,
            ctrl = ctrl,
            landscape = landscape,
            modifier = modifier,
            onBack = { ctrl.selectFrameCategory(null) },
        )
        state.frameTarget == null -> TargetListScreen(planRows, ctrl, landscape, modifier)
        else -> {
            val target = state.frameTarget
            FrameListScreen(
                title = target,
                rows = planRows.filter { it.target == target },
                ctrl = ctrl,
                landscape = landscape,
                modifier = modifier,
                onBack = { ctrl.selectFrameTarget(null) },
            )
        }
    }
}

@Composable
private fun CategoryPicker(
    previewCount: Int,
    planCount: Int,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier,
) {
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) {
                CategoryCard(
                    icon = Phosphor.Camera,
                    title = "Preview",
                    sub = "$previewCount test capture${if (previewCount == 1) "" else "s"}",
                    onClick = { ctrl.selectFrameCategory(FrameCategory.PREVIEW) },
                )
            },
            TabItem(full = true) {
                CategoryCard(
                    icon = Phosphor.Target,
                    title = "Plan",
                    sub = "$planCount real target capture${if (planCount == 1) "" else "s"}",
                    onClick = { ctrl.selectFrameCategory(FrameCategory.PLAN) },
                )
            },
        ),
    )
}

@Composable
private fun CategoryCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Phosphor.Icon(icon, size = 22.dp, tint = c.accent400)
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC(title, style = t.Body135, color = c.text)
                TextC(sub, style = t.Body13, color = c.textMuted)
            }
            Phosphor.Icon(Phosphor.CaretRight, size = 18.dp, tint = c.textFaint)
        }
    }
}

/** Shared back-navigation header for both a target's own grid and the flat Preview list. */
@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack), verticalAlignment = Alignment.CenterVertically) {
        Phosphor.Icon(Phosphor.CaretLeft, size = 18.dp, tint = c.textMuted)
        Spacer(Modifier.width(6.dp))
        TextC(title, style = t.Body135, color = c.text)
    }
}

@Composable
private fun TargetListScreen(planRows: List<FrameEntity>, ctrl: SessionController, landscape: Boolean, modifier: Modifier) {
    val byTarget = planRows.groupBy { it.target!! }
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
            add(TabItem(full = true) { BackHeader("Plan", onBack = { ctrl.selectFrameCategory(null) }) })
            if (byTarget.isEmpty()) {
                add(
                    TabItem(full = true) {
                        Card {
                            TextC("No target captures yet", style = NocturneTheme.type.Body135, color = NocturneTheme.colors.text)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                            TextC(
                                "Real captures land here once a scheduler job is actually imaging.",
                                style = NocturneTheme.type.Body13, color = NocturneTheme.colors.textMuted,
                            )
                        }
                    },
                )
            }
            byTarget.forEach { (target, rows) ->
                add(TabItem(full = true) { TargetRow(target, rows, onClick = { ctrl.selectFrameTarget(target) }) })
            }
        },
    )
}

@Composable
private fun TargetRow(target: String, rows: List<FrameEntity>, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    // rows is a slice of frameRows, itself already newest-first.
    val latest = rows.first()
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.surfaceDeep),
            ) {
                MediaFramePreviewFile(latest.filePath, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                TextC(target, style = t.Body135, color = c.text)
                val coords = if (latest.targetRA != null && latest.targetDEC != null) {
                    "${formatRaHours(latest.targetRA)} ${formatDecDegrees(latest.targetDEC)}"
                } else {
                    "coordinates unknown"
                }
                TextC(coords, style = t.MonoMicro, color = c.textMuted)
                TextC("${rows.size} frame${if (rows.size == 1) "" else "s"}", style = t.Body13, color = c.textMuted)
            }
            Phosphor.Icon(Phosphor.CaretRight, size = 18.dp, tint = c.textFaint)
        }
    }
}

@Composable
private fun FrameListScreen(
    title: String,
    rows: List<FrameEntity>,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
            add(TabItem(full = true) { BackHeader(title, onBack) })
            if (rows.isEmpty()) {
                add(
                    TabItem(full = true) {
                        Card {
                            TextC("No frames yet", style = t.Body135, color = c.text)
                            Spacer(Modifier.height(4.dp))
                            TextC(
                                "Real captures land here as they arrive over the Media channel.",
                                style = t.Body13, color = c.textMuted,
                            )
                        }
                    },
                )
            } else {
                add(
                    TabItem(full = true) {
                        Column {
                            TextC(
                                "${rows.count { it.keep }} kept · ${rows.count { !it.keep }} cut",
                                style = t.MonoSmall, color = c.textMuted,
                            )
                            Spacer(Modifier.height(8.dp))
                            // Newest-last so the run reads left-to-right in capture order — rows
                            // itself is newest-first (grid display order).
                            HfrRunChart(
                                values = rows.mapNotNull { it.hfr?.toFloat() }.asReversed(),
                                modifier = Modifier.fillMaxWidth().height(60.dp),
                            )
                        }
                    },
                )
                add(TabItem(full = true) { FrameGrid(rows, ctrl) })
            }
        },
    )
}

@Composable
private fun FrameGrid(rows: List<FrameEntity>, ctrl: SessionController) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { f ->
                    FrameThumb(f, Modifier.weight(1f)) { ctrl.expandFrame(f.id.toString()) }
                }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FrameThumb(f: FrameEntity, modifier: Modifier, onClick: () -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(c.surfaceDeep)
            .clickable(onClick = onClick),
    ) {
        MediaFramePreviewFile(f.filePath, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
        if (!f.keep) {
            Box(Modifier.fillMaxSize().background(c.danger.copy(alpha = 0.28f)))
        }
        f.hfr?.let {
            TextC(
                "%.2f".format(it), style = t.MonoMicro, color = c.text,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(c.bg.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Full-screen frame preview — tap anywhere, the close button, or system back dismisses it.
 * Info panel (M4.5 Part C) shows every real field this row actually carries — timestamp, real
 * target/filter/coordinates for a Plan capture (absent entirely for a Preview/test one, not shown
 * as a blank/zero), exposure/gain/bin/resolution, mean/median/stddev, HFR, and an honest "not
 * solved yet" placeholder for the M4.5 Part B solver, not implemented yet.
 */
@Composable
fun FrameExpandOverlay(state: AppState, ctrl: SessionController) {
    val frame = state.frameRows.firstOrNull { it.id.toString() == state.expandedFrameId } ?: return
    androidx.activity.compose.BackHandler(onBack = ctrl::closeFrameExpand)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(Modifier.fillMaxSize().background(c.surfaceDeep)) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = ctrl::closeFrameExpand,
                ),
        ) {
            MediaFramePreviewFile(frame.filePath, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            TextC(
                // Real on-disk filename (M4.5) — the actual Preview/Plan path, not a fabricated label.
                File(frame.filePath).name, style = t.Mono17, color = c.textFaint,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }
        IconBtn(
            icon = Phosphor.X,
            onClick = ctrl::closeFrameExpand,
            size = 34,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(c.surfaceDeep.copy(alpha = 0.92f))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextC(
                    frame.hfr?.let { "HFR %.2f".format(it) } ?: "HFR —", style = t.Mono34,
                    color = if ((frame.hfr ?: 0.0) > 2.8) c.danger else c.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .background(if (!frame.keep) c.danger else c.accent, CircleShape)
                        .clickable { ctrl.toggleCut(frame.id.toString()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Phosphor.Icon(if (!frame.keep) Phosphor.XFill else Phosphor.CheckFill, size = 24.dp, tint = c.surfaceDeep)
                }
            }
            Spacer(Modifier.height(11.2.dp))
            FrameInfoRows(frame)
        }
    }
}

/** One label/value row for [FrameExpandOverlay]'s info panel — null [value] means the row isn't shown at all. */
@Composable
private fun InfoRow(label: String, value: String?) {
    if (value == null) return
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        TextC(label, style = t.MonoMicro, color = c.textFaint, modifier = Modifier.width(90.dp))
        TextC(value, style = t.MonoSmall, color = c.text)
    }
}

@Composable
private fun FrameInfoRows(frame: FrameEntity) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    InfoRow("CAPTURED", TIMESTAMP_FMT.format(Date(frame.timestampMs)))
    if (frame.target != null) {
        InfoRow("TARGET", frame.target)
        val coords = if (frame.targetRA != null && frame.targetDEC != null) {
            "${formatRaHours(frame.targetRA)} ${formatDecDegrees(frame.targetDEC)}"
        } else {
            null
        }
        InfoRow("COORDS", coords)
        InfoRow("FILTER", frame.filter ?: "none")
    }
    InfoRow(
        "CAPTURE",
        listOfNotNull(
            frame.exposure?.let { "${it}s" },
            frame.gain?.let { "g$it" },
            frame.bin?.let { "bin $it" },
            frame.resolution,
        ).joinToString(" · ").ifBlank { null },
    )
    InfoRow(
        "STATS",
        listOfNotNull(
            frame.mean?.let { "mean %.1f".format(it) },
            frame.median?.let { "median %.1f".format(it) },
            frame.stddev?.let { "stddev %.1f".format(it) },
        ).joinToString(" · ").ifBlank { null },
    )
    Spacer(Modifier.height(2.dp))
    // Honest placeholder — M4.5 Part B (offline coarse solver) isn't implemented yet; no
    // fabricated RA/Dec/confidence number invented ahead of the real feature landing.
    TextC("SOLVER: not solved yet", style = t.MonoMicro, color = c.textFaint)
}
