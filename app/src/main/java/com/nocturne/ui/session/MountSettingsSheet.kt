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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.indiSwitchElements
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Mount settings (curated subset) ─────────────────────────────────────────

/**
 * Curated subset of Ekos's Mount tab: meridian flip, altitude/HA slew limits, auto-park.
 * [AppState.wireMountSettings] is null until the first `mount_get_all_settings` reply.
 */
@Composable
internal fun MountSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    var confirmSetHome by remember { mutableStateOf(false) }
    val m = state.wireMountSettings
    if (m == null) {
        TextC("Fetching mount settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        SwitchRow(
            label = "Execute meridian flip",
            sub = "flip automatically when the mount crosses the meridian",
            checked = m.executeMeridianFlip,
            onToggle = { ctrl.setMountMeridianFlip(!m.executeMeridianFlip) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        FieldLabel("Flip offset")
        Spacer(Modifier.height(5.dp))
        DegreeField(m.meridianFlipOffsetDegrees, "min", ctrl::setMountMeridianFlipOffset)
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Altitude limits",
            sub = "refuse to slew past these altitudes",
            checked = m.enableAltitudeLimits,
            onToggle = { ctrl.setMountAltLimitEnabled(!m.enableAltitudeLimits) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.enableAltitudeLimits) {
            Spacer(Modifier.height(8.4.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Min alt")
                    Spacer(Modifier.height(5.dp))
                    DegreeField(m.minimumAltLimit, "°", ctrl::setMountAltLimitMin)
                }
                Spacer(Modifier.width(11.2.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("Max alt")
                    Spacer(Modifier.height(5.dp))
                    DegreeField(m.maximumAltLimit, "°", ctrl::setMountAltLimitMax)
                }
            }
            Spacer(Modifier.height(8.4.dp))
            SwitchRow(
                label = "Tracking only",
                sub = "only enforce while tracking, not on manual slews",
                checked = m.enableAltitudeLimitsTrackingOnly,
                onToggle = { ctrl.setMountAltLimitTrackingOnly(!m.enableAltitudeLimitsTrackingOnly) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Hour-angle limit",
            sub = "refuse to slew past this many hours from the meridian",
            checked = m.enableHaLimit,
            onToggle = { ctrl.setMountHaLimitEnabled(!m.enableHaLimit) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.enableHaLimit) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Max hour angle")
            Spacer(Modifier.height(5.dp))
            DegreeField(m.maximumHaLimit, "h", ctrl::setMountHaLimitMax)
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        SwitchRow(
            label = "Park every day",
            sub = "auto-park the mount at a fixed time",
            checked = m.parkEveryDay,
            onToggle = { ctrl.setMountParkEveryDay(!m.parkEveryDay) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (m.parkEveryDay) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Park time (HH:MM:SS)")
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .background(c.bg, RoundedCornerShape(4.dp))
                    .border(1.dp, c.divider, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = m.autoParkTime,
                    onValueChange = ctrl::setMountAutoParkTime,
                    singleLine = true,
                    textStyle = t.Body13.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if ("SET" in state.indiSwitchElements(state.primaryTrain.mount, "TELESCOPE_HOME")) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            FieldLabel("Home position")
            Spacer(Modifier.height(5.dp))
            NocturneButton(
                text = "Set home to current position",
                onClick = { confirmSetHome = true },
                style = BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
    if (confirmSetHome) {
        com.nocturne.ui.components.ConfirmDialog(
            title = "Set home here?",
            message = "The mount's current position becomes its home. Go home and homing will return here.",
            confirmText = "Set home",
            confirmStyle = BtnStyle.DANGER,
            onConfirm = {
                confirmSetHome = false
                ctrl.homeMount("SET")
            },
            onDismiss = { confirmSetHome = false },
        )
    }
}

/**
 * Numeric field row for degree/hour-angle inputs. Keeps its own `text` so partial input (empty,
 * `"-"`, a trailing `"."`) survives recomposition; [onChange] fires only when the text parses.
 */
@Composable
internal fun DegreeField(value: Double, unit: String, onChange: (Double) -> Unit) = DecimalField(value, unit, 1, onChange)

/** [DegreeField] with a chosen number of decimals shown (the guide gains need two). */
@Composable
internal fun DecimalField(value: Double, unit: String, decimals: Int, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    var text by remember { mutableStateOf("%.${decimals}f".format(value)) }
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
                val filtered = new.filter { it.isDigit() || it == '.' || it == '-' }
                text = filtered
                filtered.toDoubleOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
        TextC(unit, style = t.MonoSmall, color = c.neutral500)
    }
}
