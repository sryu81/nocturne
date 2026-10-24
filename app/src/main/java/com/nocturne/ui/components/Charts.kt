package com.nocturne.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nocturne.session.wiggle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Canvas ports of the prototype's inline SVGs. All coordinates are taken
 * straight from `Session Control.dc.html`.
 */

/** Map a trace (values are vertical deviations from center) to pixel offsets. */
private fun traceOffsets(values: List<Double>, w: Float, h: Float): List<Offset> {
    val n = values.size
    if (n < 2) return emptyList()
    return values.mapIndexed { i, v ->
        Offset(
            x = if (n == 1) 0f else i / (n - 1f) * w,
            y = h / 2f - v.toFloat(),
        )
    }
}

private fun Path.polyline(pts: List<Offset>) {
    if (pts.isEmpty()) return
    moveTo(pts.first().x, pts.first().y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
}

/** Single polyline from a `wiggle` trace, stretched to the widget. */
@Composable
fun TraceLine(
    values: List<Double>,
    color: Color,
    modifier: Modifier,
    strokeWidth: Float = 1.6f,
) {
    Canvas(modifier = modifier) {
        val pts = traceOffsets(values, size.width, size.height)
        val path = Path().apply { polyline(pts) }
        drawPath(path, color, style = Stroke(width = strokeWidth))
    }
}

/** The Session-tab night arc (viewBox 348×196, uniform 'meet' scale). */
@Composable
fun NightArc(
    shotFraction: Double,
    plannedFraction: Double,
    nowFraction: Double,
    modifier: Modifier,
) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val baseTrack = Color(0xFFE9E9ED).copy(alpha = 0.07f)
    val plannedColor = colors.accent800
    val shotColor = colors.accent
    val nowColor = Color(0xFFE9E9ED)
    val ringColor = Color(0xFFE9E9ED).copy(alpha = 0.3f)

    Canvas(modifier = modifier) {
        val scale = min(size.width / 348f, size.height / 196f)
        val ox = (size.width - 348f * scale) / 2f
        val oy = (size.height - 196f * scale) / 2f
        fun vx(x: Float) = ox + x * scale
        fun vy(y: Float) = oy + y * scale
        fun vw(w: Float) = w * scale

        // pt(f, r) in viewBox coords: (174 - r*cos(πf), 178 - r*sin(πf))
        fun pt(f: Double, r: Float) = Offset(vx(174f - r * cos(PI * f).toFloat()), vy(178f - r * sin(PI * f).toFloat()))

        val arcTopLeft = Offset(vx(34f), vy(38f))
        val arcSize = androidx.compose.ui.geometry.Size(vw(280f), vw(280f))
        val arcLen = PI.toFloat() * 140f * scale
        val strokeWidth = vw(15f)

        // Base track + planned (faint) band.
        drawArc(
            color = baseTrack,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = arcTopLeft, size = arcSize,
            style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawArc(
            color = plannedColor,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = arcTopLeft, size = arcSize,
            style = Stroke(
                width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf((plannedFraction * arcLen).toFloat(), arcLen)),
            ),
        )
        drawArc(
            color = shotColor,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = arcTopLeft, size = arcSize,
            style = Stroke(
                width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf((shotFraction * arcLen).toFloat(), arcLen)),
            ),
        )
        // Now dot + ring. A "flip" tick used to draw here too, at a hardcoded fixed fraction
        // (0.62) regardless of anything real — removed (2026-08-25, real bug found live, user
        // report: "altitude peak / meridian flip time is wrong"). This function had no parameter
        // to gate it at all, contradicting NightArcCard's own doc comment, which already
        // (incorrectly) claimed it was "simply omitted under a real rig" — it never was. No real
        // flip time is computable client-side today regardless (would need real RA + local
        // sidereal time, neither modeled anywhere — same conclusion FlipBanner's own doc already
        // reached for its countdown), so there's nothing real to draw here instead; omitted
        // outright rather than left wrong.
        val now = pt(nowFraction, 140f)
        drawCircle(nowColor, radius = vw(6.5f), center = now)
        drawCircle(ringColor, radius = vw(12f), center = now, style = Stroke(width = vw(2f)))
    }
}

