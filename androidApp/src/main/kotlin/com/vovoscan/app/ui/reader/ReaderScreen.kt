package com.vovoscan.app.ui.reader

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.vovoscan.app.MainActivity
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.TallPages
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.presentation.ReaderCommand
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderItem
import com.vovoscan.feature.reader.presentation.ReaderUiState
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import com.vovoscan.feature.reader.presentation.TapOverlay
import com.vovoscan.feature.reader.presentation.TransitionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Reader. Comics: paged (right to left, left to right, vertical) or a long strip (with or without gaps), tap zones
 * (a single press pages or scrolls at once), chapter cards before the first and after the last page, a settings
 * sheet. The screen measures, draws, animates and reports taps: every decision lives in `ReaderViewModel`.
 * `onOpenChapter(chapterId, startPage)` replaces this reader.
 */
@Composable
fun ReaderScreen(
    sourceKey: String,
    titleId: String,
    chapterId: String,
    startPage: Int,
    onBack: () -> Unit,
    onOpenChapter: (String, Int) -> Unit,
    viewModel: ReaderViewModel =
        koinViewModel(key = "reader:$sourceKey/$chapterId") { parametersOf(sourceKey, titleId, chapterId, startPage) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent = viewModel::onIntent
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Keep the screen awake while reading (setting, on by default).
    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // Volume keys: the activity hands them over while the reader is on screen; the ViewModel says if it used them.
    val activity = LocalActivity.current as? MainActivity
    DisposableEffect(activity, viewModel) {
        activity?.volumeKeys = { up -> viewModel.onVolumeKey(up) }
        onDispose { activity?.volumeKeys = null }
    }
    // Opening another chapter (a chapter card, or a tap past the end): handled here, whatever the mode.
    val command = state.command
    LaunchedEffect(command) {
        if (command is ReaderCommand.OpenChapter) {
            onIntent(ReaderIntent.CommandHandled(command.seq))
            onOpenChapter(command.chapterId, command.startPage)
        }
    }

    Box(Modifier.fillMaxSize().background(Brand.Ink)) {
        when {
            state.unavailable ->
                Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This chapter isn't saved on this device yet.", style = MaterialTheme.typography.bodyMedium)
                    state.error?.let { ErrorText(it) }
                    GhostButton("Try again", { onIntent(ReaderIntent.Retry) })
                }
            state.isBook -> BookReader(state, onIntent) { onOpenChapter(it, 0) }
            state.items.isEmpty() -> Unit
            else -> ComicReader(state, onIntent)
        }
        state.tapOverlay.let { overlay ->
            AnimatedVisibility(overlay != null, enter = fadeIn(), exit = fadeOut()) {
                // Keeps drawing the last overlay while it fades out.
                var shown by remember { mutableStateOf(overlay) }
                if (overlay != null) shown = overlay
                shown?.let { TapZonesOverlay(it) }
            }
        }
        // A book keeps its bars: the web view receives the taps (text scrolling).
        if (state.chromeVisible || state.isBook) {
            TopBar(
                state,
                onBack,
                {
                    onIntent(ReaderIntent.SettingsOpened)
                    settingsOpen = true
                },
                Modifier.align(Alignment.TopCenter),
            )
            BottomBar(state, onIntent, { onOpenChapter(it, 0) }, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (settingsOpen && !state.isBook) {
        ReaderSettingsSheet(state.settings, onIntent) {
            settingsOpen = false
            onIntent(ReaderIntent.SettingsClosed)
        }
    }
}

/**
 * Comic: the mode's layout under one tap detector. A drag, fling or pinch is not a tap (the scrolling layout
 * consumes it first). Double tap to zoom (off by default) makes single taps wait for a possible second tap.
 */
@Composable
private fun ComicReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoomed by remember { mutableStateOf(false) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (zoomed) ZOOM else 1f, label = "zoom")
    val translationX by animateFloatAsState(if (zoomed) panX else 0f, label = "panX")
    val translationY by animateFloatAsState(if (zoomed) panY else 0f, label = "panY")
    // A new page or mode starts unzoomed.
    LaunchedEffect(state.currentItem.takeIf { state.isPaged }, state.mode) { zoomed = false }
    if (!state.doubleTapZoom && zoomed) zoomed = false

    fun clampPan(
        x: Float,
        y: Float,
    ) {
        panX = x.coerceIn(size.width * (1 - ZOOM), 0f)
        panY = y.coerceIn(size.height * (1 - ZOOM), 0f)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Measured outside the zoom: taps are fractions of the reading area as the reader sees it.
            .onSizeChanged { size = it }
            .pointerInput(state.doubleTapZoom) {
                detectTapGestures(
                    onDoubleTap =
                        if (state.doubleTapZoom) {
                            { point: Offset ->
                                if (zoomed) {
                                    zoomed = false
                                } else {
                                    // Zoom in around the finger: that point stays where it is.
                                    clampPan(point.x * (1 - ZOOM), point.y * (1 - ZOOM))
                                    zoomed = true
                                }
                            }
                        } else {
                            null
                        },
                    onTap = { point ->
                        if (size.width > 0 && size.height > 0) {
                            onIntent(ReaderIntent.Tap(point.x / size.width, point.y / size.height))
                        }
                    },
                )
            }.then(
                if (zoomed) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            clampPan(panX + drag.x, panY + drag.y)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    this.translationX = translationX
                    this.translationY = translationY
                },
        ) {
            if (state.isPaged) {
                PagedReader(
                    state,
                    onIntent,
                    scrollEnabled = !zoomed,
                )
            } else {
                StripReader(state, onIntent, scrollEnabled = !zoomed)
            }
        }
    }
}

/**
 * One page at a time: horizontal (right to left: reversed layout) or vertical. A tall page (`TallPages`) is shown
 * fit to width and scrolls inside its page; the pager takes over at its edges (nested scrolling: the page scrolls
 * first, what is left of the drag moves the pager).
 */
@Composable
private fun PagedReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    scrollEnabled: Boolean,
) {
    val pager = rememberPagerState(initialPage = state.currentItem) { state.items.size }
    val scope = rememberCoroutineScope()
    // A tall page asked to show its bottom (going back), until it has.
    var bottomRequest by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { onIntent(ReaderIntent.ItemVisible(it)) }
    }
    val command = state.command
    LaunchedEffect(command) {
        if (command is ReaderCommand.GoToItem) {
            if (command.atBottom) bottomRequest = command.item
            // Its own scope: the animation goes on once the command is cleared.
            scope.launch { if (command.animated) pager.animateScrollToPage(command.item) else pager.scrollToPage(command.item) }
            onIntent(ReaderIntent.CommandHandled(command.seq))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        val page: @Composable (Int) -> Unit = { index ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val item = state.items[index]) {
                    is ReaderItem.Page ->
                        PagedPage(
                            item.page,
                            index,
                            state,
                            onIntent,
                            area,
                            scrollEnabled,
                            showBottom = bottomRequest == index,
                            onBottomShown = { if (bottomRequest == index) bottomRequest = null },
                        )
                    is ReaderItem.Transition -> {
                        if (index == state.currentItem) {
                            LaunchedEffect(index) { onIntent(ReaderIntent.PageScroll(index, tall = false, atTop = true, atBottom = true)) }
                        }
                        TransitionCardView(item.card, onIntent, Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (state.pagedHorizontally) {
            // reverseLayout: the first page is on the right, swiping right moves forward (manga direction).
            HorizontalPager(
                state = pager,
                reverseLayout = state.rightToLeft,
                beyondViewportPageCount = 1,
                userScrollEnabled = scrollEnabled,
                key = { state.items[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page(it) }
        } else {
            VerticalPager(
                state = pager,
                beyondViewportPageCount = 1,
                userScrollEnabled = scrollEnabled,
                key = { state.items[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page(it) }
        }
    }
}

/**
 * A page of a paged mode: fitted to the screen, or, when tall (`TallPages.fitsWidth`, from its size or, unknown,
 * the image's once loaded), fit to width and scrolled inside the page. The current page reports its scroll
 * (`PageScroll`) and runs tap scrolls (`ScrollBy`). Pages before the current one wait at their bottom, pages after
 * it at their top: swiping back reveals the end of the previous page.
 */
@Composable
private fun PagedPage(
    page: ReaderPage,
    index: Int,
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    area: IntSize,
    scrollEnabled: Boolean,
    showBottom: Boolean,
    onBottomShown: () -> Unit,
) {
    var loadedRatio by remember(page.url) { mutableStateOf<Float?>(null) }
    val ratio = if (page.hasSize) page.aspectRatio else loadedRatio
    val tall = ratio != null && TallPages.fitsWidth(ratio, area.width.toFloat(), area.height.toFloat())
    val scroll = rememberScrollState()
    val current = index == state.currentItem
    var pinBottom by remember { mutableStateOf(index < state.currentItem) }
    LaunchedEffect(state.currentItem) {
        when {
            index < state.currentItem -> pinBottom = true
            index > state.currentItem -> {
                pinBottom = false
                scroll.scrollTo(0)
            }
        }
    }
    LaunchedEffect(showBottom) { if (showBottom) pinBottom = true }
    // Bottom wanted: once the page is measured (its height may come with the image), until the finger moves it.
    LaunchedEffect(pinBottom, tall, scroll.maxValue) {
        if (pinBottom && tall && scroll.maxValue in 1 until Int.MAX_VALUE) {
            scroll.scrollTo(scroll.maxValue)
            onBottomShown()
        }
    }
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) pinBottom = false }
    }
    if (current) {
        LaunchedEffect(index, scroll) {
            snapshotFlow { Triple(tall, !tall || !scroll.canScrollBackward, !tall || !scroll.canScrollForward) }
                .distinctUntilChanged()
                .collect { (t, top, bottom) -> onIntent(ReaderIntent.PageScroll(index, t, top, bottom)) }
        }
        val scope = rememberCoroutineScope()
        val tapScroll = remember { TapScroll() }
        val command = state.command
        LaunchedEffect(command) {
            if (command is ReaderCommand.ScrollBy) {
                pinBottom = false
                tapScroll.run(scope, scroll, command.fraction * area.height)
                onIntent(ReaderIntent.CommandHandled(command.seq))
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (tall) Modifier.verticalScroll(scroll, enabled = scrollEnabled) else Modifier),
        contentAlignment = if (tall) Alignment.TopCenter else Alignment.Center,
    ) {
        PageImage(
            page,
            if (tall) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            if (tall) ContentScale.FillWidth else ContentScale.Fit,
            reserve = tall,
            onLoadedRatio = { loadedRatio = it },
        )
    }
}

/** Continuous vertical strip (webtoon), with the mode's gap between pages. */
@Composable
private fun StripReader(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    scrollEnabled: Boolean,
) {
    // Resume (and a mode change) at the current item.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.currentItem)
    val scope = rememberCoroutineScope()
    // Until the strip moves (finger or tap scroll), positioning at the current item (clamped at the end of the
    // chapter) must not change the page.
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { if (it) moved = true }
    }
    LaunchedEffect(list) {
        snapshotFlow { if (moved) list.firstVisibleItemIndex else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { onIntent(ReaderIntent.ItemVisible(it)) }
    }
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollBackward to !list.canScrollForward }
            .distinctUntilChanged()
            .collect { (top, bottom) -> onIntent(ReaderIntent.StripEdges(top, bottom)) }
    }
    val tapScroll = remember { TapScroll() }
    val command = state.command
    LaunchedEffect(command) {
        if (command is ReaderCommand.ScrollBy) {
            tapScroll.run(scope, list, command.fraction * list.layoutInfo.viewportSize.height)
            onIntent(ReaderIntent.CommandHandled(command.seq))
        }
    }
    LazyColumn(
        state = list,
        userScrollEnabled = scrollEnabled,
        verticalArrangement = Arrangement.spacedBy(state.pageGap.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is ReaderItem.Page -> PageImage(item.page, Modifier.fillMaxWidth())
                is ReaderItem.Transition -> TransitionCardView(item.card, onIntent, Modifier.fillMaxWidth().heightIn(min = 280.dp))
            }
        }
    }
}

/** "Finished: Chapter 3" / "Next: Chapter 4 · The last gate" and "Next chapter →". */
@Composable
private fun TransitionCardView(
    card: TransitionCard,
    onIntent: (ReaderIntent) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Ink2, RoundedCornerShape(12.dp))
                .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(card.heading, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
            Text(card.target, style = MaterialTheme.typography.titleMedium)
            card.actionLabel?.let { label ->
                Spacer(Modifier.size(4.dp))
                if (card.forward) {
                    PrimaryButton(label, { onIntent(ReaderIntent.OpenNextChapter) })
                } else {
                    GhostButton(label, { onIntent(ReaderIntent.OpenPreviousChapter) })
                }
            }
        }
    }
}

