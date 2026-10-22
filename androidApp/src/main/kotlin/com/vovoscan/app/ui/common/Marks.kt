package com.vovoscan.app.ui.common

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlin.math.min

/** "Remove animations" in the system settings: no pop, no glide. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The heart: round, 40 dp, ink glass; red and filled when on, a pop when tapped (none with animations removed), a
 * haptic on every tap. [label]: what a tap does, for TalkBack.
 */
@Composable
fun HeartButton(
    on: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(40.dp)
                .background(Brand.Ink.copy(alpha = 0.7f), CircleShape)
                .clickable(role = Role.Checkbox) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (!reduced) {
                        scope.launch {
                            pop.snapTo(1.3f)
                            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                    onClick()
                }.semantics {
                    contentDescription = label
                    selected = on
                },
    ) {
        Canvas(Modifier.size(20.dp).scale(pop.value)) { heart(on) }
    }
}

private fun DrawScope.heart(filled: Boolean) {
    val w = size.width
    val h = size.height
    val path =
        Path().apply {
            moveTo(w / 2, h * 0.92f)
            cubicTo(w * 0.1f, h * 0.62f, -w * 0.02f, h * 0.3f, w * 0.22f, h * 0.12f)
            cubicTo(w * 0.36f, h * 0.02f, w * 0.48f, h * 0.12f, w / 2, h * 0.26f)
            cubicTo(w * 0.52f, h * 0.12f, w * 0.64f, h * 0.02f, w * 0.78f, h * 0.12f)
            cubicTo(w * 1.02f, h * 0.3f, w * 0.9f, h * 0.62f, w / 2, h * 0.92f)
            close()
        }
    if (filled) drawPath(path, Brand.Red) else drawPath(path, Brand.Paper, style = Stroke(width = 1.8.dp.toPx()))
}

/** "★ 4.3 (12)  ♥ 30": everyone's numbers, only those known (the yellow star, the red heart). */
@Composable
fun MarkLines(
    rating: String?,
    favorite: String?,
    modifier: Modifier = Modifier,
) {
    if (rating == null && favorite == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        val style = MaterialTheme.typography.labelMedium
        rating?.let { Text(it, style = style.copy(color = Brand.Yellow), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        favorite?.let { Text(it, style = style.copy(color = Brand.Red), maxLines = 1) }
    }
}

/** "+3 new": yellow, tilted, on a cover's corner (text on yellow: ink). */
@Composable
fun NewChaptersBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
        maxLines = 1,
        modifier =
            modifier
                .rotate(-6f)
                .background(Brand.Yellow)
                .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * Five stars: a tap sets the half under the finger (left half = .5), a drag previews and sets where it is let go.
 * TalkBack: a slider ("4.5 out of 5 stars"), adjusted by half a star ([onStep]).
 */
@Composable
fun StarRating(
    fills: List<Double>,
    preview: (Double) -> List<Double>,
    title: String,
    value: String,
    score: Double?,
    onRate: (Double) -> Unit,
    onStep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<List<Double>?>(null) }
    // Where the finger was last seen while dragging: letting go rates there.
    var lastX by remember { mutableStateOf(0.0) }
    Canvas(
        modifier
            .size(width = 190.dp, height = 36.dp)
            .pointerInput(Unit) {
                detectTapGestures { onRate((it.x / size.width).toDouble()) }
            }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragging = null
                        onRate(lastX)
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    lastX = (change.position.x / size.width).toDouble()
                    val next = preview(lastX)
                    if (next != dragging) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    dragging = next
                }
            }.clearAndSetSemantics {
                contentDescription = title
                stateDescription = value
                // A slider for TalkBack: 0 (not rated) to 5 in half steps; each adjustment moves half a star.
                progressBarRangeInfo = ProgressBarRangeInfo((score ?: 0.0).toFloat(), 0f..5f, steps = 9)
                setProgress { target ->
                    onStep(target > (score ?: 0.0).toFloat())
                    true
                }
            },
    ) {
        val shown = dragging ?: fills
        val gap = 4.dp.toPx()
        val side = min((size.width - gap * 4) / 5, size.height)
        shown.forEachIndexed { i, fill ->
            star(Offset(i * (side + gap), (size.height - side) / 2), side, fill)
        }
    }
}

private fun DrawScope.star(
    at: Offset,
    side: Float,
    fill: Double,
) {
    val path =
        Path().apply {
            for (k in 0 until 10) {
                val radius = if (k % 2 == 0) side / 2 else side / 4.6f
                val angle = Math.toRadians(-90.0 + k * 36.0)
                val x = at.x + side / 2 + (radius * kotlin.math.cos(angle)).toFloat()
                val y = at.y + side / 2 + (radius * kotlin.math.sin(angle)).toFloat()
                if (k == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    val outline = if (fill > 0) Brand.Yellow else Brand.Muted
    if (fill > 0) {
        clipRect(at.x, at.y, at.x + side * fill.toFloat(), at.y + side) { drawPath(path, Brand.Yellow) }
    }
    drawPath(path, outline, style = Stroke(width = 1.8.dp.toPx()))
}
