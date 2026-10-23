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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.presentation.BookIntent
import com.vovoscan.feature.reader.presentation.PullHint
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderUiState
import com.vovoscan.feature.reader.presentation.SectionOpening
import kotlin.math.roundToInt

/**
 * Book: one section at a time, in a web view with JavaScript off, no network and no navigation. The HTML is
 * already sanitised and styled (BookStyle); images are embedded. Pulling past the bottom (or the top) of the
 * section shows a hint that grows with the pull and turns the section on release past the threshold: the web view
 * only measures the pull, `ReaderUiState.pullHint` and `ReaderIntent.PullReleased` decide. The page follows the
 * device's text size (`BookIntent.SystemTextScale`); where a section opens comes from `ReaderUiState.book.opening`,
 * and the web view reports where its top is (`BookIntent.SectionScrolled`) so the reader reopens there.
 */
@Composable
internal fun BookReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val document = state.sectionDocument
    val context = LocalContext.current
    // The device's text size (Android font scale): the page's text follows it.
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(fontScale) { onIntent(BookIntent.SystemTextScale(fontScale)) }
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
                        web.onScrolled = { section, fraction -> onIntent(BookIntent.SectionScrolled(section, fraction)) }
                        web.setBackgroundColor(rgb(state.book.pageBackground).toArgb())
                        web.show(document, state.currentIndex, state.book.opening)
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

    /** Where the top of the page is: the section shown and the fraction of its height (not while it is placed). */
    var onScrolled: (Int, Float) -> Unit = { _, _ -> }

    private var lastY = 0f
    private var drag = 0f
    private var touched = false

    /** The section in the page, its opening to apply once loaded, the last opening applied. */
    private var section = -1
    private var pending: SectionOpening? = null
    private var appliedSeq = -1
    private var loading = false

    /** An opening is being applied (the page is not where it will be yet): nothing is reported meanwhile. */
    private var placing = false
    private var placeGeneration = 0

    init {
        setBackgroundColor(android.graphics.Color.parseColor("#0B0B0E"))
        settings.javaScriptEnabled = false
        settings.blockNetworkLoads = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // The page sets its own text size from the device's (BookStyle): nothing on top of it.
        settings.textZoom = 100
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
                    // Once per load (a jump to an anchor inside the page finishes too).
                    if (!loading) return
                    loading = false
                    place(pending)
                }
            }
    }

    /**
     * Shows [document] (section [index]): loaded when it changed, then placed as [opening] says once laid out; an
     * opening for the section already shown (a contents entry inside it) is placed at once.
     */
    fun show(
        document: String,
        index: Int,
        opening: SectionOpening?,
    ) {
        val forHere = opening?.takeIf { it.section == index && it.seq != appliedSeq }
        if (tag != document) {
            tag = document
            section = index
            pending = forHere
            forHere?.let { appliedSeq = it.seq }
            loading = true
            placing = true
            loadDataWithBaseURL(BASE_URL, document, "text/html", "utf-8", null)
            scrollTo(0, 0)
        } else if (forHere != null && !loading) {
            appliedSeq = forHere.seq
            place(forHere)
        }
    }

    /**
     * Puts the page where [opening] says (its top when null). A section of several MB keeps growing for a moment
     * after it has loaded: placed again a few times, unless the reader touches it first. Then reports where it is.
     */
    private fun place(opening: SectionOpening?) {
        touched = false
        placeGeneration += 1
        val generation = placeGeneration
        if (opening == null || (opening.fraction == null && opening.anchor == null && !opening.fromEnd)) {
            placing = false
            report()
            return
        }
        placing = true
        PLACE_RETRIES_MS.forEachIndexed { step, delay ->
            postDelayed({
                if (generation != placeGeneration || touched) return@postDelayed
                when {
                    // Same document: the browser scrolls the element to the top, no page is loaded.
                    opening.anchor != null -> loadUrl("$BASE_URL#${opening.anchor}")
                    opening.fromEnd -> scrollTo(0, (computeVerticalScrollRange() - height).coerceAtLeast(0))
                    else -> scrollTo(0, ((opening.fraction ?: 0f) * computeVerticalScrollRange()).roundToInt())
                }
                if (step == PLACE_RETRIES_MS.lastIndex) {
                    placing = false
                    report()
                }
            }, delay)
        }
    }

    private fun report() {
        val range = computeVerticalScrollRange()
        if (placing || loading || section < 0 || range <= 0) return
        onScrolled(section, (scrollY.toFloat() / range).coerceIn(0f, 1f))
    }

    override fun onScrollChanged(
        l: Int,
        t: Int,
        oldl: Int,
        oldt: Int,
    ) {
        super.onScrollChanged(l, t, oldl, oldt)
        report()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touched = true
                // The reader takes over: where the page is now is theirs.
                if (placing && !loading) placing = false
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

        /** The page's height settles over the first frames (longer for a big section): placed again meanwhile. */
        val PLACE_RETRIES_MS = listOf(0L, 120L, 400L, 1000L)

        /**
         * The document's address, never fetched (no network): a jump to an anchor is a navigation inside this same
         * document, which the web view does without JavaScript.
         */
        const val BASE_URL = "https://book.vovoscan.invalid/section"
    }
}
