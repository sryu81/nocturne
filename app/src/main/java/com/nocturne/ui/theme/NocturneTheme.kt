package com.nocturne.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Theme bag handed down through the tree. */
@Immutable
data class NocturneThemeData(
    val colors: NocturneColorScheme,
    val spacing: androidx.compose.ui.unit.Dp,
    val radius: androidx.compose.ui.unit.Dp,
    val shadow: NocturneShadow,
)

val LocalNocturneColors = staticCompositionLocalOf { NocturneDarkColors }
val LocalNocturneSpacing = staticCompositionLocalOf { NocturneSpacing }
val LocalNocturneRadius = staticCompositionLocalOf { NocturneRadius }
val LocalNocturneShadows = staticCompositionLocalOf { NocturneShadows }
val LocalNocturneType = staticCompositionLocalOf { NocturneType }
val LocalRedMode = staticCompositionLocalOf { false }

/**
 * Press feedback for every `clickable` below the theme root. Without a MaterialTheme the default
 * indication is a 30% black overlay shown only while the finger is down — invisible on this dark
 * UI and on quick taps, so taps looked ignored. A ripple in the text color (red in red mode) runs
 * to completion even for a quick tap.
 */
private val TapRippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.12f, hoveredAlpha = 0.08f, pressedAlpha = 0.28f)

/** Theme root — red mode swaps the whole palette below this point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NocturneTheme(redMode: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (redMode) NocturneRedColors else NocturneDarkColors
    CompositionLocalProvider(
        LocalNocturneColors provides colors,
        LocalRedMode provides redMode,
        LocalIndication provides ripple(color = colors.text),
        LocalRippleConfiguration provides RippleConfiguration(color = colors.text, rippleAlpha = TapRippleAlpha),
        content = content,
    )
}

object NocturneTheme {
    val colors: NocturneColorScheme
        @Composable @ReadOnlyComposable get() = LocalNocturneColors.current
    val spacing: NocturneSpacing
        @Composable @ReadOnlyComposable get() = LocalNocturneSpacing.current
    val radius: NocturneRadius
        @Composable @ReadOnlyComposable get() = LocalNocturneRadius.current
    val shadows: List<NocturneShadow>
        @Composable @ReadOnlyComposable get() = LocalNocturneShadows.current
    val type: NocturneType
        @Composable @ReadOnlyComposable get() = LocalNocturneType.current
    val redMode: Boolean
        @Composable @ReadOnlyComposable get() = LocalRedMode.current
}