/**
 * Plan-tab altitude chart (viewBox 348×118, stretched). [realAltitudes] (49 real points spanning
 * ±12h around local midnight, see `WireRiseset.altitudes`'s own doc) and [realNowFraction] (see
 * `AppState.realDayFraction`) replace the decorative fixture curve + fixed-pixel "now" dot once
 * both have arrived for the currently-framed target — until then (simulator, or before the fetch
 * lands) this keeps rendering the exact same fixture shape it always has, zero regression. There's
 * no real "flip" (meridian-flip time) data anywhere in this app (see `FlipBanner`'s own doc) so
 * that dashed line is real-mode-only omitted, same treatment as Session tab's `NightArcCard`.
 *
 * **User-requested improvements (2026-08-22)**: [realDuskFraction]/[realDawnFraction] (both
 * fractions of the same day window [realNowFraction] is measured against — see
 * `PlanScreen.kt`'s `fractionOfDayWindow`) shade the real astronomical-dark span between them and
 * draw thin dashed edges, so it's visually obvious which part of the curve is actually
 * observable versus daylight. A dashed line also now marks the real curve's own peak (its
 * highest-altitude sample) — previously only named in the corner "max X° @ time" text, with
 * nothing tying that time to a position on the curve itself. All three are real-mode only, same
 * gating as [realNowFraction] — no equivalent data exists for the fixture curve.
 */
/**
 * Real altitude (degrees, +90..-90) mapped onto [AltitudeChart]'s own 0..118 viewBox height —
 * high altitude at the top (y=8) down to low/below-horizon at the bottom (y=110), leaving a
 * small margin either side. Shared (not private to [AltitudeChart]) so `PlanScreen.kt`'s y-axis
 * tick labels (user-requested, 2026-08-22) land at exactly the same height the real curve/
 * horizon/peak/now lines do — divide by 118 for a 0..1 fraction of the chart's own height.
 */
fun altitudeToChartY(alt: Double): Float = (((90.0 - alt) / 180.0) * 102.0 + 8.0).toFloat()

