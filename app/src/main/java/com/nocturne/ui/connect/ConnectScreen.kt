package com.nocturne.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.TextC
import com.nocturne.ui.components.VSpacer
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme

/**
 * First-launch and manual-reconnect screen. Shown whenever
 * [SessionViewModel.ConnectionMode] is `NeedsConnect`/`Connecting` — never
 * auto-skipped even with saved settings, since the app must not silently
 * dial an address the user didn't just confirm (see [ConnectionStatus]'s
 * trust-boundary note below).
 */
@Composable
fun ConnectScreen(
    status: ConnectionStatus,
    savedHost: String?,
    savedPort: Int,
    onConnect: (host: String, port: Int) -> Unit,
    onUseSimulator: () -> Unit,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var host by remember { mutableStateOf(savedHost ?: "") }
    var portText by remember { mutableStateOf(savedPort.toString()) }

    val connecting = status.state == ConnectionState.CONNECTING || status.state == ConnectionState.SOCKET_OPEN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .padding(horizontal = 20.dp),
    ) {
        VSpacer(48)
        TextC("Connect to rig", style = t.Title, color = c.text)
        VSpacer(6)
        TextC(
            "Enter the EkosRemote Pi's address on your local network.",
            style = t.Body13, color = c.textMuted,
        )
        VSpacer(20)

        // Trust-boundary warning — always visible, not dismissible. The wire has
        // no auth at all; anything on the LAN that can reach the port controls Ekos.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.warn.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .border(1.dp, c.warn.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(12.dp),
        ) {
            Phosphor.Icon(Phosphor.Warning, size = 16.dp, tint = c.warn)
            Spacer(Modifier.width(10.dp))
            TextC(
                "No authentication. Only connect on a trusted local network — anything " +
                    "that can reach this address can control your rig.",
                style = t.Caption, color = c.warn,
            )
        }
        VSpacer(24)

        TextC("HOST", style = t.MicroUppercase, color = c.textMuted)
        VSpacer(6)
        FieldBox {
            BasicTextField(
                value = host,
                onValueChange = { host = it },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VSpacer(16)

        TextC("PORT", style = t.MicroUppercase, color = c.textMuted)
        VSpacer(6)
        FieldBox {
            BasicTextField(
                value = portText,
                onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
                singleLine = true,
                textStyle = t.Body13.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VSpacer(24)

        NocturneButton(
            text = when (status.state) {
                ConnectionState.CONNECTING -> "Connecting…"
                ConnectionState.SOCKET_OPEN -> "Waiting for Ekos…"
                else -> "Connect"
            },
            onClick = {
                val port = portText.toIntOrNull() ?: 9000
                if (host.isNotBlank()) onConnect(host.trim(), port)
            },
            enabled = !connecting && host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (status.lastError != null && !connecting) {
            VSpacer(10)
            TextC("Couldn't connect: ${status.lastError}", style = t.Caption, color = c.danger)
        }
        if (connecting) {
            VSpacer(10)
            TextC(
                if (status.state == ConnectionState.SOCKET_OPEN) {
                    "Connected — waiting for Ekos to start…"
                } else {
                    "Dialing ${status.host}…"
                },
                style = t.Caption, color = c.textMuted,
            )
        }

        VSpacer(20)
        Box(Modifier.fillMaxWidth().clickable(onClick = onUseSimulator), contentAlignment = Alignment.Center) {
            TextC("Use simulator instead", style = t.Button13, color = c.accent)
        }
    }
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    val c = NocturneTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}
