package com.nocturne.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.ui.theme.NocturneTheme

/**
 * Like [ConfirmDialog], but for actions where a single accidental tap on the
 * confirm button isn't a strong enough guard (rebooting the rig's Pi
 * mid-session, say) — the confirm button stays disabled until the user
 * types [requiredText] verbatim.
 */
@Composable
fun TypedConfirmDialog(
    title: String,
    message: String,
    requiredText: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: BtnStyle = BtnStyle.DANGER,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var typed by remember { mutableStateOf("") }

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
            Spacer(Modifier.height(14.dp))
            TextC("Type \"$requiredText\" to confirm", style = t.MicroUppercase, color = c.textMuted)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
                    enabled = typed == requiredText,
                    modifier = Modifier.weight(1f).height(44.dp),
                )
            }
        }
    }
}
