package com.nocturne.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathNode.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phosphor icons (256 viewBox) embedded as ImageVectors. Path data sourced from
 * @phosphor-icons/web / phosphor-icons/core — matches the prototype's icon set
 * exactly. Two weights:
 *  - [Spec.stroke=false]: filled glyph paths (fill-weight icons like the tab
 *    active states, and the legacy outline geometry used by the first icons).
 *  - [Spec.stroke=true]: stroked paths (the current regular-weight icons, which
 *    are drawn with a 16 px round stroke on the 256 viewBox).
 * Tint at draw time via Icon(tint = ...).
 */
object Phosphor {

    /** Tintable 24 dp render of a glyph (material Icon shim). */
    @Composable
    fun Icon(
        icon: ImageVector,
        modifier: Modifier = Modifier,
        size: Dp = 24.dp,
        tint: Color = Color.Unspecified,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = tint,
        )
    }

    /** Stroke width used by every regular-weight Phosphor icon (256 viewBox). */
    private const val SW = 16f

    // ── Tab icons (regular + fill) ──────────────────────────────────────────

    val Broadcast: ImageVector by lazy {
        icon("Broadcast",
            f("M128,88a40,40,0,1,0,40,40A40,40,0,0,0,128,88Zm0,64a24,24,0,1,1,24-24A24,24,0,0,1,128,152Zm73.71,7.14a80,80,0,0,1-14.08,22.2,8,8,0,0,1-11.92-10.67,63.95,63.95,0,0,0,0-85.33,8,8,0,1,1,11.92-10.67,80.08,80.08,0,0,1,14.08,84.47ZM69,103.09a64,64,0,0,0,11.26,67.58,8,8,0,0,1-11.92,10.67,79.93,79.93,0,0,1,0-106.67A8,8,0,1,1,80.29,85.34,63.77,63.77,0,0,0,69,103.09ZM248,128a119.58,119.58,0,0,1-34.29,84,8,8,0,1,1-11.42-11.2,103.9,103.9,0,0,0,0-145.56A8,8,0,1,1,213.71,44,119.58,119.58,0,0,1,248,128ZM53.71,200.78A8,8,0,1,1,42.29,212a119.87,119.87,0,0,1,0-168,8,8,0,1,1,11.42,11.2,103.9,103.9,0,0,0,0,145.56Z"))
    }

    val Crosshair: ImageVector by lazy {
        icon("Crosshair",
            f("M232,120h-8.34A96.14,96.14,0,0,0,136,32.34V24a8,8,0,0,0-16,0v8.34A96.14,96.14,0,0,0,32.34,120H24a8,8,0,0,0,0,16h8.34A96.14,96.14,0,0,0,120,223.66V232a8,8,0,0,0,16,0v-8.34A96.14,96.14,0,0,0,223.66,136H232a8,8,0,0,0,0-16Zm-96,87.6V200a8,8,0,0,0-16,0v7.6A80.15,80.15,0,0,1,48.4,136H56a8,8,0,0,0,0-16H48.4A80.15,80.15,0,0,1,120,48.4V56a8,8,0,0,0,16,0V48.4A80.15,80.15,0,0,1,207.6,120H200a8,8,0,0,0,0,16h7.6A80.15,80.15,0,0,1,136,207.6ZM128,88a40,40,0,1,0,40,40A40,40,0,0,0,128,88Zm0,64a24,24,0,1,1,24-24A24,24,0,0,1,128,152Z"))
    }

    val ListChecks: ImageVector by lazy {
        icon("ListChecks",
            f("M224,128a8,8,0,0,1-8,8H128a8,8,0,0,1,0-16h88A8,8,0,0,1,224,128ZM128,72h88a8,8,0,0,0,0-16H128a8,8,0,0,0,0,16Zm88,112H128a8,8,0,0,0,0,16h88a8,8,0,0,0,0-16ZM82.34,42.34,56,68.69,45.66,58.34A8,8,0,0,0,34.34,69.66l16,16a8,8,0,0,0,11.32,0l32-32A8,8,0,0,0,82.34,42.34Zm0,64L56,132.69,45.66,122.34a8,8,0,0,0-11.32,11.32l16,16a8,8,0,0,0,11.32,0l32-32a8,8,0,0,0-11.32-11.32Zm0,64L56,196.69,45.66,186.34a8,8,0,0,0-11.32,11.32l16,16a8,8,0,0,0,11.32,0l32-32a8,8,0,0,0-11.32-11.32Z"))
    }

    val ImagesSquare: ImageVector by lazy {
        icon("ImagesSquare",
            f("M208,32H80A16,16,0,0,0,64,48V64H48A16,16,0,0,0,32,80V208a16,16,0,0,0,16,16H176a16,16,0,0,0,16-16V192h16a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM80,48H208v69.38l-16.7-16.7a16,16,0,0,0-22.62,0L93.37,176H80Zm96,160H48V80H64v96a16,16,0,0,0,16,16h96Zm-88-64A24,24,0,1,0,96,88,24,24,0,0,0,120,112Zm0-32a8,8,0,1,1-8,8A8,8,0,0,1,120,80Z"))
    }

    val Plugs: ImageVector by lazy {
        icon("Plugs",
            f("M149.66,138.34a8,8,0,0,0-11.32,0L120,156.69,99.31,136l18.35-18.34a8,8,0,0,0-11.32-11.32L88,124.69,69.66,106.34a8,8,0,0,0-11.32,11.32L64.69,124,41.37,147.31a32,32,0,0,0,0,45.26l5.38,5.37-28.41,28.4a8,8,0,0,0,11.32,11.32l28.4-28.41,5.37,5.38a32,32,0,0,0,45.26,0L132,191.31l6.34,6.35a8,8,0,0,0,11.32-11.32L131.31,168l18.35-18.34A8,8,0,0,0,149.66,138.34Zm-52.29,65a16,16,0,0,1-22.62,0L52.69,181.25a16,16,0,0,1,0-22.62L76,135.31,120.69,180Zm140.29-185a8,8,0,0,0-11.32,0l-28.4,28.41-5.37-5.38a32.05,32.05,0,0,0-45.26,0L124,64.69l-6.34-6.35a8,8,0,0,0-11.32,11.32l80,80a8,8,0,0,0,11.32-11.32L191.31,132l23.32-23.31a32,32,0,0,0,0-45.26l-5.38-5.37,28.41-28.4A8,8,0,0,0,237.66,18.34Zm-34.35,79L180,120.69,135.31,76l23.32-23.31a16,16,0,0,1,22.62,0l22.06,22A16,16,0,0,1,203.31,97.37Z"))
    }

    val BroadcastFill: ImageVector by lazy {
        icon("BroadcastFill",
            f("M168,128a40,40,0,1,1-40-40A40,40,0,0,1,168,128Zm40,0a79.74,79.74,0,0,0-20.37-53.33,8,8,0,1,0-11.92,10.67,64,64,0,0,1,0,85.33,8,8,0,0,0,11.92,10.67A79.79,79.79,0,0,0,208,128ZM80.29,85.34A8,8,0,1,0,68.37,74.67a79.94,79.94,0,0,0,0,106.67,8,8,0,0,0,11.92-10.67,63.95,63.95,0,0,1,0-85.33Zm158.28-4A119.48,119.48,0,0,0,213.71,44a8,8,0,1,0-11.42,11.2,103.9,103.9,0,0,1,0,145.56A8,8,0,1,0,213.71,212,120.12,120.12,0,0,0,238.57,81.29ZM32.17,168.48A103.9,103.9,0,0,1,53.71,55.22,8,8,0,1,0,42.29,44a119.87,119.87,0,0,0,0,168,8,8,0,1,0,11.42-11.2A103.61,103.61,0,0,1,32.17,168.48Z"))
    }

    val CrosshairFill: ImageVector by lazy {
        icon("CrosshairFill",
            f("M232,120h-8.34A96.14,96.14,0,0,0,136,32.34V24a8,8,0,0,0-16,0v8.34A96.14,96.14,0,0,0,32.34,120H24a8,8,0,0,0,0,16h8.34A96.14,96.14,0,0,0,120,223.66V232a8,8,0,0,0,16,0v-8.34A96.14,96.14,0,0,0,223.66,136H232a8,8,0,0,0,0-16Zm-96,87.6V200a8,8,0,0,0-16,0v7.6A80.15,80.15,0,0,1,48.4,136H56a8,8,0,0,0,0-16H48.4A80.15,80.15,0,0,1,120,48.4V56a8,8,0,0,0,16,0V48.4A80.15,80.15,0,0,1,207.6,120H200a8,8,0,0,0,0,16h7.6A80.15,80.15,0,0,1,136,207.6ZM168,128a40,40,0,1,1-40-40A40,40,0,0,1,168,128Z"))
    }

    val ListChecksFill: ImageVector by lazy {
        icon("ListChecksFill",
            f("M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM117.66,149.66l-32,32a8,8,0,0,1-11.32,0l-16-16a8,8,0,0,1,11.32-11.32L80,164.69l26.34-26.35a8,8,0,0,1,11.32,11.32Zm0-64-32,32a8,8,0,0,1-11.32,0l-16-16A8,8,0,0,1,69.66,90.34L80,100.69l26.34-26.35a8,8,0,0,1,11.32,11.32ZM192,168H144a8,8,0,0,1,0-16h48a8,8,0,0,1,0,16Zm0-64H144a8,8,0,0,1,0-16h48a8,8,0,0,1,0,16Z"))
    }

    val ImagesSquareFill: ImageVector by lazy {
        icon("ImagesSquareFill",
            f("M208,32H80A16,16,0,0,0,64,48V64H48A16,16,0,0,0,32,80V208a16,16,0,0,0,16,16H176a16,16,0,0,0,16-16V192h16a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM80,48H208v69.38l-16.7-16.7a16,16,0,0,0-22.62,0L93.37,176H80Zm96,160H48V80H64v96a16,16,0,0,0,16,16h96ZM104,88a16,16,0,1,1,16,16A16,16,0,0,1,104,88Z"))
    }

    val PlugsFill: ImageVector by lazy {
        icon("PlugsFill",
            f("M149.66,149.66,131.31,168l18.35,18.34a8,8,0,0,1-11.32,11.32L132,191.31l-23.31,23.32a32.06,32.06,0,0,1-45.26,0l-5.37-5.38-28.4,28.41a8,8,0,0,1-11.32-11.32l28.41-28.4-5.38-5.37a32,32,0,0,1,0-45.26L64.69,124l-6.35-6.34a8,8,0,0,1,11.32-11.32L88,124.69l18.34-18.35a8,8,0,0,1,11.32,11.32L99.31,136,120,156.69l18.34-18.35a8,8,0,0,1,11.32,11.32Zm88-131.32a8,8,0,0,0-11.32,0l-28.4,28.41-5.37-5.38a32.05,32.05,0,0,0-45.26,0L124,64.69l-6.34-6.35a8,8,0,0,0-11.32,11.32l80,80a8,8,0,0,0,11.32-11.32L191.31,132l23.32-23.31a32,32,0,0,0,0-45.26l-5.38-5.37,28.41-28.4A8,8,0,0,0,237.66,18.34Z"))
    }

    // ── Header ─────────────────────────────────────────────────────────────

    val MoonStars: ImageVector by lazy {
        icon("MoonStars",
            f("M240,96a8,8,0,0,1-8,8H216v16a8,8,0,0,1-16,0V104H184a8,8,0,0,1,0-16h16V72a8,8,0,0,1,16,0V88h16A8,8,0,0,1,240,96ZM144,56h8v8a8,8,0,0,0,16,0V56h8a8,8,0,0,0,0-16h-8V32a8,8,0,0,0-16,0v8h-8a8,8,0,0,0,0,16Zm72.77,97a8,8,0,0,1,1.43,8A96,96,0,1,1,95.07,37.8a8,8,0,0,1,10.6,9.06A88.07,88.07,0,0,0,209.14,150.33,8,8,0,0,1,216.77,153Zm-19.39,14.88c-1.79.09-3.59.14-5.38.14A104.11,104.11,0,0,1,88,64c0-1.79,0-3.59.14-5.38A80,80,0,1,0,197.38,167.86Z"))
    }

    val Bell: ImageVector by lazy {
        icon("Bell",
            f("M221.8,175.94C216.25,166.38,208,139.33,208,104a80,80,0,1,0-160,0c0,35.34-8.26,62.38-13.81,71.94A16,16,0,0,0,48,200H88.81a40,40,0,0,0,78.38,0H208a16,16,0,0,0,13.8-24.06ZM128,216a24,24,0,0,1-22.62-16h45.24A24,24,0,0,1,128,216ZM48,184c7.7-13.24,16-43.92,16-80a64,64,0,1,1,128,0c0,36.05,8.28,66.73,16,80Z"))
    }

    val DeviceRotate: ImageVector by lazy {
        icon("DeviceRotate",
            f("M205.66,221.66l-24,24a8,8,0,0,1-11.32-11.32L180.69,224H80a24,24,0,0,1-24-24V104a8,8,0,0,1,16,0v96a8,8,0,0,0,8,8H180.69l-10.35-10.34a8,8,0,0,1,11.32-11.32l24,24A8,8,0,0,1,205.66,221.66ZM80,72a8,8,0,0,0,5.66-13.66L75.31,48H176a8,8,0,0,1,8,8v96a8,8,0,0,0,16,0V56a24,24,0,0,0-24-24H75.31L85.66,21.66A8,8,0,1,0,74.34,10.34l-24,24a8,8,0,0,0,0,11.32l24,24A8,8,0,0,0,80,72Z"))
    }

    val DeviceMobile: ImageVector by lazy {
        icon("DeviceMobile",
            s(rrect(64f, 24f, 128f, 208f, 16f)),
            s(line(64f, 56f, 192f, 56f)),
            s(line(64f, 200f, 192f, 200f)),
        )
    }

    val ArrowsInLineHorizontal: ImageVector by lazy {
        icon("ArrowsInLineHorizontal",
            f("M136,40V216a8,8,0,0,1-16,0V40a8,8,0,0,1,16,0ZM69.66,90.34a8,8,0,0,0-11.32,11.32L76.69,120H16a8,8,0,0,0,0,16H76.69L58.34,154.34a8,8,0,0,0,11.32,11.32l32-32a8,8,0,0,0,0-11.32ZM240,120H179.31l18.35-18.34a8,8,0,0,0-11.32-11.32l-32,32a8,8,0,0,0,0,11.32l32,32a8,8,0,0,0,11.32-11.32L179.31,136H240a8,8,0,0,0,0-16Z"))
    }

    // ── Common UI ──────────────────────────────────────────────────────────

    val X: ImageVector by lazy {
        icon("X",
            s(line(200f, 56f, 56f, 200f)),
            s(line(200f, 200f, 56f, 56f)),
        )
    }

    val Check: ImageVector by lazy {
        icon("Check", s(poly("40,144 96,200 224,72")))
    }

    val CheckCircle: ImageVector by lazy {
        icon("CheckCircle",
            s(poly("88,136 112,160 168,104")),
            s(circle(128f, 128f, 96f)),
        )
    }

    val Warning: ImageVector by lazy {
        icon("Warning",
            s("M142.41,40.22l87.46,151.87C236,202.79,228.08,216,215.46,216H40.54C27.92,216,20,202.79,26.13,192.09L113.59,40.22C119.89,29.26,136.11,29.26,142.41,40.22Z"),
            s(line(128f, 144f, 128f, 104f)),
            f(circle(128f, 180f, 12f)),
        )
    }

    val CheckFill: ImageVector by lazy {
        icon("CheckFill",
            f("M216,40H40A16,16,0,0,0,24,56V200a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40ZM205.66,85.66l-96,96a8,8,0,0,1-11.32,0l-40-40a8,8,0,0,1,11.32-11.32L104,164.69l90.34-90.35a8,8,0,0,1,11.32,11.32Z"))
    }

    val XFill: ImageVector by lazy {
        icon("XFill",
            f("M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM181.66,170.34a8,8,0,0,1-11.32,11.32L128,139.31,85.66,181.66a8,8,0,0,1-11.32-11.32L116.69,128,74.34,85.66A8,8,0,0,1,85.66,74.34L128,116.69l42.34-42.35a8,8,0,0,1,11.32,11.32L139.31,128Z"))
    }

    val CaretDown: ImageVector by lazy {
        icon("CaretDown", s(poly("208,96 128,176 48,96")))
    }

    val CaretRight: ImageVector by lazy {
        icon("CaretRight", s(poly("96,48 176,128 96,208")))
    }

    val CaretLeft: ImageVector by lazy {
        icon("CaretLeft", s(poly("160,208 80,128 160,48")))
    }

    val CaretUp: ImageVector by lazy {
        icon("CaretUp", s(poly("48,160 128,80 208,160")))
    }

    val MagnifyingGlass: ImageVector by lazy {
        icon("MagnifyingGlass",
            s(circle(112f, 112f, 80f)),
            s(line(168.57f, 168.57f, 224f, 224f)),
        )
    }

    val Plus: ImageVector by lazy {
        icon("Plus",
            s(line(40f, 128f, 216f, 128f)),
            s(line(128f, 40f, 128f, 216f)),
        )
    }

    val Pause: ImageVector by lazy {
        icon("Pause",
            s(rrect(152f, 40f, 56f, 176f, 8f)),
            s(rrect(48f, 40f, 56f, 176f, 8f)),
        )
    }

    val ArrowsClockwise: ImageVector by lazy {
        icon("ArrowsClockwise",
            s(poly("168,96 216,96 216,48")),
            s("M216,96,187.72,67.72A88,88,0,0,0,64,67"),
            s(poly("88,160 40,160 40,208")),
            s("M40,160l28.28,28.28A88,88,0,0,0,192,189"),
        )
    }

    val ArrowsOut: ImageVector by lazy {
        icon("ArrowsOut",
            s(poly("160,48 208,48 208,96")),
            s(line(152f, 104f, 208f, 48f)),
            s(poly("96,208 48,208 48,160")),
            s(line(104f, 152f, 48f, 208f)),
            s(poly("208,160 208,208 160,208")),
            s(line(152f, 152f, 208f, 208f)),
            s(poly("48,96 48,48 96,48")),
            s(line(104f, 104f, 48f, 48f)),
        )
    }

    val FlagCheckered: ImageVector by lazy {
        icon("FlagCheckered",
            s("M48,176c64-55.43,112,55.43,176,0V56C160,111.43,112,.57,48,56V224"),
            s("M48,116c64-55.43,112,55.43,176,0"),
            s(line(168f, 69.48f, 168f, 189.48f)),
            s(line(104f, 42.52f, 104f, 162.52f)),
        )
    }

    val Sparkle: ImageVector by lazy {
        icon("Sparkle",
            s("M84.27,171.73l-55.09-20.3a7.92,7.92,0,0,1,0-14.86l55.09-20.3,20.3-55.09a7.92,7.92,0,0,1,14.86,0l20.3,55.09,55.09,20.3a7.92,7.92,0,0,1,0,14.86l-55.09,20.3-20.3,55.09a7.92,7.92,0,0,1-14.86,0Z"),
            s(line(176f, 16f, 176f, 64f)),
            s(line(224f, 72f, 224f, 104f)),
            s(line(152f, 40f, 200f, 40f)),
            s(line(208f, 88f, 240f, 88f)),
        )
    }

    val TestTube: ImageVector by lazy {
        icon("TestTube",
            s("M94.77,213.23a36.77,36.77,0,0,1-52,0h0a36.77,36.77,0,0,1,0-52L172,32l60,60-24,8Z"),
            s("M72.82,131.18c9.37-3.65,25.78-6.36,47.18,4.82s37.81,8.47,47.18,4.82"),
        )
    }

    val Target: ImageVector by lazy {
        icon("Target",
            s(line(128f, 128f, 224f, 32f)),
            s("M195.88,60.12a95.88,95.88,0,1,0,18.77,26.49"),
            s("M161.94,94.06a48,48,0,1,0,14,31.2"),
        )
    }

    val Camera: ImageVector by lazy {
        icon("Camera",
            s("M208,208H48a16,16,0,0,1-16-16V80A16,16,0,0,1,48,64H80L96,40h64l16,24h32a16,16,0,0,1,16,16V192A16,16,0,0,1,208,208Z"),
            s(circle(128f, 132f, 36f)),
        )
    }

    val CirclesThree: ImageVector by lazy {
        icon("CirclesThree",
            s(circle(128f, 76f, 36f)),
            s(circle(188f, 172f, 36f)),
            s(circle(68f, 172f, 36f)),
        )
    }

    val CompassTool: ImageVector by lazy {
        icon("CompassTool",
            s(circle(128f, 80f, 32f)),
            s(line(128f, 48f, 128f, 24f)),
            s(line(141f, 109.25f, 192f, 224f)),
            s(line(64f, 224f, 115f, 109.25f)),
            s("M208,120c-14.57,28.49-45.8,48-80,48a87.71,87.71,0,0,1-35.75-7.56"),
        )
    }

    val CrosshairSimple: ImageVector by lazy {
        icon("CrosshairSimple",
            s(circle(128f, 128f, 96f)),
            s(line(128f, 32f, 128f, 72f)),
            s(line(128f, 184f, 128f, 224f)),
            s(line(32f, 128f, 72f, 128f)),
            s(line(184f, 128f, 224f, 128f)),
        )
    }

    val Cloud: ImageVector by lazy {
        icon("Cloud",
            s("M80,128a80,80,0,1,1,80,80H72A56,56,0,1,1,85.92,97.74"),
        )
    }

    val CloudSun: ImageVector by lazy {
        icon("CloudSun",
            s(line(87.66f, 56.73f, 83.5f, 33.09f)),
            s(line(56.69f, 76.46f, 37.03f, 62.69f)),
            s(line(48.73f, 112.31f, 25.09f, 116.48f)),
            s(line(123.52f, 64.69f, 137.28f, 45.03f)),
            s("M96,144a68.06,68.06,0,1,1,68,72H84a44,44,0,1,1,14.2-85.66"),
            s("M59.65,135.35a48,48,0,1,1,80.19-50.94"),
        )
    }

    val Garage: ImageVector by lazy {
        icon("Garage",
            s(line(16f, 200f, 240f, 200f)),
            s("M224,200V98.67A8,8,0,0,0,220.44,92l-88-58.67a8,8,0,0,0-8.88,0L35.56,92A8,8,0,0,0,32,98.67V200"),
            s(poly("72,200 72,136 184,136 184,200")),
            s(line(128f, 136f, 128f, 200f)),
            s(line(72f, 168f, 184f, 168f)),
        )
    }

    val Scissors: ImageVector by lazy {
        icon("Scissors",
            s(circle(60f, 76f, 28f)),
            s(circle(60f, 180f, 28f)),
            s(line(136f, 128f, 83.11f, 164.19f)),
            s(line(232f, 62.32f, 164.33f, 108.61f)),
            s(line(232f, 193.68f, 83.11f, 91.81f)),
        )
    }

    val SlidersHorizontal: ImageVector by lazy {
        icon("SlidersHorizontal",
            s(circle(104f, 80f, 24f)),
            s(circle(168f, 176f, 24f)),
            s(line(128f, 80f, 216f, 80f)),
            s(line(40f, 80f, 80f, 80f)),
            s(line(192f, 176f, 216f, 176f)),
            s(line(40f, 176f, 144f, 176f)),
        )
    }

    val DotsSixVertical: ImageVector by lazy {
        icon("DotsSixVertical",
            f(circle(92f, 60f, 12f)),
            f(circle(164f, 60f, 12f)),
            f(circle(92f, 128f, 12f)),
            f(circle(164f, 128f, 12f)),
            f(circle(92f, 196f, 12f)),
            f(circle(164f, 196f, 12f)),
        )
    }

    // ── Path helpers ────────────────────────────────────────────────────────

    /** SVG `d` for a straight segment. */
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        "M$x1 $y1 L$x2 $y2"

    /** SVG `d` for a polyline from "x,y x,y ..." points. */
    private fun poly(points: String): String {
        val pts = points.trim().split(" ").toList()
        if (pts.isEmpty()) return ""
        return pts.drop(1).fold("M${pts[0]}") { acc, p -> "$acc L$p" }
    }

    /** SVG `d` for a circle (two arcs, no fills of its own). */
    private fun circle(cx: Float, cy: Float, r: Float): String {
        val r2 = 2 * r
        return "M${cx - r},$cy a$r,$r 0 1,1 $r2,0 a$r,$r 0 1,1 ${-r2},0 Z"
    }

    /** SVG `d` for a rounded rectangle. */
    private fun rrect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
        val right = x + w
        val bottom = y + h
        return "M${x + rx},$y H${right - rx} Q$right,$y $right,${y + rx} " +
            "V${bottom - rx} Q$right,$bottom ${right - rx},$bottom " +
            "H${x + rx} Q$x,$bottom $x,${bottom - rx} " +
            "V${y + rx} Q$x,$y ${x + rx},$y Z"
    }

    // ── Spec + builder ─────────────────────────────────────────────────────

    private class Spec(val stroke: Boolean, val d: String)

    private fun f(d: String) = Spec(stroke = false, d = d)
    private fun s(d: String) = Spec(stroke = true, d = d)

    private fun icon(name: String, vararg specs: Spec): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 256f,
            viewportHeight = 256f,
        ).apply {
            specs.forEach { spec ->
                path(
                    name = name,
                    fill = if (spec.stroke) null else SolidColor(Color.Black),
                    stroke = if (spec.stroke) SolidColor(Color.Black) else null,
                    strokeLineWidth = if (spec.stroke) SW else 0f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    addNodes(PathParser().parsePathString(spec.d).toNodes())
                }
            }
        }.build()

    /** PathBuilder has no node-injection API in 1.7.x, so map nodes manually. */
    private fun PathBuilder.addNodes(nodes: List<PathNode>) {
        nodes.forEach { node ->
            when (node) {
                is MoveTo -> moveTo(node.x, node.y)
                is RelativeMoveTo -> moveToRelative(node.dx, node.dy)
                is LineTo -> lineTo(node.x, node.y)
                is RelativeLineTo -> lineToRelative(node.dx, node.dy)
                is HorizontalTo -> horizontalLineTo(node.x)
                is RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
                is VerticalTo -> verticalLineTo(node.y)
                is RelativeVerticalTo -> verticalLineToRelative(node.dy)
                is CurveTo -> curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                is RelativeCurveTo -> curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
                is ReflectiveCurveTo -> reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
                is RelativeReflectiveCurveTo -> reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                is QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
                is RelativeQuadTo -> quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
                is ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
                is RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx, node.dy)
                is ArcTo -> arcTo(
                    node.horizontalEllipseRadius,
                    node.verticalEllipseRadius,
                    node.theta,
                    node.isMoreThanHalf,
                    node.isPositiveArc,
                    node.arcStartX,
                    node.arcStartY,
                )
                is RelativeArcTo -> arcToRelative(
                    node.horizontalEllipseRadius,
                    node.verticalEllipseRadius,
                    node.theta,
                    node.isMoreThanHalf,
                    node.isPositiveArc,
                    node.arcStartDx,
                    node.arcStartDy,
                )
                is Close -> close()
            }
        }
    }
}
