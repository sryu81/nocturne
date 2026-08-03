package com.nocturne.ui.theme

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

/** Theme root — red mode swaps the whole palette below this point. */
@Composable
fun NocturneTheme(redMode: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalNocturneColors provides if (redMode) NocturneRedColors else NocturneDarkColors,
        LocalRedMode provides redMode,
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
