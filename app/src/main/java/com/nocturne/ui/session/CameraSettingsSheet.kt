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
import com.nocturne.session.CaptureLayout
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Camera file settings ────────────────────────────────────────────────────

/**
 * Where frames are saved. Fixed by [CaptureLayout] (`docs/PLAN.md`, "File layout rule"), so this
 * only shows the rule. [AppState.wireCaptureSettings] is not needed.
 */
@Composable
internal fun CameraSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    Column {
        FieldLabel("Save location")
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            TextC(CaptureLayout.ROOT, style = t.Body13, color = c.text)
        }
        Spacer(Modifier.height(11.2.dp))
        FieldLabel("Folders")
        Spacer(Modifier.height(5.dp))
        TextC(
            "<Target> / <night> / <Type> / <Filter> / <Target>_<Type>_<Filter>_<exposure>_<time>_<n>.fits",
            style = t.MonoSmall, color = c.text,
        )
        Spacer(Modifier.height(8.dp))
        TextC(
            "The night is the date it began, so frames after midnight stay with the evening before. " +
                "The same layout is used on every USB drive, under its own Nocturne folder. The path is " +
                "fixed so the Rig storage file manager can find, copy and move whole nights.",
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
