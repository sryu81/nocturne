package com.nocturne.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * Module gain field (Capture/Guide/Focus/Align) that understands Ekos's "--": a value at or below
 * [special] ([com.nocturne.session.gainSpecialValue], camera min − step) means "send no gain,
 * keep the driver's own", so it shows as an empty field with a "--" hint instead of the raw
 * 99 / −10. Clearing the field sends [special] back — the same as picking "--" on the desktop.
 * Before the camera's range is known ([special] null) it behaves like a plain [MiniField].
 */
@Composable
fun GainField(value: Double, special: Double?, modifier: Modifier, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val isDefault = special != null && value <= special + 1e-6
    var text by remember(isDefault) { mutableStateOf(if (isDefault) "" else "%.0f".format(value)) }
    Column(modifier) {
        TextC(if (isDefault) "GAIN · DRIVER" else "GAIN", style = t.MonoMicro, color = c.textMuted)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(c.bg, RoundedCornerShape(4.dp))
                .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) TextC("--", style = t.Body13, color = c.textFaint)
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val filtered = new.filter { ch -> ch.isDigit() || ch == '.' }
                    text = filtered
                    if (filtered.isEmpty()) special?.let(onChange) else filtered.toDoubleOrNull()?.let(onChange)
                },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
