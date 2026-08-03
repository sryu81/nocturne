package com.nocturne.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nocturne.R

/**
 * Nocturne type scale — ported from `nocturne.css`. Inter variable font
 * (opsz,wght) drives the weights via FontVariation on API 26+.
 */
object NocturneType {

    @OptIn(ExperimentalTextApi::class)
    val Inter: FontFamily = FontFamily(
        Font(
            R.font.inter_variable,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        Font(
            R.font.inter_variable,
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        Font(
            R.font.inter_variable,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
        Font(
            R.font.inter_variable,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

    /** Mono numerals for telemetry (css `.mono`). */
    val Mono: FontFamily = FontFamily.Monospace

    private fun heading(size: Int, letter: Float) = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = size.sp,
        lineHeight = (size * 1.12).sp,
        letterSpacing = letter.sp,
    )

    /** h1 */
    val Display = heading(42, -0.63f)
    /** h2 */
    val Headline = heading(32, -0.48f)
    /** h3 */
    val Title = heading(25, -0.38f)
    /** h4 — dialog titles */
    val Subtitle = heading(20, -0.30f)
    /** h5 */
    val TitleSmall = heading(16, -0.24f)
    /** h6 — 13px uppercase label */
    val LabelUppercase = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.04.sp,
    )

    /** Base body — 15px/1.55 */
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.25.sp)
    /** card body — 13px */
    val BodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    /** card title — 17px medium */
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 20.sp)

    /** Header title — 19px medium */
    val HeaderTitle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        letterSpacing = (-0.19).sp,
    )

    /** Buttons — 14px medium */
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 16.8.sp)
    /** Small button — 11px medium */
    val ButtonSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.66.sp)

    /** card kicker — 10px uppercase accent */
    val Kicker = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.0.sp,
    )
    /** uppercase 10px status */
    val StatusLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.0.sp)

    /** field label — 12px */
    val FieldLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp)

    /** nav label — 10px */
    val NavLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.2.sp)

    /** card meta / table foot — 11px */
    val Meta = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.sp)
    /** table header — 11px uppercase */
    val TableHeader = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.88.sp)

    /** Telemetry values — mono numerals, 21px */
    val Telemetry = TextStyle(fontFamily = Mono, fontSize = 21.sp, letterSpacing = (-0.42).sp)
    /** Telemetry small — 16px */
    val TelemetrySmall = TextStyle(fontFamily = Mono, fontSize = 16.sp)
    /** Telemetry large — 34px integration total */
    val TelemetryLarge = TextStyle(fontFamily = Mono, fontSize = 34.sp, letterSpacing = (-1.02).sp)
    /** Telemetry tiny — 10.5px */
    val TelemetryTiny = TextStyle(fontFamily = Mono, fontSize = 10.5.sp)

    /** Mono numeral sizes — `.mono` in the prototype. */
    private fun mono(size: Float, letter: Float = 0f) = TextStyle(
        fontFamily = Mono,
        fontSize = size.sp,
        letterSpacing = letter.sp,
    )

    val MonoMicro = mono(9f)
    val MonoMini = mono(9.5f)
    val MonoTiny = mono(10f)
    val MonoSmall = mono(11f)
    val Mono115 = mono(11.5f)
    val MonoMid = mono(12f)
    val Mono13 = mono(13f)
    val Mono14 = mono(14f)
    val Mono15 = mono(15f)
    val Mono17 = mono(17f)
    val Mono20 = mono(20f)
    val Mono21 = mono(21f, -0.42f)      // telemetry value, -.02em
    val Mono26 = mono(26f, -0.52f)      // cooler sensor temp, -.02em
    val Mono30 = mono(30f, -0.9f)       // PA total error, -.03em
    val Mono34 = mono(34f, -1.02f)      // elapsed integration, -.03em
    val Mono38 = mono(38f, -1.14f)      // arc integration, -.03em

    /** Inter small sizes — extra body/label scales from the prototype. */
    private fun body(size: Float, weight: FontWeight = FontWeight.Normal, letter: Float = 0f) = TextStyle(
        fontFamily = Inter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.4).sp,
        letterSpacing = letter.sp,
    )

    /** 9.5px plain meta (card captions) */
    val Micro = body(9.5f)
    /** 9.5px letter-spaced uppercase */
    val MicroLabel = body(9.5f, letter = 0.855f)
    /** 10px letter-spaced uppercase */
    val MicroUppercase = body(10f, FontWeight.Medium, 0.9f)
    /** 10px plain */
    val Caption10 = body(10f)
    /** 11px body */
    val Caption = body(11f)
    /** 11px medium */
    val CaptionMedium = body(11f, FontWeight.Medium)
    /** 13px body */
    val Body13 = body(13f)
    /** 13.5px body (device/row names) */
    val Body135 = body(13.5f)
    /** 12px medium button */
    val Button12 = body(12f, FontWeight.Medium)
    /** 13px medium button */
    val Button13 = body(13f, FontWeight.Medium)
    /** 14px medium button */
    val Button14 = body(14f, FontWeight.Medium, 0f)

    /** Material shim so material3 widgets inherit Nocturne type. */
    val Material: Typography = Typography().let { base ->
        base.copy(
            displayLarge = Display,
            displayMedium = Headline,
            headlineLarge = Title,
            headlineMedium = Subtitle,
            headlineSmall = TitleSmall,
            titleLarge = Subtitle,
            titleMedium = CardTitle,
            titleSmall = TitleSmall,
            bodyLarge = Body,
            bodyMedium = Body,
            bodySmall = BodySmall,
            labelLarge = Button,
            labelMedium = Meta,
            labelSmall = StatusLabel,
        )
    }
}
