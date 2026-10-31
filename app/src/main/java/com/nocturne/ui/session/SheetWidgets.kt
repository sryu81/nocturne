package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Shared bits ──────────────────────────────────────────────────────────

@Composable
internal fun Panel(content: @Composable () -> Unit) {
    val c = NocturneTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) { content() }
}

@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textMuted)
        TextC(value, style = t.Mono17, color = c.text)
    }
}

@Composable
internal fun SumStat(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(
        modifier
            .background(c.bg, RoundedCornerShape(4.dp))
            .padding(11.2.dp),
    ) {
        TextC(label, style = t.MicroLabel, color = c.textMuted)
        TextC(value, style = t.Mono20, color = c.text)
        if (sub != null) {
            TextC(sub, style = t.MonoMicro, color = c.textFaint)
        }
    }
}

/** Shared field-label style for sheet forms. */
@Composable
internal fun FieldLabel(text: String) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    TextC(text, style = t.CaptionMedium, color = c.textDim)
}