@Composable
fun AltitudeChart(
    modifier: Modifier,
    realAltitudes: List<Double>? = null,
    realNowFraction: Double? = null,
    realDuskFraction: Double? = null,
    realDawnFraction: Double? = null,
    /** Real Moon altitude curve (user request) — same 49-point/30-min grid as [realAltitudes],
     * from `astro_get_objects_riseset`'s own "Moon" entry (a real, generically-resolvable
     * SkyObject, same command already used for every target — no new wire command needed). Drawn
     * as a soft dashed line distinct from the target's own solid accent curve. */
    moonAltitudes: List<Double>? = null,
) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val fixtureCurve = listOf(
        "0,96", "40,80", "80,64", "120,48", "160,33", "200,26", "240,32", "280,48", "320,70", "348,86",
    ).map { p ->
        val parts = p.split(",")
        Offset(parts[0].toFloat(), parts[1].toFloat())
    }
    // Real altitudes are degrees, +90..-90 — mapped onto the same 0..118 viewBox height the
    // fixture curve uses, high altitude at the top (y=8) down to low/below-horizon at the bottom
    // (y=110), leaving a small margin either side. Shared as [altitudeToChartY] (below) so
    // PlanScreen.kt's y-axis tick labels land at exactly the same height this curve does.
    fun altY(alt: Double) = altitudeToChartY(alt)
    Canvas(modifier = modifier) {
        fun sx(x: Float) = x / 348f * size.width
        fun sy(y: Float) = y / 118f * size.height
        drawLine(
            Color(0xFFE9E9ED).copy(alpha = 0.14f),
            Offset(0f, sy(88f)), Offset(size.width, sy(88f)), strokeWidth = 1f,
        )
        drawLine(
            Color(0xFFE9E9ED).copy(alpha = 0.07f),
            Offset(0f, sy(50f)), Offset(size.width, sy(50f)), strokeWidth = 1f,
        )
        if (realAltitudes != null && realAltitudes.size >= 2) {
            // Astronomical-dark band (real dusk→dawn) — drawn first, as a background layer, so the
            // curve/horizon/peak/now marks all sit on top of it. Only drawn when both edges are
            // known and actually fall within this chart's own day-window (they always should, by
            // construction — dusk/dawn are always within the ±12h day window they're offset from
            // — but `dawnF > duskF` is asserted defensively rather than assumed).
            if (realDuskFraction != null && realDawnFraction != null && realDawnFraction > realDuskFraction) {
                val duskX = sx((realDuskFraction * 348f).toFloat())
                val dawnX = sx((realDawnFraction * 348f).toFloat())
                drawRect(
                    Color(0xFF5D5294).copy(alpha = 0.12f),
                    topLeft = Offset(duskX, 0f),
                    size = androidx.compose.ui.geometry.Size(dawnX - duskX, size.height),
                )
                val duskDashPathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                drawLine(Color(0xFF5D5294).copy(alpha = 0.6f), Offset(duskX, 0f), Offset(duskX, size.height), strokeWidth = 1f, pathEffect = duskDashPathEffect)
                drawLine(Color(0xFF5D5294).copy(alpha = 0.6f), Offset(dawnX, 0f), Offset(dawnX, size.height), strokeWidth = 1f, pathEffect = duskDashPathEffect)
            }
            // Real horizon (alt=0) line, in place of the fixture's second fixed guide line.
            drawLine(
                colors.warn.copy(alpha = 0.3f),
                Offset(0f, sy(altY(0.0))), Offset(size.width, sy(altY(0.0))), strokeWidth = 1f,
            )
            val n = realAltitudes.size
            val path = Path().apply {
                realAltitudes.forEachIndexed { i, alt ->
                    val x = sx(i.toFloat() / (n - 1) * 348f)
                    val y = sy(altY(alt))
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(path, colors.accent, style = Stroke(width = 2f))
            // Real Moon altitude (user request) — same x-mapping as the target curve above,
            // assumes the same sample grid (guaranteed by construction: both come from the same
            // real astro_get_objects_riseset command, just different names in the same request).
            if (moonAltitudes != null && moonAltitudes.size >= 2) {
                val moonN = moonAltitudes.size
                val moonPath = Path().apply {
                    moonAltitudes.forEachIndexed { i, alt ->
                        val x = sx(i.toFloat() / (moonN - 1) * 348f)
                        val y = sy(altY(alt))
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    moonPath, Color(0xFFC9C4B8).copy(alpha = 0.6f),
                    style = Stroke(width = 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))),
                )
            }
            // Real peak (curve's own highest-altitude sample) — previously only named in the
            // corner "max X° @ time" text with no visual tie to a position on the curve. Bright
            // near-white (same family as the "now" mark below, not the muted dusk/dawn indigo) —
            // confirmed live the first cut's colors.accent400 was too close to the dusk/dawn
            // lines' own hue to tell apart at a glance.
            val peakIdx = realAltitudes.withIndex().maxByOrNull { it.value }?.index
            if (peakIdx != null) {
                val peakX = sx(peakIdx.toFloat() / (n - 1) * 348f)
                drawLine(
                    Color(0xFFE9E9ED).copy(alpha = 0.55f), Offset(peakX, 0f), Offset(peakX, size.height),
                    strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            if (realNowFraction != null) {
                val idx = (realNowFraction * (n - 1)).coerceIn(0.0, (n - 1).toDouble())
                val lo = idx.toInt().coerceIn(0, n - 1)
                val hi = (lo + 1).coerceAtMost(n - 1)
                val frac = idx - lo
                val nowAlt = realAltitudes[lo] + (realAltitudes[hi] - realAltitudes[lo]) * frac
                val nowX = sx((realNowFraction * 348f).toFloat())
                drawLine(Color(0xFFE9E9ED), Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 1f)
                drawCircle(Color(0xFFE9E9ED), radius = 3.5f, center = Offset(nowX, sy(altY(nowAlt))))
            }
        } else {
            val path = Path().apply {
                moveTo(sx(fixtureCurve[0].x), sy(fixtureCurve[0].y))
                fixtureCurve.drop(1).forEach { lineTo(sx(it.x), sy(it.y)) }
            }
            drawPath(path, colors.accent, style = Stroke(width = 2f))
            drawLine(
                colors.warn, Offset(sx(208f), 0f), Offset(sx(208f), size.height),
                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            drawLine(
                Color(0xFFE9E9ED), Offset(sx(118f), 0f), Offset(sx(118f), size.height),
                strokeWidth = 1f,
            )
            drawCircle(Color(0xFFE9E9ED), radius = 3.5f, center = Offset(sx(118f), sy(49f)))
        }
    }
}

/**
 * Real guide drift trace — [com.nocturne.session.AppState.guideSamples] (fork `guideStats` push,
 * docs/FORK-BACKLOG.md §1), oldest-to-newest left-to-right, centered on 0. RA solid in [raColor],
 * DEC dashed in [deColor] (dash keeps them apart in red mode, where hues collapse). Vertical scale
 * is ±[guideDriftScale] — symmetric, follows the data, never tighter than ±[minScaleArcsec] so a
 * calm trace doesn't get blown up into noise; [thresholdArcsec] draws dashed ± guide lines.
 */
/** Half-height of [GuideDriftChart]'s vertical axis, arcsec — exposed so callers can label it. */
fun guideDriftScale(ra: List<Double>, de: List<Double>, thresholdArcsec: Double?, minScaleArcsec: Double = 2.0): Double =
    maxOf((ra + de).maxOfOrNull { kotlin.math.abs(it) } ?: 0.0, thresholdArcsec ?: 0.0, minScaleArcsec)

@Composable
fun GuideDriftChart(
    ra: List<Double>,
    de: List<Double>,
    raColor: Color,
    deColor: Color,
    modifier: Modifier,
    thresholdArcsec: Double? = null,
    minScaleArcsec: Double = 2.0,
) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val scale = guideDriftScale(ra, de, thresholdArcsec, minScaleArcsec)
    Canvas(modifier = modifier) {
        val mid = size.height / 2f
        fun y(v: Double) = mid - (v / scale).toFloat().coerceIn(-1f, 1f) * mid
        drawLine(colors.divider, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        if (thresholdArcsec != null) {
            for (v in listOf(thresholdArcsec, -thresholdArcsec)) {
                drawLine(
                    colors.warn.copy(alpha = 0.5f), Offset(0f, y(v)), Offset(size.width, y(v)),
                    strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                )
            }
        }
        fun trace(values: List<Double>, color: Color, dashed: Boolean) {
            if (values.size < 2) return
            val pts = values.mapIndexed { i, v -> Offset(i / (values.size - 1f) * size.width, y(v)) }
            drawPath(
                Path().apply { polyline(pts) }, color,
                style = Stroke(width = 1.6f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 3f)) else null),
            )
        }
        trace(ra, raColor, dashed = false)
        trace(de, deColor, dashed = true)
    }
}

/**
 * Real per-solve pointing-accuracy trend (follow-up to M5, docs/STATUS.md) — [AppState.alignAccuracyHistory]'s
 * real `targetDiff` values (arcsec), oldest-to-newest left-to-right. Unlike [HfrRunChart], this
 * normalizes against the data's own dynamic range rather than a fixed 60 — target accuracy has no
 * single universal scale the way this app's HFR values happen to. [thresholdArcsec] (real
 * `alignAccuracyThreshold`, when known) draws the same dashed reference line convention.
 */
@Composable
fun AccuracyTrendChart(values: List<Double>, thresholdArcsec: Double?, modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val maxVal = (values.maxOrNull() ?: 0.0).coerceAtLeast(thresholdArcsec ?: 0.0).coerceAtLeast(0.01)
    Canvas(modifier = modifier) {
        if (thresholdArcsec != null) {
            val y = (1f - (thresholdArcsec / maxVal).toFloat().coerceIn(0f, 1f)) * size.height
            drawLine(
                colors.warn.copy(alpha = 0.5f),
                Offset(0f, y), Offset(size.width, y),
                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
        }
        if (values.isEmpty()) return@Canvas
        val pts = values.mapIndexed { i, v ->
            val x = if (values.size < 2) 0f else i / (values.size - 1f) * size.width
            val y = (1f - (v / maxVal).toFloat().coerceIn(0f, 1f)) * size.height
            Offset(x, y)
        }
        val path = Path().apply { polyline(pts) }
        drawPath(path, colors.accent, style = Stroke(width = 1.6f))
    }
}

/** Frames tab HFR-across-run (viewBox 348×60, stretched). */
@Composable
fun HfrRunChart(values: List<Float>, modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    Canvas(modifier = modifier) {
        drawLine(
            Color(0xFFD98484).copy(alpha = 0.5f),
            Offset(0f, 22f / 60f * size.height), Offset(size.width, 22f / 60f * size.height),
            strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
        )
        val pts = values.mapIndexed { i, v ->
            val x = if (values.size < 2) 0f else i / (values.size - 1f) * size.width
            Offset(x, v / 60f * size.height)
        }
        val path = Path().apply { polyline(pts) }
        drawPath(path, colors.accent, style = Stroke(width = 1.6f))
    }
}

/** RMS card mini trace (viewBox 96×20, stretched). */
@Composable
fun MiniTrace(t: Int, modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val vals = wiggle(t, 0, 40, 7.0)
    Canvas(modifier = modifier) {
        val pts = traceOffsets(vals, size.width, size.height)
        val path = Path().apply { polyline(pts) }
        drawPath(path, colors.accent, style = Stroke(width = 1.2f))
    }
}

/**
 * Autofocus V-curve — one dot per measured focus frame ([com.nocturne.session.AppState.focusSamples]),
 * x = focuser position, y = HFR (lower is better, so the best focus sits at the bottom of the V).
 * A faint line joins the points in position order so the V shape reads at a glance; the newest
 * frame is ringed in [latestColor] and the lowest-HFR frame is filled in [bestColor]. On a relative
 * focuser (pos -1) frames are spread evenly in arrival order instead.
 */
@Composable
fun FocusVCurveChart(
    samples: List<com.nocturne.session.FocusSample>,
    pointColor: Color,
    bestColor: Color,
    latestColor: Color,
    modifier: Modifier,
) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    Canvas(modifier = modifier) {
        val pad = 6.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        for (f in listOf(0f, 0.5f, 1f)) {
            val y = pad + f * h
            drawLine(colors.divider, Offset(pad, y), Offset(pad + w, y), strokeWidth = 1f)
        }
        if (samples.isEmpty()) return@Canvas
        val byPosition = samples.all { it.pos >= 0 }
        val xs = if (byPosition) samples.map { it.pos.toDouble() } else samples.indices.map { it.toDouble() }
        val (xMin, xMax) = xs.min() to xs.max()
        val (yMin, yMax) = focusHfrRange(samples)
        fun pt(i: Int) = Offset(
            pad + (if (xMax > xMin) ((xs[i] - xMin) / (xMax - xMin)).toFloat() else 0.5f) * w,
            pad + (1f - ((samples[i].hfr - yMin) / (yMax - yMin)).toFloat()) * h,
        )
        val order = samples.indices.sortedBy { xs[it] }
        if (order.size >= 2) {
            drawPath(Path().apply { polyline(order.map { pt(it) }) }, pointColor.copy(alpha = 0.35f), style = Stroke(width = 1.4f))
        }
        val best = samples.indices.minBy { samples[it].hfr }
        val r = 3.dp.toPx()
        samples.indices.forEach { drawCircle(pointColor, radius = r, center = pt(it)) }
        drawCircle(bestColor, radius = r * 1.5f, center = pt(best))
        drawCircle(latestColor, radius = r * 2.2f, center = pt(samples.lastIndex), style = Stroke(width = 1.6f))
    }
}

/** Vertical HFR range of [FocusVCurveChart], padded 10% and never thinner than 0.5 — exposed for axis labels. */
fun focusHfrRange(samples: List<com.nocturne.session.FocusSample>): Pair<Double, Double> {
    val lo = samples.minOfOrNull { it.hfr } ?: 0.0
    val hi = samples.maxOfOrNull { it.hfr } ?: 1.0
    val span = maxOf(hi - lo, 0.5)
    val mid = (hi + lo) / 2
    return (mid - span * 0.55).coerceAtLeast(0.0) to mid + span * 0.55
}
