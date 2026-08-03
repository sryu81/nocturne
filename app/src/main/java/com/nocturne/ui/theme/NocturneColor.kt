package com.nocturne.ui.theme

import androidx.compose.ui.graphics.Color

/** Nocturne palette — ported from `project/nocturne.css` tokens. */
object NocturnePalette {
    val Bg = Color(0xFF161826)
    val Surface = Color(0xFF232532)
    val SurfaceDeep = Color(0xFF191B28)
    val SurfaceRaised = Color(0xFF20222F)

    val Text = Color(0xFFE9E9ED)
    val TextMuted = Color(0xFF8A8A93)   // text 55%
    val TextDim = Color(0xFFAAAAB1)     // text 70% (field labels)
    val TextFaint = Color(0xFF80808A)   // text 50% (card meta)

    val Accent = Color(0xFF9184D9)
    val Accent2 = Color(0xFFA7A1DB)

    /** Divider = text 16% over bg, pre-composited. */
    val Divider = Color(0xFF383846)
    val DividerStrong = Color(0xFF464858)

    val Ok = Color(0xFF84D9A4)
    val Warn = Color(0xFFD9B184)
    val Danger = Color(0xFFD98484)
    val Info = Color(0xFF84B8D9)

    val Neutral100 = Color(0xFFF3F5FE)
    val Neutral200 = Color(0xFFE4E7F5)
    val Neutral300 = Color(0xFFCFD3E5)
    val Neutral400 = Color(0xFFB2B6CA)
    val Neutral500 = Color(0xFF9397AB)
    val Neutral600 = Color(0xFF75798C)
    val Neutral700 = Color(0xFF595D6C)
    val Neutral800 = Color(0xFF3F424D)
    val Neutral900 = Color(0xFF292B31)

    val Accent100 = Color(0xFFF5F4FF)
    val Accent200 = Color(0xFFE7E5FE)
    val Accent300 = Color(0xFFD2CEFD)
    val Accent400 = Color(0xFFB5ABFC)
    val Accent500 = Color(0xFF968AE0)
    val Accent600 = Color(0xFF796CBF)
    val Accent700 = Color(0xFF5D5294)
    val Accent800 = Color(0xFF423A6A)
    val Accent900 = Color(0xFF2B2741)

    val Accent2_100 = Color(0xFFF5F4FF)
    val Accent2_200 = Color(0xFFE7E5FE)
    val Accent2_300 = Color(0xFFD2CEFD)
    val Accent2_400 = Color(0xFFB5AFE8)
    val Accent2_500 = Color(0xFF9690C9)
    val Accent2_600 = Color(0xFF7972A9)
    val Accent2_700 = Color(0xFF5C5783)
    val Accent2_800 = Color(0xFF423E5D)
    val Accent2_900 = Color(0xFF2B293A)

    /** Red mode — deep-red ramp + dim (the prototype applies a CSS
     *  `grayscale sepia hue-rotate saturate brightness` filter; we remap
     *  the palette at the theme root instead, per the plan). */
    val RedBg = Color(0xFF1A1012)
    val RedSurface = Color(0xFF28171B)
    val RedSurfaceDeep = Color(0xFF1E1114)
    val RedText = Color(0xFFCBB9BD)
    val RedTextMuted = Color(0xFF7E6C70)
    val RedTextDim = Color(0xFF97858A)
    val RedTextFaint = Color(0xFF6F5F63)
    val RedAccent = Color(0xFFD97A6C)
    val RedAccent2 = Color(0xFFC99E8D)
    val RedDivider = Color(0xFF3C2A2E)
    val RedDividerStrong = Color(0xFF4C373C)
    val RedOk = Color(0xFFB8D98C)
    val RedWarn = Color(0xFFE0B27F)
    val RedDanger = Color(0xFFE08B80)
    val RedInfo = Color(0xFF9FBBD9)
}

/** Full Nocturne color scheme. Derived from the token palettes above. */
data class NocturneColorScheme(
    val bg: Color,
    val surface: Color,
    val surfaceDeep: Color,
    val surfaceRaised: Color,
    val text: Color,
    val textMuted: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentMuted: Color,
    val accent2: Color,
    val divider: Color,
    val dividerStrong: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val neutral: List<Color>,
    val accentRamp: List<Color>,
    val accent2Ramp: List<Color>,
) {
    val neutral600: Color get() = neutral[5]
    val neutral700: Color get() = neutral[6]
    val neutral800: Color get() = neutral[7]
    val neutral500: Color get() = neutral[4]
    val neutral400: Color get() = neutral[3]
    val accent400: Color get() = accentRamp[3]
    val accent600: Color get() = accentRamp[5]
    val accent800: Color get() = accentRamp[7]
}

