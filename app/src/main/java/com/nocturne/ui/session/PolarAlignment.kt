package com.nocturne.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nocturne.session.SessionController
import com.nocturne.session.AppState
import com.nocturne.session.realSlewRateProp
import com.nocturne.ui.components.BtnStyle
import com.nocturne.ui.components.HDivider
import com.nocturne.ui.components.MediaFramePreview
import com.nocturne.ui.components.NocturneButton
import com.nocturne.ui.components.SwitchRow
import com.nocturne.ui.components.TextC
import com.nocturne.ui.theme.NocturneTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.nocturne.session.PAH_DIRECTIONS
import com.nocturne.session.PAH_MEASURE_IMAGE
import com.nocturne.session.PAH_REFRESH_ALGORITHMS
import com.nocturne.session.PAH_ROTATE_STAGES
import com.nocturne.session.PAH_ROTATIONS
import com.nocturne.session.PAH_STAGE_REFRESHING
import com.nocturne.session.PAH_STAGE_SELECT_STAR


// ── Polar align ──────────────────────────────────────────────────────────


/**
 * Polar Alignment Assistant, driven by `new_polar_state`. Pushes are independent partial
 * shapes (`{"stage"}`, `{"message"}`, `{"vector"}`, `{"enabled"}`, updated errors), merged in
 * [AppState]. Stage strings are the `PAHStages` table (see [PAH_MEASURE_IMAGE]), so the
 * sheet branches on them: image progress while measuring, Done rotating (manual slew), Select
 * Star → Start adjusting, Refreshing → Done. Settings are the assistant's own widgets via
 * `align_set_all_settings`, editable only when the desktop enables them.
 *
 * Shown on the Controls tab's Polar alignment page: this is the scrolling body, and
 * [PolarAlignmentActions] is the button bar pinned under it.
 */
