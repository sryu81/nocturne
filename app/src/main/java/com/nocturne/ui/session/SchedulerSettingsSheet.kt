package com.nocturne.ui.session

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Scheduler settings (curated subset, see WireSchedulerSettings) ─────────

/**
 * Scheduler-wide policy, laid out like Ekos's Scheduler tab: startup condition, constraints and
 * per-job step defaults, completion condition, observatory startup/shutdown, aborted-job handling,
 * guide & focus limits, dither. [AppState.wireSchedulerSettings] is null until the first
 * `scheduler_get_all_settings` reply.
 */
@Composable
internal fun SchedulerSettingsSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type

    val sc = state.wireSchedulerSettings
    if (sc == null) {
        TextC("Fetching scheduler settings…", style = t.Body13, color = c.textMuted)
        return
    }

    Column {
        TextC("STARTUP CONDITION", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Start ASAP", selected = sc.asapConditionR, onSelect = ctrl::setSchedulerStartAsap)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Start at a specific time", selected = sc.startupTimeConditionR, onSelect = { ctrl.setSchedulerStartAtTime(sc.startupTimeEdit) })
        if (sc.startupTimeConditionR) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Start time (ISO)")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.startupTimeEdit, ctrl::setSchedulerStartAtTime)
        }
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Lead time")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_LeadTime, "min", ctrl::setSchedulerLeadTime)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Pre-dawn cutoff")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_PreDawnTime, "min", ctrl::setSchedulerPreDawnTime)
            }
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("CONSTRAINTS", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Altitude limit", checked = sc.schedulerAltitude,
            onToggle = { ctrl.setSchedulerAltitudeEnabled(!sc.schedulerAltitude) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerAltitude) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerAltitudeValue, "°", ctrl::setSchedulerAltitudeValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Moon separation", checked = sc.schedulerMoonSeparation,
            onToggle = { ctrl.setSchedulerMoonSeparationEnabled(!sc.schedulerMoonSeparation) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerMoonSeparation) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerMoonSeparationValue, "°", ctrl::setSchedulerMoonSeparationValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Moon altitude", checked = sc.schedulerMoonAltitude,
            onToggle = { ctrl.setSchedulerMoonAltitudeEnabled(!sc.schedulerMoonAltitude) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerMoonAltitude) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.schedulerMoonAltitudeMaxValue, "°", ctrl::setSchedulerMoonAltitudeMaxValue)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Twilight", sub = "the real root cause behind the twilight-shutdown incident — see project notes",
            checked = sc.schedulerTwilight,
            onToggle = { ctrl.setSchedulerTwilightEnabled(!sc.schedulerTwilight) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel("Dawn offset")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_DawnOffset, "h", ctrl::setSchedulerDawnOffset)
            }
            Spacer(Modifier.width(11.2.dp))
            Column(Modifier.weight(1f)) {
                FieldLabel("Dusk offset")
                Spacer(Modifier.height(5.dp))
                DegreeField(sc.kcfg_DuskOffset, "h", ctrl::setSchedulerDuskOffset)
            }
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Horizon", checked = sc.schedulerHorizon,
            onToggle = { ctrl.setSchedulerHorizonEnabled(!sc.schedulerHorizon) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel("Job steps (new-job defaults)")
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Track", checked = sc.schedulerTrackStep,
            onToggle = { ctrl.setSchedulerTrackStep(!sc.schedulerTrackStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Focus", checked = sc.schedulerFocusStep,
            onToggle = { ctrl.setSchedulerFocusStep(!sc.schedulerFocusStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Align", checked = sc.schedulerAlignStep,
            onToggle = { ctrl.setSchedulerAlignStep(!sc.schedulerAlignStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Guide", checked = sc.schedulerGuideStep,
            onToggle = { ctrl.setSchedulerGuideStep(!sc.schedulerGuideStep) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Force alignment before each job",
            sub = "Only applies to a job whose own Align step is on — doesn't override one with Align off",
            checked = sc.kcfg_ForceAlignmentBeforeJob,
            onToggle = { ctrl.setSchedulerForceAlignmentBeforeJob(!sc.kcfg_ForceAlignmentBeforeJob) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("COMPLETION CONDITION", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Complete sequences", selected = sc.schedulerCompleteSequences, onSelect = ctrl::setSchedulerCompleteSequences)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Repeat sequences", selected = sc.schedulerRepeatSequences, onSelect = { ctrl.setSchedulerRepeatSequences(sc.schedulerRepeatSequencesLimit) })
        if (sc.schedulerRepeatSequences) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Repeat count")
            Spacer(Modifier.height(5.dp))
            DegreeField(sc.schedulerRepeatSequencesLimit.toDouble(), "×") { ctrl.setSchedulerRepeatSequences(it.toInt()) }
        }
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Repeat everything", selected = sc.schedulerRepeatEverything, onSelect = ctrl::setSchedulerRepeatEverything)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Loop until terminated", selected = sc.schedulerUntilTerminated, onSelect = ctrl::setSchedulerUntilTerminated)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Loop until a date", selected = sc.schedulerUntil, onSelect = { ctrl.setSchedulerUntil(sc.schedulerUntilValue) })
        if (sc.schedulerUntil) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Until (ISO)")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerUntilValue, ctrl::setSchedulerUntil)
        }
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("OBSERVATORY STARTUP / SHUTDOWN", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Startup procedure enabled", checked = sc.schedulerStartupEnabled,
            onToggle = { ctrl.setSchedulerStartupEnabled(!sc.schedulerStartupEnabled) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerStartupEnabled) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Pre-startup script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPreStartupScript, ctrl::setSchedulerPreStartupScript)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Post-startup script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPostStartupScript, ctrl::setSchedulerPostStartupScript)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Shutdown procedure enabled", checked = sc.schedulerShutdownEnabled,
            onToggle = { ctrl.setSchedulerShutdownEnabled(!sc.schedulerShutdownEnabled) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.schedulerShutdownEnabled) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Pre-shutdown script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPreShutdownScript, ctrl::setSchedulerPreShutdownScript)
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Post-shutdown script")
            Spacer(Modifier.height(5.dp))
            TextFieldBox(sc.schedulerPostShutdownScript, ctrl::setSchedulerPostShutdownScript)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Preemptive shutdown", sub = "shut down early if no job can run before the gap ends",
            checked = sc.kcfg_PreemptiveShutdown,
            onToggle = { ctrl.setSchedulerPreemptiveShutdown(!sc.kcfg_PreemptiveShutdown) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        if (sc.kcfg_PreemptiveShutdown) {
            Spacer(Modifier.height(8.4.dp))
            DegreeField(sc.kcfg_PreemptiveShutdownTime, "h", ctrl::setSchedulerPreemptiveShutdownTime)
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Stop Ekos after shutdown", checked = sc.kcfg_StopEkosAfterShutdown,
            onToggle = { ctrl.setSchedulerStopEkosAfterShutdown(!sc.kcfg_StopEkosAfterShutdown) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            label = "Shutdown script terminates INDI", checked = sc.kcfg_ShutdownScriptTerminatesINDI,
            onToggle = { ctrl.setSchedulerShutdownScriptTerminatesIndi(!sc.kcfg_ShutdownScriptTerminatesINDI) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        TextC("ABORTED-JOB HANDLING", style = t.MicroUppercase, color = c.textFaint)
        Spacer(Modifier.height(8.4.dp))
        OptionRow(label = "Don't restart", selected = sc.errorHandlingDontRestartButton, onSelect = ctrl::setSchedulerAbortDontRestart)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Restart immediately", selected = sc.errorHandlingRestartImmediatelyButton, onSelect = ctrl::setSchedulerAbortRestartImmediately)
        Spacer(Modifier.height(4.dp))
        OptionRow(label = "Restart after re-queueing", selected = sc.errorHandlingRestartQueueButton, onSelect = ctrl::setSchedulerAbortRestartQueue)
        if (sc.errorHandlingRestartImmediatelyButton || sc.errorHandlingRestartQueueButton) {
            Spacer(Modifier.height(8.4.dp))
            FieldLabel("Restart delay")
            Spacer(Modifier.height(5.dp))
            DegreeField(sc.errorHandlingStrategyDelay.toDouble(), "min") { ctrl.setSchedulerAbortDelay(it.toInt()) }
        }
        Spacer(Modifier.height(8.4.dp))
        SwitchRow(
            label = "Reschedule errored jobs", checked = sc.errorHandlingRescheduleErrorsCB,
            onToggle = { ctrl.setSchedulerAbortRescheduleErrors(!sc.errorHandlingRescheduleErrorsCB) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )
        Spacer(Modifier.height(16.dp))
        HDivider()
        Spacer(Modifier.height(16.dp))

        // With this on, Ekos can resume a stale queued job on its own restart.
        SwitchRow(
            label = "Remember job progress", sub = "Ekos can auto-resume a queued job on its own, even before this app reconnects",
            checked = sc.kcfg_RememberJobProgress,
            onToggle = { ctrl.setSchedulerRememberJobProgress(!sc.kcfg_RememberJobProgress) },
            modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
        )

        // ── Guide & Focus Limits ────────────────────────────────────────────────────
        // Ekos's "Guide & Focus Limits" dialog (limits.ui): guide-deviation abort, start-of-job
        // drift, refocus triggers. These and Dither are Capture-module fields, written with
        // capture_set_all_settings; they sit here because they govern an imaging session.
        val cam = state.wireCaptureSettings
        if (cam == null) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            TextC("Fetching guide/focus limits…", style = t.Body13, color = c.textMuted)
        } else {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            TextC("GUIDE & FOCUS LIMITS", style = t.MicroUppercase, color = c.textFaint)
            Spacer(Modifier.height(8.4.dp))

            SwitchRow(
                label = "Guide deviation guard",
                sub = "abort capture if the guide star drifts past this many arcsec",
                checked = cam.enforceGuideDeviation,
                onToggle = { ctrl.setCameraGuideDeviationEnabled(!cam.enforceGuideDeviation) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceGuideDeviation) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max deviation")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.guideDeviation, "\"", ctrl::setCameraGuideDeviation)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Start-of-job drift guard",
                sub = "wait for the guide star to settle within this many arcsec before starting a job",
                checked = cam.enforceStartGuiderDrift,
                onToggle = { ctrl.setCameraStartGuideDriftEnabled(!cam.enforceStartGuiderDrift) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceStartGuiderDrift) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max drift")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.startGuideDeviation, "\"", ctrl::setCameraStartGuideDeviation)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Refocus every N minutes",
                sub = "force an autofocus on this timer, reset at each autofocus",
                checked = cam.enforceRefocusEveryN,
                onToggle = { ctrl.setCameraRefocusEveryNEnabled(!cam.enforceRefocusEveryN) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceRefocusEveryN) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Every N minutes")
                Spacer(Modifier.height(5.dp))
                IntField(cam.refocusEveryN, ctrl::setCameraRefocusEveryN)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            SwitchRow(
                label = "Refocus on temperature drift",
                sub = "force an autofocus once temperature has drifted this much since the last one",
                checked = cam.enforceAutofocusOnTemperature,
                onToggle = { ctrl.setCameraRefocusOnTemperatureEnabled(!cam.enforceAutofocusOnTemperature) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enforceAutofocusOnTemperature) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Max ΔT")
                Spacer(Modifier.height(5.dp))
                DegreeField(cam.maxFocusTemperatureDelta, "°C", ctrl::setCameraMaxFocusTemperatureDelta)
            }
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))

            // Capture's global dither default (`enableDitherPerJob`/`guideDitherPerJobFrequency`);
            // a block's "Dither every" (`Block.ditherEvery`) overrides it.
            TextC("DITHER (GLOBAL DEFAULT)", style = t.MicroUppercase, color = c.textFaint)
            Spacer(Modifier.height(8.4.dp))
            SwitchRow(
                label = "Dither every job",
                sub = "dither after this many captured subs, not just per-filter",
                checked = cam.enableDitherPerJob,
                onToggle = { ctrl.setCameraDitherPerJobEnabled(!cam.enableDitherPerJob) },
                modifier = Modifier.fillMaxWidth().background(c.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
            )
            if (cam.enableDitherPerJob) {
                Spacer(Modifier.height(8.4.dp))
                FieldLabel("Every N subs")
                Spacer(Modifier.height(5.dp))
                IntField(cam.guideDitherPerJobFrequency, ctrl::setCameraDitherPerJobFrequency)
            }
        }
    }
}

/** Radio-style selection row with [SwitchRow]'s look; tapping the selected option re-selects it. */
@Composable
internal fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    SwitchRow(
        label = label, checked = selected, onToggle = onSelect,
        modifier = Modifier.fillMaxWidth().background(NocturneTheme.colors.bg, RoundedCornerShape(4.dp)).padding(horizontal = 11.2.dp),
    )
}

/** Plain single-line text field box, same visual shape as [CameraSettingsSheet]'s save-path field. */
@Composable
internal fun TextFieldBox(value: String, onChange: (String) -> Unit) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Box(
        Modifier.fillMaxWidth().height(48.dp)
            .background(c.bg, RoundedCornerShape(4.dp))
            .border(1.dp, c.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = t.Body13.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
