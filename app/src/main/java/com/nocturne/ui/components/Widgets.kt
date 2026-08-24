package com.nocturne.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import com.nocturne.protocol.MediaFrame
import com.nocturne.ui.icons.Phosphor
import com.nocturne.ui.theme.NocturneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Theme-aware text shim. */
@Composable
fun TextC(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
}

/** Button — prototype has solid accent fill (dark text), outline, danger fills. */
enum class BtnStyle { SOLID, OUTLINE, DANGER, SUBTLE }

@Composable
fun NocturneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.SOLID,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val bg: Color
    val fg: Color
    val bd: Color?
    when (style) {
        BtnStyle.SOLID -> {
            bg = c.accent; fg = c.surfaceDeep; bd = null
        }
        BtnStyle.OUTLINE -> {
            bg = c.surfaceRaised; fg = c.text; bd = c.dividerStrong
        }
        BtnStyle.DANGER -> {
            bg = c.danger; fg = c.surfaceDeep; bd = null
        }
        BtnStyle.SUBTLE -> {
            bg = Color.Transparent; fg = c.textMuted; bd = c.divider
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier
        .height(38.dp) // was 34 — most call sites override with their own explicit height anyway
        .background(bg, shape)
        .padding(horizontal = 14.dp)
        .clickable(enabled = enabled, onClick = onClick)
    val bordered = if (bd != null) base.border(1.dp, bd, shape) else base
    Box(modifier = modifier.then(bordered), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Phosphor.Icon(icon, size = 16.dp, tint = fg)
                Spacer(Modifier.width(6.dp))
            }
            TextC(text, style = t.Button13, color = if (enabled) fg else c.textFaint)
        }
    }
}

/** Round bordered icon button (header + sheet actions). */
@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 34,
    iconSize: Dp = 16.dp,
    tint: Color? = null,
    enabled: Boolean = true,
    dot: Boolean = false,
) {
    val c = NocturneTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .size(size.dp)
            .background(c.surfaceRaised, shape)
            .border(1.dp, c.divider, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Phosphor.Icon(icon, size = iconSize, tint = tint ?: c.textMuted)
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(c.warn, RoundedCornerShape(50)),
            )
        }
    }
}

/** Mono/uppercase status chip — e.g. `IDLE`, `GUIDING`, `CUT`. */
@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    val t = NocturneTheme.type
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextC(text, style = t.MicroLabel, color = color)
    }
}

/** Plan chip — `04:23`-style selectable tag. */
@Composable
fun PlanChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .background(if (selected) c.accent.copy(alpha = 0.16f) else Color.Transparent, shape)
            .border(1.dp, if (selected) c.accent else c.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextC(text, style = t.Caption, color = if (selected) c.accent else c.textMuted)
    }
}

/** Card container — bg surface, divider border, radius 14. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = NocturneTheme.colors
    Column(
        modifier = modifier
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.divider, RoundedCornerShape(14.dp))
            .padding(14.dp),
        content = content,
    )
}

/** Metric stat card — label uppercase over value, optional sub/right/onClick. */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    right: (@Composable () -> Unit)? = null,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    val base = Modifier
        .background(c.surface, RoundedCornerShape(14.dp))
        .border(1.dp, c.divider, RoundedCornerShape(14.dp))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(12.dp)
    Column(modifier = modifier.then(base)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC(label, style = t.MicroLabel, color = c.textFaint, modifier = Modifier.weight(1f))
            if (right != null) right()
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            TextC(
                value, style = t.Mono15, color = valueColor ?: c.text,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            if (sub != null) {
                Spacer(Modifier.width(6.dp))
                TextC(sub, style = t.Caption, color = c.textFaint)
            }
        }
        if (content != null) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Labeled row — label left, value right (setups, PA, device rows). */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    valueStyle: TextStyle? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        modifier = modifier
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextC(
            label, style = t.Body135, color = c.textMuted,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextC(value, style = valueStyle ?: t.Body135, color = valueColor ?: c.text)
    }
}

/** Toggle row with custom switch (prototype uses filled/unfilled circles). */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            TextC(label, style = t.Body135, color = c.text)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                TextC(sub, style = t.Caption, color = c.textFaint)
            }
        }
        Spacer(Modifier.width(12.dp))
        SwitchThumb(checked)
    }
}

@Composable
fun SwitchThumb(checked: Boolean, modifier: Modifier = Modifier) {
    val c = NocturneTheme.colors
    Box(
        modifier = modifier
            .size(38.dp, 22.dp)
            .background(if (checked) c.accent.copy(alpha = 0.25f) else c.divider, RoundedCornerShape(50))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(if (checked) c.accent else c.textFaint, RoundedCornerShape(50)),
        )
    }
}

