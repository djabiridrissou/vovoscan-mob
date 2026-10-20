package com.vovoscan.app.ui.theme

import android.annotation.SuppressLint
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Brand colors (`brand/tokens.json`), the only ones allowed in the UI. */
object Brand {
    val Ink = Color(0xFF0B0B0E)
    val Ink2 = Color(0xFF15151B)
    val Line = Color(0xFF2A2A33)
    val Paper = Color(0xFFF3EEE4)
    val Paper2 = Color(0xFFE6DFD1)
    val Muted = Color(0xFFA39E94)
    val Red = Color(0xFFFF2D55)
    val Yellow = Color(0xFFFFD60A)
    val Cyan = Color(0xFF2DE2E6)
    val Danger = Color(0xFFFF6B6B)
    val InputBg = Color(0xFF0B0C10)
    val TextSoft = Color(0xC2F3EEE4)
}

class BrandFonts(
    val display: FontFamily,
    val body: FontFamily,
)

/**
 * Dela Gothic One (headings) and Space Grotesk (body), copied into `res/font` by `scripts/fetch-fonts.sh`.
 * If missing, the system font is used instead: the app stays usable.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun rememberBrandFonts(): BrandFonts {
    val context = LocalContext.current
    return remember {
        fun family(name: String): FontFamily =
            context.resources
                .getIdentifier(name, "font", context.packageName)
                .takeIf { it != 0 }
                ?.let { FontFamily(Font(it)) } ?: FontFamily.Default
        BrandFonts(display = family("dela_gothic_one"), body = family("space_grotesk"))
    }
}

@Composable
fun VovoscanTheme(content: @Composable () -> Unit) {
    val fonts = rememberBrandFonts()
    val colors =
        darkColorScheme(
            primary = Brand.Red,
            onPrimary = Brand.Ink,
            secondary = Brand.Cyan,
            onSecondary = Brand.Ink,
            tertiary = Brand.Yellow,
            background = Brand.Ink,
            onBackground = Brand.Paper,
            surface = Brand.Ink2,
            onSurface = Brand.Paper,
            surfaceVariant = Brand.Ink2,
            onSurfaceVariant = Brand.Muted,
            outline = Brand.Line,
            error = Brand.Danger,
        )
    val body = TextStyle(fontFamily = fonts.body, color = Brand.Paper)
    val typography =
        Typography(
            displaySmall = TextStyle(fontFamily = fonts.display, fontSize = 34.sp, lineHeight = 36.sp, color = Brand.Paper),
            headlineSmall = TextStyle(fontFamily = fonts.display, fontSize = 26.sp, lineHeight = 30.sp, color = Brand.Paper),
            titleMedium = TextStyle(fontFamily = fonts.display, fontSize = 18.sp, lineHeight = 22.sp, color = Brand.Paper),
            bodyLarge = body.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = body.copy(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = body.copy(fontSize = 13.sp, color = Brand.Muted),
            labelLarge = body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            labelSmall = body.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
        )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
