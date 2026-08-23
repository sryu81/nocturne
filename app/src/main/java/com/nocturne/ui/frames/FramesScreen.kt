package com.nocturne.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.SimState
import com.nocturne.session.frames
import com.nocturne.ui.components.Card
import com.nocturne.ui.components.HatchBg
import com.nocturne.ui.components.IconBtn
import com.nocturne.ui.components.TabItem
import com.nocturne.ui.components.TabPane
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

/**
 * Real bug found while removing the app's simulator mode (2026-08-22, see
 * docs/simulator-removal-plan.md): this whole tab rendered fixture frames (`FRAME_IDS`/
 * `FRAME_HFRS`, `SimState.frames`) unconditionally, with zero disclosure — worse than the other
 * M4-blocked features (`StatsRow`'s HFR/RMS/SNR, `SubPreview`) which at least say "not available
 * (M4)" once connected to a real rig. No real per-frame data exists anywhere on the wire (blocked
 * on the Media channel, same M4 gap) — matching that same honest-placeholder pattern here instead
 * of a decorative grid that looks like real captured frames.
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
    TabPane(
        landscape = landscape,
        modifier = modifier,
        items = listOf(
            TabItem(full = true) {
                Card {
                    TextC("Frames aren't available yet", style = t.Body135, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    TextC(
                        "Needs the Media channel (M4) — per-frame previews/HFR don't exist on the wire yet.",
                        style = t.Body13, color = c.textMuted,
                    )
                }
            },
        ),
    )
}

/** Full-screen frame preview — tap anywhere, the close button, or system back dismisses it. */
@Composable
fun FrameExpandOverlay(state: SimState, ctrl: SessionController) {
    val frame = state.frames.firstOrNull { it.id == state.expandedFrameId } ?: return
    androidx.activity.compose.BackHandler(onBack = ctrl::closeFrameExpand)
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val cut = frame.cut
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
        HatchBg(Modifier.fillMaxSize(), color = Color(0xFF2B2D38))
        TextC(
            "sub_${frame.id}.fits", style = t.Mono17, color = c.textFaint,
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
                "HFR ${String.format("%.2f", frame.hfr)}", style = t.Mono34,
                color = if (frame.hfr > 2.8) c.danger else c.text,
            )
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .background(if (cut) c.danger else c.accent, CircleShape)
                    .clickable { ctrl.toggleCut(frame.id) },
                contentAlignment = Alignment.Center,
            ) {
                Phosphor.Icon(if (cut) Phosphor.XFill else Phosphor.CheckFill, size = 24.dp, tint = c.surfaceDeep)
            }
        }
    }
}

