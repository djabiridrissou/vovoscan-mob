package com.vovoscan.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's line icons (24 × 24, 1.8 stroke, round ends), drawn after the iOS tab bar's SF Symbols: a grid
 * (Catalog), books (Library), a stack (Sources), a gear (Settings), and the download controls. No icon library:
 * `Icon(…, tint = …)` colours them.
 */
object AppIcons {
    val Catalog: ImageVector by lazy {
        icon("Catalog") {
            roundRect(3.5f, 3.5f, 7f, 7f, 1.8f)
            roundRect(13.5f, 3.5f, 7f, 7f, 1.8f)
            roundRect(3.5f, 13.5f, 7f, 7f, 1.8f)
            roundRect(13.5f, 13.5f, 7f, 7f, 1.8f)
        }
    }

    val Library: ImageVector by lazy {
        icon("Library") {
            roundRect(3.5f, 4f, 4f, 16f, 1f)
            roundRect(9.5f, 4f, 4f, 16f, 1f)
            // The third book leans on the others.
            moveTo(15.2f, 5.4f)
            lineTo(18.9f, 4.5f)
            lineTo(21.9f, 18.6f)
            lineTo(18.2f, 19.5f)
            close()
        }
    }

    val Sources: ImageVector by lazy {
        icon("Sources") {
            moveTo(12f, 3f)
            lineTo(21f, 7.5f)
            lineTo(12f, 12f)
            lineTo(3f, 7.5f)
            close()
            moveTo(3f, 12f)
            lineTo(12f, 16.5f)
            lineTo(21f, 12f)
            moveTo(3f, 16.5f)
            lineTo(12f, 21f)
            lineTo(21f, 16.5f)
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            // Eight teeth around a ring, a hole in the middle.
            val teeth = 8
            val outer = 10f
            val inner = 7.4f
            for (i in 0 until teeth) {
                val a = i * 360f / teeth
                val points = listOf(inner to a - 17f, outer to a - 9f, outer to a + 9f, inner to a + 17f)
                points.forEachIndexed { j, (r, deg) ->
                    val (x, y) = polar(r, deg)
                    if (i == 0 && j == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            circle(12f, 12f, 3.2f)
        }
    }

    val Download: ImageVector by lazy {
        icon("Download") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7.5f)
            lineTo(12f, 15.5f)
            moveTo(8.5f, 12.5f)
            lineTo(12f, 16f)
            lineTo(15.5f, 12.5f)
        }
    }

    val Downloaded: ImageVector by lazy {
        icon("Downloaded") {
            circle(12f, 12f, 9f)
            moveTo(8f, 12.3f)
            lineTo(10.8f, 15f)
            lineTo(16f, 9.5f)
        }
    }

    val Failed: ImageVector by lazy {
        icon("Failed") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7.5f)
            lineTo(12f, 13f)
            moveTo(12f, 16.3f)
            lineTo(12f, 16.4f)
        }
    }

    /** Inside the progress ring: stops the download. */
    val Stop: ImageVector by lazy {
        ImageVector
            .Builder("Stop", 24.dp, 24.dp, 24f, 24f)
            .apply { path(fill = SolidColor(Color.Black)) { roundRect(8.5f, 8.5f, 7f, 7f, 1.2f) } }
            .build()
    }

    private fun icon(
        name: String,
        draw: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = draw,
                )
            }.build()

    private fun polar(
        radius: Float,
        degrees: Float,
    ): Pair<Float, Float> {
        val rad = degrees * PI / 180
        return (12 + radius * cos(rad)).toFloat() to (12 + radius * sin(rad)).toFloat()
    }

    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun PathBuilder.roundRect(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        r: Float,
    ) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w, y1 = y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w - r, y1 = y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x, y1 = y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + r, y1 = y)
        close()
    }
}