/** Horizontal slider — label over track, rounded knob, mono value. */
@Composable
fun SliderRow(
    label: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextC(label, style = t.Caption, color = c.textMuted, modifier = Modifier.weight(1f))
            TextC(value.toString(), style = t.Mono13, color = c.text)
        }
        Spacer(Modifier.height(4.dp))
        val frac = if (max == 0) 0f else value.toFloat() / max
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(max, value) {
                    fun apply(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        onChange((f * max).roundToInt())
                    }
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        apply(change.position.x)
                    }
                }
                .clickable(enabled = false) {},
        ) {
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val trackH = 4.dp.toPx()
                val y = size.height / 2f
                drawRoundRect(
                    color = c.divider,
                    topLeft = Offset(0f, y - trackH / 2),
                    size = Size(size.width, trackH),
                    cornerRadius = CornerRadius(trackH / 2),
                )
                drawRoundRect(
                    color = c.accent,
                    topLeft = Offset(0f, y - trackH / 2),
                    size = Size(size.width * frac, trackH),
                    cornerRadius = CornerRadius(trackH / 2),
                )
                drawCircle(
                    color = c.text,
                    radius = 6.dp.toPx(),
                    center = Offset(size.width * frac, y),
                )
            }
        }
    }
}

/**
 * Renders a real JPEG frame — decode happens off the main thread via
 * [androidx.compose.runtime.produceState] since a capture frame can be a few hundred KB and
 * decoding synchronously inside composition would jank a live-updating preview. Falls back to
 * [HatchBg] while [key] is null or decoding fails (malformed/partial frame, missing file) — never
 * blank, so there's no flash between "no frame yet" and "frame failed to decode." [key] doubles
 * as the [produceState] key — pass whatever value should trigger a re-decode (the raw bytes, or
 * the file path).
 */
@Composable
private fun MediaFramePreviewImpl(
    key: Any?,
    modifier: Modifier,
    hatchColor: Color?,
    decode: suspend () -> ImageBitmap?,
) {
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = if (key == null) null else withContext(Dispatchers.Default) { runCatching { decode() }.getOrNull() }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        HatchBg(modifier, color = hatchColor)
    }
}

/**
 * Raw-bytes overload — a live `/media/ekos` push's JPEG isn't backed by a file (M4.2 call sites
 * before a frame is ever persisted); see the [String]-path overload below for a Room-persisted
 * [com.nocturne.data.FrameEntity] row (M4.5, real on-disk file, not a DB blob).
 */
@Composable
fun MediaFramePreview(jpeg: ByteArray?, modifier: Modifier = Modifier, hatchColor: Color? = null) {
    MediaFramePreviewImpl(jpeg, modifier, hatchColor) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)?.asImageBitmap() }
}

/** [MediaFrame]-typed convenience overload for a live `/media/ekos` push (M4.2 call sites). */
@Composable
fun MediaFramePreview(frame: MediaFrame?, modifier: Modifier = Modifier, hatchColor: Color? = null) {
    MediaFramePreview(frame?.jpeg, modifier, hatchColor)
}

/** Real on-disk file path overload (M4.5) — a [com.nocturne.data.FrameEntity]'s own `filePath`. */
@Composable
fun MediaFramePreviewFile(filePath: String?, modifier: Modifier = Modifier, hatchColor: Color? = null) {
    MediaFramePreviewImpl(filePath, modifier, hatchColor) { BitmapFactory.decodeFile(filePath)?.asImageBitmap() }
}

/** Diagonal hatch background — image slots & placeholders. */
@Composable
fun HatchBg(modifier: Modifier = Modifier, color: Color? = null) {
    val c = NocturneTheme.colors
    val line = color ?: c.divider
    Canvas(modifier = modifier) {
        val step = 6.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x + size.height, size.height), 1f)
            x += step
        }
    }
}

/** Full-bleed horizontal divider. */
@Composable
fun HDivider(modifier: Modifier = Modifier, strong: Boolean = false) {
    val c = NocturneTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) c.dividerStrong else c.divider),
    )
}

/** Section kicker + right side, per prototype section headers. */
@Composable
fun SectionHeader(kicker: String, modifier: Modifier = Modifier, right: (@Composable () -> Unit)? = null) {
    val c = NocturneTheme.colors
    val t = NocturneTheme.type
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextC(kicker, style = t.MicroUppercase, color = c.textFaint, modifier = Modifier.weight(1f))
        if (right != null) right()
    }
}

/** Column with vertical gap helper. */
@Composable
fun VSpacer(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HSpacer(w: Int) = Spacer(Modifier.width(w.dp))
