package com.nocturne.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.guideCameraDevice
import com.nocturne.session.gainSpecialValue
import com.nocturne.session.eafTemp
import com.nocturne.session.benchFocPos
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.FocusCurve
import com.nocturne.ui.components.GuideTelemetry
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme


// ── Guide ────────────────────────────────────────────────────────────────

/**
 * Guide camera frame + status, and live RMS/drift ([GuideTelemetry]) from the fork's guide-stats
 * push (stock EkosRemote only sends `{status}`).
 */
@Composable
internal fun GuideSheet(state: AppState, landscape: Boolean) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val guide = state.wireGuideSettings
    val preview = @Composable {
        Panel {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(c.surfaceDeep, RoundedCornerShape(8.dp)),
            ) {
                MediaFramePreview(state.latestGuideFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            }
            Spacer(Modifier.height(10.dp))
            TextC(
                state.wireGuideStatus?.let { "status: $it" } ?: "no status yet",
                style = t.Mono17, color = c.text,
            )
        }
    }
    val info = @Composable {
        GuideTelemetry(state, chartHeight = 120.dp)
        if (guide != null) {
            Spacer(Modifier.height(11.2.dp))
            TextC(
                "${"%.1f".format(guide.guideExposure)}s exposure · gain ${
                    if (state.gainSpecialValue(state.guideCameraDevice)?.let { guide.guideGain <= it + 1e-6 } == true) "--"
                    else "%.0f".format(guide.guideGain)
                } · bin ${guide.guideBinning}",
                style = t.MonoSmall, color = c.neutral500,
            )
        }
    }
    // Landscape: preview left, status right, so everything fits without scrolling.
    if (landscape) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { preview() }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { info() }
        }
    } else {
        Column {
            preview()
            Spacer(Modifier.height(11.2.dp))
            info()
        }
    }
}

// ── Focus ────────────────────────────────────────────────────────────────

/**
 * Focus frame + status, the live autofocus V-curve ([FocusCurve], from `new_focus_state`'s
 * per-frame `{hfr, pos}`), and POSITION/TEMP Δ/NEXT AF.
 */
@Composable
internal fun FocusSheet(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val tempNow = state.eafTemp
    val tempAtAf = state.focusTempAtLastAf
    val tempDelta = if (tempNow != null && tempAtAf != null) tempNow - tempAtAf else null
    val hfr = state.latestFocusFrame?.header?.hfr
    Column {
        Panel {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(c.surfaceDeep, RoundedCornerShape(8.dp)),
            ) {
                MediaFramePreview(state.latestFocusFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            }
            Spacer(Modifier.height(10.dp))
            TextC(
                listOfNotNull(
                    state.wireFocusStatus?.let { "status: $it" } ?: "no status yet",
                    hfr?.let { "HFR %.2f".format(it) },
                ).joinToString(" · "),
                style = t.Mono17, color = c.text,
            )
            Spacer(Modifier.height(10.dp))
            FocusCurve(state, chartHeight = 140.dp)
        }
        Spacer(Modifier.height(11.2.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("POSITION", state.benchFocPos?.toString() ?: "N/A", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            Stat("TEMP Δ", tempDelta?.let { "%+.1f°".format(it) } ?: "N/A", Modifier.weight(1f))
            Spacer(Modifier.width(8.4.dp))
            val refocus = state.wireCaptureSettings?.let { if (it.enforceRefocusEveryN) "every ${it.refocusEveryN} m" else "off" } ?: "N/A"
            Stat("REFOCUS", refocus, Modifier.weight(1f))
        }
        Spacer(Modifier.height(11.2.dp))
        NocturneButton(
            text = "Run autofocus now",
            onClick = ctrl::runAutofocusNow,
            style = com.nocturne.ui.components.BtnStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        )
    }
}
