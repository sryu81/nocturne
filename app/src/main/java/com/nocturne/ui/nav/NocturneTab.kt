package com.nocturne.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import com.nocturne.ui.icons.Phosphor

/** The six primary destinations, in the order of a night: see docs/PLAN.md plan 1. */
enum class NocturneTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    /** Rig overview; the first page after connecting. */
    Home("home", "Home", Phosphor.Broadcast),
    Gear("gear", "Gear", Phosphor.Plugs),
    /** Per-module settings and live control (Camera/Guide/Mount/Align). Gear holds rig
     *  setup: profile, devices, scopes, trains. */
    Controls("controls", "Controls", Phosphor.SlidersHorizontal),
    Plan("plan", "Plan", Phosphor.Crosshair),
    /** The queue, with the running job's progress above it. */
    Sequence("sequence", "Sequence", Phosphor.ListChecks),
    Frames("frames", "Frames", Phosphor.ImagesSquare),
}
