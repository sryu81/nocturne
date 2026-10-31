package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Camera file settings ────────────────────────────────────────────────────

/**
 * Capture-module file settings: save path and filename template (`fileDirectoryT`/
 * `placeholderFormatT`). Opened from the Frames tab. [AppState.wireCaptureSettings] is null until
 * the first `capture_get_all_settings` reply.
 */
@Composable
internal fun CameraSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val cam = state.wireCaptureSettings
    if (cam == null) {
        TextC("Fetching camera settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        FieldLabel("Save path")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = cam.fileDirectoryT,
                onValueChange = ctrl::setCameraSaveDir,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Placeholder format")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = cam.placeholderFormatT,
                onValueChange = ctrl::setCameraPlaceholderFormat,
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(5.dp))
        TextC(
            "Real subfolder/filename template, applied after Save path — e.g. /%t/%T/%F/ (%t target, %T frame type, %F filter).",
            style = t.MonoMicro, color = c.textMuted,
        )
    }
}

/** Integer counterpart of [DegreeField], with the same local text buffer. */
@Composable
internal fun IntField(value: Int, onChange: (Int) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("$value") }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { new ->
                val filtered = new.filter { it.isDigit() }
                text = filtered
                filtered.toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
    }
}
