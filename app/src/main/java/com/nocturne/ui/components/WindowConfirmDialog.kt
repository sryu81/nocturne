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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nocturne.ui.theme.NocturneTheme

/**
 * Like [ConfirmDialog], but in its own window, for call sites inside a scrolling sheet (the rig
 * reboot/shutdown buttons), where an inline overlay renders as more sheet content. It used to make
 * the user type REBOOT or SHUTDOWN; that was dropped as more annoying than protective, since the
 * dialog already names the action and Cancel is the default way out.
 */
@Composable
fun WindowConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: BtnStyle = BtnStyle.DANGER,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    // Own window, not an inline overlay: the reboot call site lives inside a scrolling sheet,
    // where a fillMaxSize scrim just renders as more sheet content instead of on top of it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg.copy(alpha = 0.7f))
                .imePadding()
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
}