@Composable
internal fun PolarAlignmentBody(state: AppState, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val stage = state.wirePolarStage
    val running = state.polarRunning
    val pah = state.wireAlignSettings
    val manualSlew = pah?.pAHManualSlew == true
    val algorithm = pah?.pAHRefreshAlgorithm
    val starSelect = stage == PAH_STAGE_SELECT_STAR
    // Move Star methods need a star picked on the image; Plate Solve doesn't.
    val canPickStar = starSelect && algorithm != null && algorithm != PAH_REFRESH_ALGORITHMS[0]

    Column {
        TextC(
            stage ?: if (running) "running…" else "not started",
            style = t.Mono26, color = if (running) c.warn else c.text,
        )
        PAH_MEASURE_IMAGE[stage]?.let { image ->
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..3).forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(if (i <= image) c.accent else c.divider, RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            TextC("measuring · image $image of 3", style = t.MonoSmall, color = c.textMuted)
        }
        Spacer(Modifier.height(9.dp))
        TextC(
            state.wirePolarMessage?.let(::appPahMessage) ?: "Put the mount either in the home position pointed toward the celestial pole, or pointed anywhere near the meridian, then tap Start.",
            style = t.Body13, color = c.textMuted,
        )
        // Align frame. Once PAH has a result, Ekos sends "+A" updated frames
        // (Media::sendUpdatedFrame) cropped from AlignView's display pixmap, which already has
        // Ekos's own correction triangle / plate-solve arrows painted in (FITSView::updateFrame
        // → AlignView::drawOverlay) — so nothing is drawn client-side, it would only duplicate.
        Spacer(Modifier.height(14.dp))
        PaImage(state, canPickStar, ctrl)
        if (canPickStar) {
            Spacer(Modifier.height(6.dp))
            TextC("Tap a bright star to place the correction triangle.", style = t.Caption, color = c.accent400)
        }
        // updatedError/Az/Alt arrive during refresh (setUpdatedErrors); vector.* with the
        // measurement (setPolarResults). Az/alt are signed (which way to turn the knob); -1/-1/-1
        // means "not computed yet", so only the total can be tested against it.
        val totalErr = (state.wirePolarUpdatedError ?: state.wirePolarVector?.error)?.takeIf { it >= 0 }
        if (totalErr != null) {
            val azErr = state.wirePolarUpdatedAzError ?: state.wirePolarVector?.azError
            val altErr = state.wirePolarUpdatedAltError ?: state.wirePolarVector?.altError
            Spacer(Modifier.height(10.dp))
            // Read at arm's length at the mount, so the values are large with the label above.
            Row(Modifier.fillMaxWidth()) {
                PolarErrorReadout("TOTAL", "%.2f°".format(totalErr), c.text, Modifier.weight(1f))
                PolarErrorReadout("AZ", azErr?.let { "%+.2f°".format(it) } ?: "—", c.textMuted, Modifier.weight(1f))
                PolarErrorReadout("ALT", altErr?.let { "%+.2f°".format(it) } ?: "—", c.textMuted, Modifier.weight(1f))
            }
        }

        // Settings — same enable rules as the desktop assistant: measurement settings only while
        // idle, refresh exposure/method also at Select Star (before adjusting starts).
        if (!running || starSelect) {
            Spacer(Modifier.height(16.dp))
            HDivider()
            Spacer(Modifier.height(16.dp))
            if (pah?.pAHDirection == null) {
                TextC("Polar alignment settings load once Ekos's Align module replies.", style = t.Caption, color = c.textFaint)
            } else {
                if (!running) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Direction")
                            Spacer(Modifier.height(5.dp))
                            CycleChip(pah.pAHDirection) {
                                ctrl.setPahDirection(PAH_DIRECTIONS[(PAH_DIRECTIONS.indexOf(pah.pAHDirection) + 1).mod(PAH_DIRECTIONS.size)])
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            FieldLabel("Rotation")
                            Spacer(Modifier.height(5.dp))
                            val rot = pah.pAHRotation ?: 30
                            CycleChip("$rot°") {
                                ctrl.setPahRotation(PAH_ROTATIONS[(PAH_ROTATIONS.indexOf(rot) + 1).mod(PAH_ROTATIONS.size)])
                            }
                        }
                    }
                    // Mount speed options are the mount's own slew-rate labels (the desktop combo
                    // is filled from the same TELESCOPE_SLEW_RATE list).
                    val speeds = state.realSlewRateProp?.options.orEmpty()
                    if (speeds.isNotEmpty() && pah.pAHMountSpeed != null) {
                        Spacer(Modifier.height(8.4.dp))
                        FieldLabel("Mount speed")
                        Spacer(Modifier.height(5.dp))
                        CycleChip(pah.pAHMountSpeed) {
                            ctrl.setPahMountSpeed(speeds[(speeds.indexOf(pah.pAHMountSpeed) + 1).mod(speeds.size)])
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        label = "Manual slew",
                        sub = "For non-GOTO mounts — you rotate RA by hand, then tap Done",
                        checked = manualSlew,
                        onToggle = { ctrl.setPahManualSlew(!manualSlew) },
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Refresh exposure")
                        Spacer(Modifier.height(5.dp))
                        DegreeField(pah.pAHExposure ?: 2.0, "s") { ctrl.setPahExposure(it.coerceIn(1.0, 30.0)) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Refresh method")
                        Spacer(Modifier.height(5.dp))
                        val alg = algorithm ?: PAH_REFRESH_ALGORITHMS[0]
                        CycleChip(alg) {
                            ctrl.setPahRefreshAlgorithm(PAH_REFRESH_ALGORITHMS[(PAH_REFRESH_ALGORITHMS.indexOf(alg) + 1).mod(PAH_REFRESH_ALGORITHMS.size)])
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Only matters before the mount moves on its own; once adjusting by hand it's just noise.
        if (!running || stage in PAH_ROTATE_STAGES || PAH_MEASURE_IMAGE[stage] != null) {
            TextC(
                "Real PAH drives its own mount rotation sequence once started — watch the mount. Stop cancels immediately, mid-motion if it's already rotating.",
                style = t.MonoMicro, color = c.warn,
            )
        }
    }
}

@Composable
internal fun PolarErrorReadout(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier) {
        TextC(label, style = t.MicroUppercase, color = c.textFaint)
        TextC(value, style = t.Mono21, color = valueColor)
    }
}

/**
 * The buttons for the current stage: the primary action (Start, Done rotating, Start adjusting,
 * Done) and Stop while a run is going; every stage has at least one.
 */
@Composable
internal fun PolarAlignmentActions(state: AppState, ctrl: SessionController) {
    val stage = state.wirePolarStage
    val running = state.polarRunning
    val manualSlew = state.wireAlignSettings?.pAHManualSlew == true
    val starSelect = stage == PAH_STAGE_SELECT_STAR
    val refreshing = stage == PAH_STAGE_REFRESHING
    val primary: Pair<String, () -> Unit>? = when {
        !running -> "Start" to ctrl::startPolarAlign
        stage in PAH_ROTATE_STAGES && manualSlew -> "Done rotating" to ctrl::polarSlewDone
        starSelect -> "Start adjusting" to ctrl::polarRefresh
        refreshing -> "Done" to ctrl::polarRefreshDone
        else -> null
    }
    // Refreshing's "Done" already ends the run (same server call as Stop).
    val showStop = running && !refreshing
    Column {
        primary?.let { (label, action) ->
            com.nocturne.ui.components.NocturneButton(
                text = label,
                onClick = action,
                style = com.nocturne.ui.components.BtnStyle.SOLID,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
        if (showStop) {
            if (primary != null) Spacer(Modifier.height(8.dp))
            com.nocturne.ui.components.NocturneButton(
                text = "Stop",
                onClick = ctrl::stopPolarAlign,
                style = com.nocturne.ui.components.BtnStyle.OUTLINE,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
}

/** Ekos's PAH prompts name its desktop buttons; point them at this page's. */
internal fun appPahMessage(message: String): String = message
    .replace("Click Stop", "Tap Done")
    .replace("click Stop", "tap Done")
    .replace("then click refresh", "then tap Start adjusting")
    .replace("click refresh", "tap Start adjusting")

/**
 * Latest align frame, box sized to the frame's own aspect (correction crops are square, a fixed
 * 4:3 box would shrink them). Pinch to zoom, drag to pan once zoomed, double-tap to reset: Ekos
 * paints the correction triangle and the RA-axis ring into the frame itself, so the app draws nothing
 * over it. When
 * [pickable], a tap sends its position as a fraction of the *received image* (zoom and
 * letterbox-corrected) — `polar_set_crosshair` maps that back through the crop's bounding rect
 * server-side (Message::processPolarCommands).
 */
@Composable
internal fun PaImage(state: AppState, pickable: Boolean, ctrl: SessionController) {
    val c = NocturneTheme.colors
    val jpeg = state.latestAlignFrame?.jpeg
    val imageSize = remember(jpeg) {
        jpeg?.let {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, o)
            if (o.outWidth > 0 && o.outHeight > 0) IntSize(o.outWidth, o.outHeight) else null
        }
    }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var picked by remember(jpeg) { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun clampPan(p: Offset, z: Float): Offset {
        val maxX = boxSize.width * (z - 1f) / 2f
        val maxY = boxSize.height * (z - 1f) / 2f
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(imageSize?.let { (it.width.toFloat() / it.height).coerceIn(0.75f, 2f) } ?: (4f / 3f))
            .clip(RoundedCornerShape(8.dp))
            .background(c.surfaceDeep)
            .onSizeChanged { boxSize = it }
            // One finger only moves the image while zoomed in, so the sheet still scrolls over it at 1×.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || zoom > 1f) {
                            val z = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                            zoom = z
                            pan = if (z == 1f) Offset.Zero else clampPan(pan + event.calculatePan(), z)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(imageSize, pickable) {
                detectTapGestures(
                    onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                    onTap = { tap ->
                        if (!pickable || imageSize == null) return@detectTapGestures
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        val local = (tap - pan - center) / zoom + center
                        val scale = minOf(boxSize.width / imageSize.width.toFloat(), boxSize.height / imageSize.height.toFloat())
                        val ox = (boxSize.width - imageSize.width * scale) / 2f
                        val oy = (boxSize.height - imageSize.height * scale) / 2f
                        val fx = (local.x - ox) / (imageSize.width * scale)
                        val fy = (local.y - oy) / (imageSize.height * scale)
                        if (fx in 0f..1f && fy in 0f..1f) {
                            picked = local
                            ctrl.polarSelectStar(fx.toDouble(), fy.toDouble())
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
        ) {
            MediaFramePreview(state.latestAlignFrame, Modifier.fillMaxSize(), hatchColor = c.surfaceRaised)
            picked?.takeIf { pickable }?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(c.warn, radius = 14.dp.toPx() / zoom, center = p, style = Stroke(width = 1.5.dp.toPx() / zoom))
                }
            }
        }
    }
    if (jpeg != null) {
        Spacer(Modifier.height(4.dp))
        TextC("Pinch to zoom · double-tap to reset", style = NocturneTheme.type.MonoMicro, color = c.textFaint)
    }
}
