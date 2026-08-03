package com.nocturne.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nocturne.ui.theme.NocturneTheme

/** M0 placeholder — replaced by the full tab UI in M1. */
@Composable
fun TabPlaceholder(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val colors = NocturneTheme.colors
    Box(modifier = modifier.fillMaxSize().padding(NocturneTheme.spacing.s6)) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .background(colors.surface, RoundedCornerShape(NocturneTheme.radius.md))
                .border(1.dp, colors.divider, RoundedCornerShape(NocturneTheme.radius.md))
                .padding(NocturneTheme.spacing.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .background(colors.accent, RoundedCornerShape(2.dp))
                    .padding(horizontal = NocturneTheme.spacing.s4),
            )
            Spacer(Modifier.height(NocturneTheme.spacing.s4))
            androidx.compose.material3.Text(title, style = NocturneTheme.type.Subtitle, color = colors.text)
            Spacer(Modifier.height(NocturneTheme.spacing.s2))
            androidx.compose.material3.Text(
                hint,
                style = NocturneTheme.type.Meta,
                color = colors.textFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}
