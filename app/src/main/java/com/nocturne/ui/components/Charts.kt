package com.nocturne.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val tickColor = colors.warn
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
        // Flip tick + now dot + ring.
        val f1 = pt(0.62, 126f)
        val f2 = pt(0.62, 155f)
        drawLine(tickColor, f1, f2, strokeWidth = vw(2f))
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
 * M4.4: deleted `GuideTraceChart`/`VCurve` — both were fixture-only (Guide/Focus sheets' fake
 * RA/DEC trace and hardcoded 9-point V-curve), confirmed against source that no real data exists
 * on the wire to ever back either (see `GuideSheet`/`FocusSheet` in `Sheets.kt`, docs/M4-plan.md
 * "Guide/Focus real telemetry does not exist"). Not a "not yet wired" case — removed outright.
 */

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