/** The tap zones for a moment: red goes forward, cyan goes back, ink opens the menu. */
@Composable
private fun TapZonesOverlay(overlay: TapOverlay) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        overlay.rects.forEach { rect ->
            Box(
                Modifier
                    .offset(x = maxWidth * rect.left, y = maxHeight * rect.top)
                    .size(width = maxWidth * (rect.right - rect.left), height = maxHeight * (rect.bottom - rect.top))
                    .background(zoneColor(rect.action).copy(alpha = if (rect.action == TapAction.Menu) 0.55f else 0.35f))
                    .border(1.dp, Brand.Ink.copy(alpha = 0.6f)),
            )
        }
        overlay.labels.forEach { label ->
            Box(
                Modifier
                    .offset(x = maxWidth * label.x - LABEL_WIDTH / 2, y = maxHeight * label.y - 14.dp)
                    .width(LABEL_WIDTH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Paper),
                    modifier =
                        Modifier
                            .background(
                                Brand.Ink.copy(alpha = 0.7f),
                                RoundedCornerShape(999.dp),
                            ).padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/**
 * The tap scroll in progress (a strip, or a tall page): its job and the distance it has left. Taps faster than the
 * animation add up: what the previous one had left to scroll is carried over. A finger cancels the animation, so
 * nothing is carried after it.
 */
private class TapScroll {
    var job: Job? = null
    var left = 0f

    fun run(
        scope: CoroutineScope,
        target: ScrollableState,
        distance: Float,
    ) {
        val carry = if (job?.isActive == true) left else 0f
        job?.cancel()
        val total = carry + distance
        left = total
        job =
            scope.launch {
                target.scroll {
                    var done = 0f
                    animate(0f, total, animationSpec = tween(TAP_SCROLL_MS)) { value, _ ->
                        scrollBy(value - done)
                        done = value
                        left = total - value
                    }
                }
            }
    }
}

private const val TAP_SCROLL_MS = 300
private const val MAX_HARDWARE_SIDE = 4096
private val LABEL_WIDTH = 140.dp
private const val ZOOM = 2f

private fun zoneColor(action: TapAction): Color =
    when (action) {
        TapAction.Next -> Brand.Red
        TapAction.Previous -> Brand.Cyan
        TapAction.Menu -> Brand.Ink
    }

@Composable
private fun PageImage(
    page: ReaderPage,
    modifier: Modifier,
    scale: ContentScale = ContentScale.FillWidth,
    reserve: Boolean = true,
    onLoadedRatio: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    var attempt by remember(page.url) { mutableIntStateOf(0) }
    var failed by remember(page.url) { mutableStateOf(false) }
    // The image's own width / height once loaded, for a page whose size the source didn't give.
    var loadedRatio by remember(page.url) { mutableStateOf<Float?>(null) }
    // Webtoon pages can be very tall (800 × 14 000 px): beyond what a GPU texture holds, a hardware bitmap fails
    // to draw, so tall pages, and pages of unknown height, are decoded as ordinary bitmaps.
    val request =
        remember(page.url, attempt) {
            ImageRequest
                .Builder(context)
                .data(page.url)
                .allowHardware(page.hasSize && page.height <= MAX_HARDWARE_SIDE)
                .build()
        }
    // Known size: space reserved from it, no jump when the image arrives. Unknown: a placeholder, then the image's
    // own shape (never the placeholder's: a strip squeezed into it would be cropped).
    val ratio = if (page.hasSize) page.aspectRatio else loadedRatio ?: page.aspectRatio
    val sized = if (reserve) modifier.aspectRatio(ratio) else modifier
    if (failed) {
        Column(
            sized.background(Brand.Ink2).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This page didn't load.", style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            Spacer(Modifier.size(12.dp))
            GhostButton(
                "Retry",
                onClick = {
                    failed = false
                    attempt++
                },
                Modifier.width(160.dp),
            )
        }
        return
    }
    AsyncImage(
        model = request,
        contentDescription = "Page ${page.index + 1}",
        contentScale = scale,
        onSuccess = { success ->
            val image = success.result.image
            if (image.width > 0 && image.height > 0) {
                loadedRatio = image.width.toFloat() / image.height
                onLoadedRatio(image.width.toFloat() / image.height)
            }
        },
        onError = { failed = true },
        modifier = sized.background(Brand.Ink2),
    )
}

@Composable
private fun TopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Brand.Ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("←", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(state.titleName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.chapterLabel, style = MaterialTheme.typography.bodySmall)
        }
        if (!state.isBook) {
            // Text presentation of the gear (not the emoji).
            Text(
                "⚙︎",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(onClick = onSettings).padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: ReaderUiState,
    onIntent: (ReaderIntent) -> Unit,
    onOpenChapter: (String) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(Brand.Ink.copy(alpha = 0.92f)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isBook) {
            Text(
                "←",
                style = MaterialTheme.typography.titleMedium.copy(color = if (state.canGoBack) Brand.Paper else Brand.Line),
                modifier = Modifier.clickable(enabled = state.canGoBack) { onIntent(ReaderIntent.Previous) }.padding(end = 16.dp),
            )
        }
        Text(state.positionLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (state.isBook && state.canGoForward) {
            Text(
                "Next section →",
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onIntent(ReaderIntent.Next) },
            )
            return@Row
        }
        state.previousChapterId?.let { previous ->
            Text("← Previous", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { onOpenChapter(previous) })
            Spacer(Modifier.width(20.dp))
        }
        state.nextChapterId?.let { next ->
            Text(
                "Next →",
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onOpenChapter(next) },
            )
        }
    }
}
