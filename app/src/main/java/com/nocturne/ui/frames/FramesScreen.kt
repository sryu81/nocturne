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
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.keepCount
import com.nocturne.session.rejectCount
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HfrRunChart
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

/**
 * Real capture frames grid (M4.3), Room-backed via [FrameEntity]/[state].[SimState.frameRows] —
 * replaces the honest "not available (M4)" placeholder that stood here since the simulator-removal
 * pass (2026-08-22). Empty until a real capture actually lands (routed by
 * [com.nocturne.session.EkosRemoteController] off the Media channel's `uuid == ""` frames, M4.1).
 */
@Composable
fun FramesScreen(
    state: SimState,
    ctrl: SessionController,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val rows = state.frameRows
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = buildList {
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
                                "${state.keepCount} kept · ${state.rejectCount} cut",
                                style = t.MonoSmall, color = c.textMuted,
                            )
                            Spacer(Modifier.height(8.dp))
                            // Newest-last so the run reads left-to-right in capture order —
                            // frameRows itself is newest-first (grid display order).
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
        MediaFramePreview(f.jpeg, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
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

/** Full-screen frame preview — tap anywhere, the close button, or system back dismisses it. */
@Composable
fun FrameExpandOverlay(state: SimState, ctrl: SessionController) {
    val frame = state.frameRows.firstOrNull { it.id.toString() == state.expandedFrameId } ?: return
    androidx.activity.compose.BackHandler(onBack = ctrl::closeFrameExpand)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier
            .fillMaxSize()
            .background(c.surfaceDeep)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = ctrl::closeFrameExpand,
            ),
    ) {
        MediaFramePreview(frame.jpeg, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
        TextC(
            "frame_${frame.id}.jpg", style = t.Mono17, color = c.textFaint,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
        IconBtn(
            icon = Phosphor.X,
            onClick = ctrl::closeFrameExpand,
            size = 34,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextC(
                frame.hfr?.let { "HFR %.2f".format(it) } ?: "HFR —", style = t.Mono34,
                color = if ((frame.hfr ?: 0.0) > 2.8) c.danger else c.text,
            )
            Spacer(Modifier.width(16.dp))
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
    }
}
