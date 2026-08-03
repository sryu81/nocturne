package com.nocturne.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.nocturne.ui.icons.Phosphor

/** The five primary destinations, mirroring the prototype's bottom nav. */
enum class NocturneTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Session("session", "Session", Phosphor.Broadcast),
    Plan("plan", "Plan", Phosphor.Crosshair),
    Sequence("sequence", "Sequence", Phosphor.ListChecks),
    Frames("frames", "Frames", Phosphor.ImagesSquare),
    Gear("gear", "Gear", Phosphor.Plugs),
}