/** Default dark scheme — the app's only light theme; red mode replaces it. */
val NocturneDarkColors: NocturneColorScheme = NocturneColorScheme(
    bg = NocturnePalette.Bg,
    surface = NocturnePalette.Surface,
    surfaceDeep = NocturnePalette.SurfaceDeep,
    surfaceRaised = NocturnePalette.SurfaceRaised,
    text = NocturnePalette.Text,
    textMuted = NocturnePalette.TextMuted,
    textDim = NocturnePalette.TextDim,
    textFaint = NocturnePalette.TextFaint,
    accent = NocturnePalette.Accent,
    accentStrong = NocturnePalette.Accent400,
    accentMuted = NocturnePalette.Accent600,
    accent2 = NocturnePalette.Accent2,
    divider = NocturnePalette.Divider,
    dividerStrong = NocturnePalette.DividerStrong,
    ok = NocturnePalette.Ok,
    warn = NocturnePalette.Warn,
    danger = NocturnePalette.Danger,
    info = NocturnePalette.Info,
    neutral = listOf(
        NocturnePalette.Neutral100,
        NocturnePalette.Neutral200,
        NocturnePalette.Neutral300,
        NocturnePalette.Neutral400,
        NocturnePalette.Neutral500,
        NocturnePalette.Neutral600,
        NocturnePalette.Neutral700,
        NocturnePalette.Neutral800,
        NocturnePalette.Neutral900,
    ),
    accentRamp = listOf(
        NocturnePalette.Accent100,
        NocturnePalette.Accent200,
        NocturnePalette.Accent300,
        NocturnePalette.Accent400,
        NocturnePalette.Accent500,
        NocturnePalette.Accent600,
        NocturnePalette.Accent700,
        NocturnePalette.Accent800,
        NocturnePalette.Accent900,
    ),
    accent2Ramp = listOf(
        NocturnePalette.Accent2_100,
        NocturnePalette.Accent2_200,
        NocturnePalette.Accent2_300,
        NocturnePalette.Accent2_400,
        NocturnePalette.Accent2_500,
        NocturnePalette.Accent2_600,
        NocturnePalette.Accent2_700,
        NocturnePalette.Accent2_800,
        NocturnePalette.Accent2_900,
    ),
)

/** Red mode — deep-red ramp + dim, mapped on the same token structure. */
val NocturneRedColors: NocturneColorScheme = NocturneColorScheme(
    bg = NocturnePalette.RedBg,
    surface = NocturnePalette.RedSurface,
    surfaceDeep = NocturnePalette.RedSurfaceDeep,
    surfaceRaised = NocturnePalette.RedSurface.copy(alpha = 0.92f),
    text = NocturnePalette.RedText,
    textMuted = NocturnePalette.RedTextMuted,
    textDim = NocturnePalette.RedTextDim,
    textFaint = NocturnePalette.RedTextFaint,
    accent = NocturnePalette.RedAccent,
    accentStrong = Color(0xFFEDB39A),
    accentMuted = NocturnePalette.RedAccent.copy(alpha = 0.85f),
    accent2 = NocturnePalette.RedAccent2,
    divider = NocturnePalette.RedDivider,
    dividerStrong = NocturnePalette.RedDividerStrong,
    ok = NocturnePalette.RedOk,
    warn = NocturnePalette.RedWarn,
    danger = NocturnePalette.RedDanger,
    info = NocturnePalette.RedInfo,
    neutral = listOf(
        Color(0xFF3A2F33),
        Color(0xFF5A474C),
        Color(0xFF7A5F65),
        Color(0xFF9A7E83),
        Color(0xFFB79B9F),
        Color(0xFF7E6C70),
        Color(0xFF6F5F63),
        Color(0xFF524448),
        Color(0xFF382C2F),
    ),
    accentRamp = listOf(
        Color(0xFFF7E6E0),
        Color(0xFFF0CBBF),
        Color(0xFFE8AFA0),
        Color(0xFFDF9281),
        Color(0xFFD97A6C),
        Color(0xFFC46357),
        Color(0xFFA44E44),
        Color(0xFF7E3A33),
        Color(0xFF552824),
    ),
    accent2Ramp = listOf(
        Color(0xFFF6E9E0),
        Color(0xFFEED2C0),
        Color(0xFFE2B9A0),
        Color(0xFFD19A7F),
        Color(0xFFC99E8D),
        Color(0xFFB17F6C),
        Color(0xFF8F6351),
        Color(0xFF6B4638),
        Color(0xFF472E26),
    ),
)
