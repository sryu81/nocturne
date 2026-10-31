package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.TrainRole
import com.nocturne.session.TrainSlot
import com.nocturne.session.get
import com.nocturne.session.train
import com.nocturne.session.trainRolePool
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


/**
 * Optical train role editor: a fixed Primary and Secondary train (no add/remove), each with the
 * 10 roles of `train_get_all` (Mount/Camera/Rotator/Guide via/Dust cap/Scope/Filter wheel/Focuser/
 * Reducer/Light box). Pools come from the profile's devices and the Scopes catalog; Reducer is a
 * multiplier. Which module uses which train is [ModuleAssignmentsSheet].
 */
@Composable
internal fun OpticalTrainSheet(state: AppState, ctrl: SessionController) {
    var slot by remember { mutableStateOf(TrainSlot.PRIMARY) }
    Column {
        Row(Modifier.border(1.dp, NocturneTheme.colors.divider, RoundedCornerShape(10.dp))) {
            listOf(TrainSlot.PRIMARY to "Primary", TrainSlot.SECONDARY to "Secondary").forEach { (s, label) ->
                val sel = s == slot
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (sel) NocturneTheme.colors.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { slot = s }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextC(label, style = NocturneTheme.type.Button13, color = if (sel) NocturneTheme.colors.accent400 else NocturneTheme.colors.textDim)
                }
            }
        }
        Spacer(Modifier.height(11.2.dp))
        TrainForm(state, ctrl, slot)
    }
}

/**
 * Which train each Ekos module (Camera/Focus/Mount/Guide/Align/Dark Library) uses, stored
 * per active profile (`train_set`/`train_get_profiles`). Gear hides the entry until trains exist.
 */
@Composable
internal fun ModuleAssignmentsSheet(state: AppState, ctrl: SessionController) {
    val trainNames = state.wireTrains?.map { it.name } ?: emptyList()
    Column {
        MODULE_ASSIGNMENT_LABELS.forEach { (moduleKey, label) ->
            RoleRow(
                label = label,
                options = trainNames,
                selected = state.moduleTrainAssignments?.get(moduleKey) ?: "",
                onSelect = { ctrl.setModuleTrain(moduleKey, it) },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * `train_set` module keys + labels: the 6 Ekos modules with their own Optical Train combo. The
 * wire key is `"capture"` but Ekos labels the tab Camera. Scheduler picks trains per job, and
 * Setup/Analyze/Observatory have none, so they're absent.
 *
 * Dark Library builds master darks for whichever camera its train points at, independently of
 * Capture's train (e.g. darks for the guide camera).
 */
internal val MODULE_ASSIGNMENT_LABELS = listOf(
    "capture" to "Camera",
    "focus" to "Focus",
    "mount" to "Mount",
    "guide" to "Guide",
    "align" to "Align",
    "darklibrary" to "Dark Library",
)

internal val TRAIN_ROLE_LABELS = listOf(
    TrainRole.MOUNT to "Mount",
    TrainRole.CAMERA to "Camera",
    TrainRole.ROTATOR to "Rotator",
    TrainRole.GUIDE_VIA to "Guide via",
    TrainRole.DUST_CAP to "Dust cap",
    TrainRole.SCOPE to "Scope/Lens",
    TrainRole.FILTER_WHEEL to "Filter wheel",
    TrainRole.FOCUSER to "Focuser",
    TrainRole.LIGHT_BOX to "Light box",
    TrainRole.ADAPTIVE_OPTICS to "Adaptive optics",
)

@Composable
internal fun TrainForm(state: AppState, ctrl: SessionController, slot: TrainSlot) {
    val train = state.train(slot)
    Column {
        TRAIN_ROLE_LABELS.forEach { (role, label) ->
            val pool = state.trainRolePool(role)
            RoleRow(
                label = label,
                options = pool,
                selected = train.get(role),
                onSelect = { ctrl.setTrainRole(slot, role, it) },
            )
            Spacer(Modifier.height(8.4.dp))
        }
        FieldLabel("Reducer/Barlow")
        Spacer(Modifier.height(5.dp))
        ReducerField(value = train.reducer, onChange = { ctrl.setTrainReducer(slot, it) })
    }
}

/** One role's picker: a horizontally scrolling chip row. */
@Composable
internal fun RoleRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column {
        TextC(label, style = t.CaptionMedium, color = c.textDim)
        Spacer(Modifier.height(5.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .border(1.dp, c.divider, RoundedCornerShape(4.dp)),
        ) {
            options.forEach { opt ->
                val sel = opt == selected
                Box(
                    Modifier
                        .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    TextC(opt, style = t.Body13, color = if (sel) c.accent400 else c.textDim)
                }
            }
        }
    }
}

/** Numeric reducer/barlow multiplier — plain digits+dot field, "1.00x" style. */
@Composable
internal fun ReducerField(value: Double, onChange: (Double) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
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
            value = "%.2f".format(value),
            onValueChange = { text ->
                text.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f),
        )
        TextC("x", style = t.MonoSmall, color = c.neutral500)
    }
}
