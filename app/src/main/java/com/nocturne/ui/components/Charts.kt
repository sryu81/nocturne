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

/** Plan-tab altitude chart (viewBox 348×118, stretched). */
@Composable
fun AltitudeChart(modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val curve = listOf(
        "0,96", "40,80", "80,64", "120,48", "160,33", "200,26", "240,32", "280,48", "320,70", "348,86",
    ).map { p ->
        val parts = p.split(",")
        Offset(parts[0].toFloat(), parts[1].toFloat())
    }
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
        val path = Path().apply {
            moveTo(sx(curve[0].x), sy(curve[0].y))
            curve.drop(1).forEach { lineTo(sx(it.x), sy(it.y)) }
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

/** Guide sheet RA/DEC traces (viewBox 344×108, stretched). */
@Composable
fun GuideTraceChart(ra: List<Double>, dec: List<Double>, modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    Canvas(modifier = modifier) {
        fun sx(x: Float) = x / 344f * size.width
        fun sy(y: Float) = y / 108f * size.height
        drawLine(Color(0xFFE9E9ED).copy(alpha = 0.14f), Offset(0f, sy(54f)), Offset(size.width, sy(54f)), 1f)
        drawLine(Color(0xFFE9E9ED).copy(alpha = 0.05f), Offset(0f, sy(27f)), Offset(size.width, sy(27f)), 1f)
        drawLine(Color(0xFFE9E9ED).copy(alpha = 0.05f), Offset(0f, sy(81f)), Offset(size.width, sy(81f)), 1f)
        fun pts(vals: List<Double>) = vals.mapIndexed { i, v ->
            val x = if (vals.size < 2) 0f else i / (vals.size - 1f) * size.width
            Offset(x, sy(54f) - v.toFloat() * (size.height / 108f))
        }
        val raPath = Path().apply { polyline(pts(ra)) }
        val decPath = Path().apply { polyline(pts(dec)) }
        drawPath(raPath, colors.accent, style = Stroke(width = 1.4f))
        drawPath(decPath, colors.info, style = Stroke(width = 1.4f))
    }
}

/** Focus sheet V-curve (viewBox 344×130, uniform scale). */
@Composable
fun VCurve(modifier: Modifier) {
    val colors = com.nocturne.ui.theme.NocturneTheme.colors
    val pts = listOf(
        "14,16", "62,42", "110,68", "158,96", "186,112", "214,96", "262,68", "310,42", "340,18",
    ).map { p ->
        val parts = p.split(",")
        Offset(parts[0].toFloat(), parts[1].toFloat())
    }
    Canvas(modifier = modifier) {
        val scale = min(size.width / 344f, size.height / 130f)
        val ox = (size.width - 344f * scale) / 2f
        val oy = (size.height - 130f * scale) / 2f
        fun v(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)
        val path = Path().apply {
            moveTo(v(pts[0].x, pts[0].y).x, v(pts[0].x, pts[0].y).y)
            pts.drop(1).forEach { p ->
                val o = v(p.x, p.y)
                lineTo(o.x, o.y)
            }
        }
        drawPath(path, colors.accent.copy(alpha = 0.45f), style = Stroke(width = 1.4f))
        pts.forEachIndexed { i, p ->
            val o = v(p.x, p.y)
            val r = if (i == 4) 5f * scale else 3f * scale
            drawCircle(if (i == 4) Color(0xFFE9E9ED) else colors.accent, radius = r, center = o)
        }
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
