package com.vovoscan.app.ui.reader

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.vovoscan.feature.reader.domain.Glide
import com.vovoscan.feature.reader.domain.Gutters
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.StripLayout
import com.vovoscan.feature.reader.presentation.ReaderCommand
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderItem
import com.vovoscan.feature.reader.presentation.ReaderUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** The spring in flight (reader/SPEC.md §3): where it is and how fast, so a new tap retargets without a jerk. */
@Stable
private class SpringRun {
    var running by mutableStateOf(false)
    var position = 0f
    var velocity = 0f
    var job: Job? = null
}

/**
 * The continuous strip (`flow`): every page stacked at the screen's width, no gap. It reports its size and scroll
 * (from the first page's top, in dp) and plays the view model's `GlideTo` with the shared spring, frame by frame from
 * the frame's own time (60, 90 or 120 Hz alike). A finger stops the spring at once (the list's user scroll wins) and
 * the platform's fling takes over. Focus dims everything but the panel being read.
 */
@Composable
internal fun FlowStrip(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    scrollEnabled: Boolean,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.currentItem)
    val scope = rememberCoroutineScope()
    val spring = remember { SpringRun() }
    var width by remember { mutableFloatStateOf(0f) }
    val layout =
        remember(state.pages, width, state.pageGap) {
            if (width >
                0f
            ) {
                StripLayout(state.pages, width, state.pageGap.toFloat())
            } else {
                null
            }
        }

    LaunchedEffect(list, layout) {
        val strip = layout ?: return@LaunchedEffect
        snapshotFlow { Triple(list.offsetFrom(strip, state, density), list.isScrollInProgress, spring.running) }
            .collect { (offset, scrolling, gliding) ->
                if (offset !=
                    null
                ) {
                    onIntent(ReaderIntent.StripScrolled(offset, byUser = scrolling && !gliding, settled = !scrolling && !gliding))
                }
            }
    }
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollBackward to !list.canScrollForward }
            .collect { (top, bottom) -> onIntent(ReaderIntent.StripEdges(top, bottom)) }
    }
    LaunchedEffect(state.prefetch) {
        val loader = SingletonImageLoader.get(context)
        state.prefetch.forEach { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
    }

    val command = state.command
    LaunchedEffect(command, layout) {
        when (command) {
            is ReaderCommand.GlideTo -> {
                val strip = layout ?: return@LaunchedEffect
                onIntent(ReaderIntent.CommandHandled(command.seq))
                if (!command.animated) {
                    spring.job?.cancel()
                    list.jumpTo(strip, command.offset, density)
                } else {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val from = if (spring.running) spring.position else list.offsetFrom(strip, state, density) ?: return@LaunchedEffect
                    val v0 = if (spring.running) spring.velocity else 0f
                    spring.job?.cancel()
                    spring.job = scope.launch { list.glide(spring, from, v0, command.offset, density) }
                }
            }
            is ReaderCommand.GoToItem -> {
                onIntent(ReaderIntent.CommandHandled(command.seq))
                spring.job?.cancel()
                scope.launch { if (command.animated) list.animateScrollToItem(command.item) else list.scrollToItem(command.item) }
            }
            // Before the strip is measured: a plain share of the screen.
            is ReaderCommand.ScrollBy -> {
                onIntent(ReaderIntent.CommandHandled(command.seq))
                scope.launch { list.animateScrollBy(command.fraction * list.layoutInfo.viewportSize.height) }
            }
            else -> Unit
        }
    }

    // Focus: the panel's box in strip coordinates (dp), gliding from panel to panel; drawn against the live scroll.
    val focus = state.focus
    val focusRect =
        if (focus != null && layout != null && focus.page in layout.tops.indices) {
            val top = layout.tops[focus.page] + focus.y * layout.heights[focus.page]
            Rect(focus.x * layout.width, top, (focus.x + focus.width) * layout.width, top + focus.height * layout.heights[focus.page])
        } else {
            Rect.Zero
        }
    val spot by animateRectAsState(focusRect, tween(state.focusMs), label = "focus")
    val dim = Color.Black.copy(alpha = 1f - state.otherLight)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                with(density) {
                    width = it.width.toDp().value
                    onIntent(ReaderIntent.StripMeasured(it.width.toDp().value, it.height.toDp().value))
                }
            }.drawWithContent {
                drawContent()
                val strip = layout ?: return@drawWithContent
                if (focus == null) return@drawWithContent
                val offset = list.offsetFrom(strip, state, density) ?: return@drawWithContent
                val r =
                    with(density) {
                        Rect(spot.left.dp.toPx(), (spot.top - offset).dp.toPx(), spot.right.dp.toPx(), (spot.bottom - offset).dp.toPx())
                    }
                clipRect(r.left, r.top, r.right, r.bottom, ClipOp.Difference) { drawRect(dim, Offset.Zero, Size(size.width, size.height)) }
            },
    ) {
        LazyColumn(
            state = list,
            userScrollEnabled = scrollEnabled,
            verticalArrangement = Arrangement.spacedBy(state.pageGap.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.key }) { item ->
                when (item) {
                    is ReaderItem.Page -> {
                        val index = state.pages.indexOfFirst { it.index == item.page.index }
                        PageImage(item.page, Modifier.fillMaxWidth(), onSize = {
                            w,
                            h,
                            ->
                            onIntent(ReaderIntent.PageSized(item.page.index, w, h))
                        })
                        LaunchedEffect(item.page.url) {
                            luminance(context, item.page)?.let { onIntent(ReaderIntent.PageLuminance(index, it)) }
                        }
                    }
                    is ReaderItem.Transition -> TransitionCardView(item.card, onIntent, Modifier.fillMaxWidth().heightIn(min = 280.dp))
                }
            }
        }
    }
}

