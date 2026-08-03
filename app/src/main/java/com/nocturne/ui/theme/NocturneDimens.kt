package com.nocturne.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Spacing scale from `nocturne.css` `--space-*` tokens. */
object NocturneSpacing {
    val s1 = 2.8.dp
    val s2 = 5.6.dp
    val s3 = 8.4.dp
    val s4 = 11.2.dp
    val s6 = 16.8.dp
    val s8 = 22.4.dp
}

/** Radius scale from `nocturne.css` `--radius-*` tokens. */
object NocturneRadius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 14.dp
}

/**
 * Elevation from `nocturne.css` `--shadow-*` tokens: a hairline ring color
 * plus a drop elevation. Rings render as borders; the drop shadows map to
 * Compose elevation.
 */
data class NocturneShadow(
    val ring: Color,
    val elevation: androidx.compose.ui.unit.Dp,
)

/** Shadow definitions. Ring colors from the neutral ramp (see css). */
val NocturneShadows = listOf(
    NocturneShadow(ring = Color(0xFF3F424D), elevation = 0.dp),      // sm
    NocturneShadow(ring = Color(0xFF595D6C), elevation = 6.dp),      // md
    NocturneShadow(ring = Color(0xFF9397AB), elevation = 16.dp),     // lg
)
