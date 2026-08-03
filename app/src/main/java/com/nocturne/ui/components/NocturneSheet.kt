package com.nocturne.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

/**
 * Modal sheet — dimmed backdrop, centered panel capped at 420 dp wide,
 * rounded top corners, title row + close, scrollable body, optional footer.
 */
@Composable
fun NocturneSheet(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    fullscreen: Boolean = false,
    meta: String? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(c.bg.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (fullscreen) 1f else 0.92f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            val panelWidth = if (maxWidth > 420.dp) 420.dp else maxWidth
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .background(
                        c.surface,
                        if (fullscreen) RoundedCornerShape(0.dp)
                        else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    )
                    .let { if (fullscreen) it.statusBarsPadding() else it }
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextC(title, style = t.Mono17, color = c.text, modifier = Modifier.weight(1f))
                    if (meta != null) {
                        Spacer(Modifier.width(8.dp))
                        TextC(meta, style = t.TelemetryTiny, color = c.neutral600)
                    }
                    Spacer(Modifier.width(9.dp))
                    IconBtn(icon = Phosphor.X, onClick = onClose, size = 32)
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
                if (footer != null) {
                    Spacer(Modifier.height(14.dp))
                    footer()
                }
            }
        }
    }
}
