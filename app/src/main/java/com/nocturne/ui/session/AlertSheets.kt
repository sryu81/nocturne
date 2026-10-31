package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.nocturne.session.sourceLabel
import com.nocturne.session.SessionController
import com.nocturne.session.SheetType
import com.nocturne.session.AppState
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturnePalette
import com.nocturne.ui.theme.NocturneTheme


/** Tintable big circle icon used in alert rows. */
internal data class AlertStyle(val color: Color, val icon: ImageVector)

/**
 * Styled by severity, the only classification the wire carries besides `source`.
 * `KSNotification::EventType` (`ksnotification.h`): `Debug`/`Info`/`Warn`/`Alert`.
 */
internal fun alertStyle(a: com.nocturne.session.Alert): AlertStyle = when (a.severity) {
    3 -> AlertStyle(NocturnePalette.Danger, Phosphor.Warning) // Alert
    2 -> AlertStyle(NocturnePalette.Warn, Phosphor.Warning) // Warn
    1 -> AlertStyle(NocturnePalette.Ok, Phosphor.CheckCircle) // Info
    else -> AlertStyle(NocturnePalette.Accent, Phosphor.CheckCircle) // Debug
}


// ── Alerts ───────────────────────────────────────────────────────────────

@Composable
internal fun AlertsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        // The new_notification stream; empty state until one arrives this connection.
        if (state.wireNotifications.isEmpty()) {
            TextC("No alerts yet this session.", style = t.Body13, color = c.textMuted)
            Spacer(Modifier.height(11.2.dp))
        }
        state.wireNotifications.forEach { a ->
            val st = alertStyle(a)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .padding(11.2.dp),
            ) {
                Box(Modifier.width(2.dp).height(40.dp).background(st.color, RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(11.2.dp))
                Phosphor.Icon(st.icon, size = 17.dp, tint = st.color, modifier = Modifier.padding(top = 1.dp))
                Spacer(Modifier.width(11.2.dp))
                Column(Modifier.weight(1f)) {
                    TextC(a.message, style = t.Body13, color = c.text)
                    TextC("${a.time} · ${a.sourceLabel}", style = t.MonoMicro, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(8.4.dp))
        }
        NocturneButton(
            text = "Alert rules & notifications",
            onClick = { ctrl.openSheet(SheetType.PREFS) },
            icon = Phosphor.SlidersHorizontal,
            style = com.nocturne.ui.components.BtnStyle.SUBTLE,
            modifier = Modifier.fillMaxWidth().height(42.dp),
        )
    }
}

// ── Prefs ────────────────────────────────────────────────────────────────

/**
 * `option_get`/`option_set` toggles. Ekos has no per-category notification settings in
 * `kstars.kcfg`; these two are what exists: the master gate for the `new_notification` stream,
 * and whether Ekos plays a sound on the Pi. `—` until the connect-time fetch replies.
 */
@Composable
internal fun PrefsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        SwitchRow(
            label = "Notifications",
            sub = "master switch for the whole real alert stream (new_notification)",
            checked = state.wireEkosRemoteNotifications == true,
            onToggle = { ctrl.setEkosRemoteNotifications(!(state.wireEkosRemoteNotifications ?: false)) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Sound",
            sub = "Ekos plays a sound on the Pi's own speaker for each alert",
            checked = state.wireEkosRemoteSound == true,
            onToggle = { ctrl.setEkosRemoteSound(!(state.wireEkosRemoteSound ?: false)) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))
        // App-side only: the phone's own notifications, not Ekos' alert stream above.
        SwitchRow(
            label = "Push to this phone",
            sub = "alerts, a failing module, a lost link and a full disk while the phone is locked; " +
                "keeps the connection alive with a notification (needs Android's notification permission)",
            checked = state.pushNotificationsEnabled,
            onToggle = { ctrl.setPushNotifications(!state.pushNotificationsEnabled) },
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        // Quiet hours are app-side only; Ekos has no matching setting.
        SwitchRow(
            label = "Quiet hours",
            sub = "while on, only critical pushes (failures, a lost link) reach the phone; local only, doesn't affect the Pi",
            checked = state.quietHoursEnabled,
            onToggle = ctrl::toggleQuietHours,
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 11.2.dp),
        )
    }
}
