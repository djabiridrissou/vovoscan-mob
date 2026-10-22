package com.vovoscan.app.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.HapticFeedbackConstants.CONTEXT_CLICK
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.presentation.PullHint
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderUiState

/**
 * Book: one section at a time, in a web view with JavaScript off, no network and no navigation. The HTML is
 * already sanitised and styled (BookStyle); images are embedded. Pulling past the bottom (or the top) of the
 * section shows a hint that grows with the pull and turns the section on release past the threshold: the web view
 * only measures the pull, `ReaderUiState.pullHint` and `ReaderIntent.PullReleased` decide.
 */
@Composable
internal fun BookReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val document = state.sectionDocument
    val context = LocalContext.current
    val view = LocalView.current
    val reducedMotion =
        remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // How far past an edge, as a fraction of the reading area's height (positive: past the bottom).
    var overscroll by remember { mutableFloatStateOf(0f) }
    val hint = state.pullHint(overscroll)
    // A tick when the pull crosses the threshold (either way back is silent).
    val armed = hint?.armed == true
    LaunchedEffect(armed) {
        if (armed) {
            val tick = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else CONTEXT_CLICK
            view.performHapticFeedback(tick)
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 56.dp)) {
        when {
            state.preparingBook || document == null ->
                Text(
                    if (state.preparingBook) "Opening the book…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            else ->
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { PullWebView(it) },
                    update = { web ->
                        web.onPull = { px -> overscroll = if (web.height > 0) px / web.height else 0f }
                        web.onRelease = { px ->
                            overscroll = 0f
                            if (web.height > 0) onIntent(ReaderIntent.PullReleased(px / web.height))
                        }
                        if (web.tag != document) {
                            web.tag = document
                            web.fromEnd = state.sectionFromEnd
                            web.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
                            web.scrollTo(0, 0)
                        }
                    },
                )
        }
        hint?.let {
            PullHintView(
                it,
                reducedMotion,
                Modifier.align(if (it.forward) Alignment.BottomCenter else Alignment.TopCenter).padding(16.dp),
            )
        }
        if (state.atEnd && state.nextChapterId != null && document != null && hint == null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                PrimaryButton(state.nextLabel ?: "Next →", { onOpenChapter(state.nextChapterId!!) })
            }
        }
    }
}

/**
 * "Pull for the next section" → "Release for the next section": slides in from its edge and grows with the pull,
 * a bar fills up to the threshold, red once letting go turns. Reduced motion: no slide, no growth, no colour fade.
 */
@Composable
private fun PullHintView(
    hint: PullHint,
    reducedMotion: Boolean,
    modifier: Modifier,
) {
    val background by animateColorAsState(
        if (hint.armed) Brand.Red else Brand.Ink2,
        animationSpec = if (reducedMotion) snap() else tween(120),
        label = "pullBackground",
    )
    val text =
        when {
            hint.armed -> Brand.Ink
            hint.enabled -> Brand.Paper
            else -> Brand.Muted
        }
    val shape = RoundedCornerShape(999.dp)
    Column(
        modifier
            .graphicsLayer {
                if (!reducedMotion) {
                    val grow = 0.85f + 0.15f * hint.progress
                    scaleX = grow
                    scaleY = grow
                    // Slides in from its edge as the pull grows.
                    translationY = (1f - hint.progress) * 24.dp.toPx() * if (hint.forward) 1f else -1f
                }
                alpha = if (reducedMotion) 1f else 0.4f + 0.6f * hint.progress
            }.background(background, shape)
            .border(1.dp, if (hint.armed) Brand.Ink else Brand.Line, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                !hint.enabled -> hint.label
                hint.forward -> "↑  " + hint.label
                else -> "↓  " + hint.label
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge.copy(color = text),
        )
        if (hint.enabled) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(120.dp)
                    .height(3.dp)
                    .clip(shape)
                    .background(if (hint.armed) Brand.Ink.copy(alpha = 0.3f) else Brand.Line),
            ) {
                Box(Modifier.fillMaxWidth(hint.progress).height(3.dp).background(if (hint.armed) Brand.Ink else Brand.Red))
            }
        }
    }
}

/**
 * The section's web view, measuring pulls past its edges. A web view never scrolls past its content: once it
 * can't scroll further in the finger's direction, the rest of the drag is the pull (halved, like a rubber band,
 * so it reads as far as the same pull on iOS). Its own scrolling is untouched: every event still goes to it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetJavaScriptEnabled")
private class PullWebView(
    context: Context,
) : WebView(context) {
    /** The pull in pixels, positive past the bottom, while the finger is down (0 when there is none). */
    var onPull: (Float) -> Unit = {}

    /** The finger let go during a pull. */
    var onRelease: (Float) -> Unit = {}

    /** The section loading now opens at its end (reached going back). */
    var fromEnd = false

    private var lastY = 0f
    private var drag = 0f
    private var touched = false

    init {
        setBackgroundColor(android.graphics.Color.parseColor("#0B0B0E"))
        settings.javaScriptEnabled = false
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient =
            object : WebViewClient() {
                // No link leaves the book.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ) = true

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    if (!fromEnd) return
                    touched = false
                    // The page's height settles over the first frames: try again a moment later.
                    END_RETRIES_MS.forEach { delay -> postDelayed({ if (!touched) scrollToEnd() }, delay) }
                }
            }
    }

    private fun scrollToEnd() {
        scrollTo(0, (computeVerticalScrollRange() - height).coerceAtLeast(0))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touched = true
                lastY = event.y
                drag = 0f
            }
            MotionEvent.ACTION_MOVE ->
                if (event.pointerCount == 1) {
                    // Positive: the finger goes up (towards what comes next).
                    val dy = lastY - event.y
                    lastY = event.y
                    drag =
                        when {
                            drag > 0f || (drag == 0f && dy > 0f && !canScrollVertically(1)) -> (drag + dy).coerceAtLeast(0f)
                            drag < 0f || (drag == 0f && dy < 0f && !canScrollVertically(-1)) -> (drag + dy).coerceAtMost(0f)
                            else -> 0f
                        }
                    onPull(drag * RESISTANCE)
                } else if (drag != 0f) {
                    drag = 0f
                    onPull(0f)
                }
            MotionEvent.ACTION_UP -> {
                if (drag != 0f) onRelease(drag * RESISTANCE) else onPull(0f)
                drag = 0f
            }
            MotionEvent.ACTION_CANCEL -> {
                drag = 0f
                onPull(0f)
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val RESISTANCE = 0.5f
        val END_RETRIES_MS = listOf(0L, 120L, 400L)
    }
}
