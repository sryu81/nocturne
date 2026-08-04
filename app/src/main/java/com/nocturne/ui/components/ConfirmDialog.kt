package com.nocturne.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.ui.theme.NocturneTheme

/**
 * Small centered confirm/cancel dialog for actions that shouldn't fire on a
 * single accidental tap (e.g. Session tab's FLIP NOW / DEFER). Custom-styled
 * rather than Material's AlertDialog — this app has no Material dependency
 * (README §3: "no Material — custom Nocturne theme").
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: BtnStyle = BtnStyle.SOLID,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg.copy(alpha = 0.7f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .padding(horizontal = 32.dp)
                .background(c.surface, RoundedCornerShape(14.dp))
                .border(1.dp, c.divider, RoundedCornerShape(14.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(20.dp),
        ) {
            TextC(title, style = t.CardTitle, color = c.text)
            Spacer(Modifier.height(8.dp))
            TextC(message, style = t.Body13, color = c.textMuted)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                NocturneButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    style = BtnStyle.SUBTLE,
                    modifier = Modifier.weight(1f).height(44.dp),
                )
                Spacer(Modifier.width(8.dp))
                NocturneButton(
                    text = confirmText,
                    onClick = onConfirm,
                    style = confirmStyle,
                    modifier = Modifier.weight(1f).height(44.dp),
                )
            }
        }
    }
}
