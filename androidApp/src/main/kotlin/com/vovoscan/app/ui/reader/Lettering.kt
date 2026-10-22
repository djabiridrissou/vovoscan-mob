package com.vovoscan.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.vovoscan.app.R
import com.vovoscan.feature.reader.domain.Lettering

/** The lettering of the reader's language for a page index (empty: the original lettering). */
val LocalPageLettering = compositionLocalOf<(Int) -> List<Lettering>> { { emptyList() } }

private val letteringFont = FontFamily(Font(R.font.space_grotesk_bold, FontWeight.Bold))

/**
 * A translated page (the iOS `LetteringOverlay`, the web reader's `lettering.ts` through `LetteringLayout`): the
 * balloon's paint over the original letters, then the translation fitted in its rectangle. Fractions of the page,
 * so it is drawn in a box of the page's own shape, whatever the zoom or the mode.
 */
@Composable
fun LetteringOverlay(
    items: List<Lettering>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            items.forEach { item ->
                val paint = hexColor(item.fill)
                item.patches.forEach { patch ->
                    val topLeft = Offset((patch.rect.x * size.width).toFloat(), (patch.rect.y * size.height).toFloat())
                    val box = Size((patch.rect.w * size.width).toFloat(), (patch.rect.h * size.height).toFloat())
                    if (patch.ellipse) drawOval(paint, topLeft, box) else drawRect(paint, topLeft, box)
                }
            }
        }
        items.forEach { item ->
            val rect = item.textRect
            Box(
                Modifier
                    .offset(x = w * rect.x.toFloat(), y = h * rect.y.toFloat())
                    .size(width = w * rect.w.toFloat(), height = h * rect.h.toFloat())
                    .semantics { contentDescription = item.text },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    item.text,
                    style = TextStyle(color = hexColor(item.ink), fontFamily = letteringFont, textAlign = TextAlign.Center),
                    autoSize = TextAutoSize.StepBased(minFontSize = 5.sp, maxFontSize = 34.sp, stepSize = 0.5.sp),
                )
            }
        }
    }
}

/** `#rrggbb` from the server (checked in shared Kotlin); anything else is white. */
private fun hexColor(value: String): Color {
    val digits = value.removePrefix("#")
    val rgb = digits.toLongOrNull(16)?.takeIf { digits.length == 6 } ?: 0xFFFFFF
    return Color(0xFF000000 or rgb)
}