/** Scroll from the first page's top (dp): negative over the previous-chapter card, past the strip on the end card. */
private fun LazyListState.offsetFrom(
    strip: StripLayout,
    state: ReaderUiState,
    density: Density,
): Float? {
    val first = layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
    val into = with(density) { (-first.offset).toDp().value }
    return when (val item = state.items.getOrNull(first.index)) {
        is ReaderItem.Page -> strip.tops.getOrNull(first.index - 1)?.plus(into)
        is ReaderItem.Transition ->
            if (item.card.forward) strip.total + strip.gap + into else into - with(density) { first.size.toDp().value }
        null -> null
    }
}

private suspend fun LazyListState.jumpTo(
    strip: StripLayout,
    offset: Float,
    density: Density,
) {
    val target = offset.coerceAtLeast(0f)
    val page = strip.pageAt(target)
    scrollToItem(page + 1, with(density) { (target - strip.tops[page]).dp.roundToPx() })
}

/** The spring, frame by frame; the list's own user scroll cancels it (a finger always wins). */
private suspend fun LazyListState.glide(
    spring: SpringRun,
    from: Float,
    velocity: Float,
    target: Float,
    density: Density,
) {
    spring.running = true
    spring.position = from
    spring.velocity = velocity
    try {
        scroll {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val (x, v) = Glide.at(from, velocity, target, (now - start) / 1e9f)
                val wanted = with(density) { (x - spring.position).dp.toPx() }
                val consumed = scrollBy(wanted)
                spring.position += with(density) { consumed.toDp().value }
                spring.velocity = v
                // Landed, or the list can't go further (its end): stop.
                if (Glide.settled(x, v, target) || (abs(consumed) < 0.5f && abs(wanted) > 1f)) break
            }
        }
    } finally {
        spring.running = false
    }
}

/** The page's luminance at `Gutters.COLUMNS` columns, for gutter bands when the chapter has no analysis. */
private suspend fun luminance(
    context: Context,
    page: ReaderPage,
): IntArray? {
    if (page.width <= 0 || page.height <= 0) return null
    val rows = (Gutters.COLUMNS * page.height.toFloat() / page.width).roundToInt().coerceIn(1, 4096)
    val result =
        SingletonImageLoader.get(context).execute(
            ImageRequest
                .Builder(context)
                .data(page.url)
                .size(Gutters.COLUMNS, rows)
                .allowHardware(false)
                .memoryCacheKey("${page.url}#luminance")
                .build(),
        ) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        val small = Bitmap.createScaledBitmap(result.image.toBitmap(), Gutters.COLUMNS, rows, true)
        val pixels = IntArray(Gutters.COLUMNS * rows)
        small.getPixels(pixels, 0, Gutters.COLUMNS, 0, 0, Gutters.COLUMNS, rows)
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
    }
}
