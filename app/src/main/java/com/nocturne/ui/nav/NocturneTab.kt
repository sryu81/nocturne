package com.nocturne.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.nocturne.ui.icons.Phosphor

/** The six primary destinations, mirroring the prototype's bottom nav. */
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
    /** Per-module operational settings + live control (Camera/Guide/Mount/Align) — split out
     *  of Gear (which stays rig topology/setup: profile, devices, scopes, trains) once Gear
     *  started accumulating both concerns. See docs/M3.3-plan.md's Addendum. */
    Controls("controls", "Controls", Phosphor.SlidersHorizontal),
}
