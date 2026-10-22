package com.vovoscan.app.ui.reader

import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.size.Size
import com.vovoscan.app.MainActivity
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.LanguageDock
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.common.content
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.TallPages
import com.vovoscan.feature.reader.domain.TapAction
import com.vovoscan.feature.reader.presentation.CoachGesture
import com.vovoscan.feature.reader.presentation.ReaderCoach
import com.vovoscan.feature.reader.presentation.ReaderCommand
import com.vovoscan.feature.reader.presentation.ReaderIntent
import com.vovoscan.feature.reader.presentation.ReaderItem
import com.vovoscan.feature.reader.presentation.ReaderUiState
import com.vovoscan.feature.reader.presentation.ReaderViewModel
import com.vovoscan.feature.reader.presentation.TapOverlay
import com.vovoscan.feature.reader.presentation.TransitionCard
import com.vovoscan.feature.reader.presentation.TranslationIntent
import com.vovoscan.feature.reader.presentation.TranslationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** "Watch the episode from here": the episode of this chapter, from that page. */
    onWatch: (chapterId: String, page: Int) -> Unit = { _, _ -> },
    viewModel: ReaderViewModel =
        koinViewModel(key = "reader:$sourceKey/$chapterId") { parametersOf(sourceKey, titleId, chapterId, startPage) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onIntent = viewModel::onIntent
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The reader's language (chapters of our catalog): any time from the bar, free when already made, quoted first.
    val translation: TranslationViewModel =
        koinViewModel(key = "translation:$sourceKey/$chapterId") { parametersOf(sourceKey, chapterId) }
    val translationState by translation.state.collectAsStateWithLifecycle()
    var languagesOpen by rememberSaveable { mutableStateOf(false) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    val languageAction =
        remember(translationState.available) {
            if (translationState.available) ReaderLanguageAction(translationState.endCardLabel) { languagesOpen = true } else null
        }

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
        if (command is ReaderCommand.OpenEpisode) {
            onIntent(ReaderIntent.CommandHandled(command.seq))
            onWatch(command.chapterId, command.page)
        }
    }
    // Remove animations (developer options, accessibility): jumps instead of the spring, no glide, no fades.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        onIntent(ReaderIntent.ReducedMotion(scale == 0f))
    }
    // Immersive: the system bars leave with the reader's own bars (a swipe from the edge shows them for a moment).
    val window = activity?.window
    DisposableEffect(window, state.chromeVisible, state.isBook) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (state.chromeVisible || state.isBook) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(Modifier.fillMaxSize().background(Brand.Ink)) {
        when {
            state.unavailable ->
                Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(I18n.text("reader.notSaved"), style = MaterialTheme.typography.bodyMedium)
                    state.error?.let { ErrorText(it) }
                    GhostButton(I18n.text("common.tryAgain"), { onIntent(ReaderIntent.Retry) })
                }
            state.isBook -> BookReader(state, onIntent) { onOpenChapter(it, 0) }
            state.items.isEmpty() -> Unit
            else ->
                CompositionLocalProvider(
                    LocalPageLettering provides { translationState.letteringFor(it) },
                    LocalReaderLanguageAction provides languageAction,
                ) {
                    ComicReader(state, onIntent)
                }
        }
        state.tapOverlay.let { overlay ->
            AnimatedVisibility(overlay != null, enter = fadeIn(), exit = fadeOut()) {
                // Keeps drawing the last overlay while it fades out.
                var shown by remember { mutableStateOf(overlay) }
                if (overlay != null) shown = overlay
                shown?.let { TapZonesOverlay(it) }
            }
        }
        // The bars hidden: a thin line at the very top says how much of the strip is read.
        if (!state.chromeVisible && state.isStrip) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(state.progress)
                    .height(2.dp)
                    .background(Brand.Red)
                    .semantics { contentDescription = state.progressLabel },
            )
        }
        // The chapter's title over the first page, for a moment.
        AnimatedVisibility(
            state.titleCard != null,
            enter = if (state.reducedMotion) EnterTransition.None else fadeIn(tween(400)),
            exit = if (state.reducedMotion) ExitTransition.None else fadeOut(tween(600)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            var shown by remember { mutableStateOf(state.titleCard.orEmpty()) }
            state.titleCard?.let { shown = it }
            Text(
                shown,
                style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper),
                modifier =
                    Modifier
                        .background(
                            Brand.Ink.copy(alpha = 0.78f),
                            RoundedCornerShape(10.dp),
                        ).padding(horizontal = 18.dp, vertical = 12.dp),
            )
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
                onWatch = { onIntent(ReaderIntent.ReadWithVoice) },
                onHelp = { onIntent(ReaderIntent.ShowCoach) },
                language = {
                    if (!state.isBook && translationState.available) LanguageButton(translationState) { languagesOpen = true }
                },
            )
            BottomBar(state, onIntent, { onOpenChapter(it, 0) }, Modifier.align(Alignment.BottomCenter))
        }
        // A language on its way: the page keeps its lettering, the dock shows the cost, then the progress.
        AnimatedVisibility(
            translationState.dock != null && !state.isBook,
            enter = if (state.reducedMotion) EnterTransition.None else slideInVertically { it } + fadeIn(),
            exit = if (state.reducedMotion) ExitTransition.None else slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            var shown by remember { mutableStateOf(translationState.dock) }
            translationState.dock?.let { shown = it }
            shown?.let { dock ->
                LanguageDock(
                    dock.content(),
                    onConfirm = { translation.onIntent(TranslationIntent.ConfirmOffer) },
                    onAddKey = { keysOpen = true },
                    onRetry = { translation.onIntent(TranslationIntent.Retry) },
                    onClose = { translation.onIntent(TranslationIntent.CloseDock) },
                )
            }
        }
        // How to read, the first time a chapter of its kind opens: any tap closes it.
        AnimatedVisibility(
            state.coach != null,
            enter = if (state.reducedMotion) EnterTransition.None else fadeIn(tween(250)),
            exit = if (state.reducedMotion) ExitTransition.None else fadeOut(tween(250)),
        ) {
            var shown by remember { mutableStateOf(state.coach) }
            state.coach?.let { shown = it }
            shown?.let { CoachOverlay(it) { onIntent(ReaderIntent.DismissCoach) } }
        }
    }
    if (languagesOpen) TranslationSheet(translationState, translation::onIntent) { languagesOpen = false }
    if (keysOpen) KeysSheet { keysOpen = false }
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
                FlowStrip(state, onIntent, scrollEnabled = !zoomed)
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
            onSize = { w, h -> onIntent(ReaderIntent.PageSized(page.index, w, h)) },
            onLoadedRatio = { loadedRatio = it },
        )
    }
}

/** "Finished: Chapter 3" / "Next: Chapter 4 · The last gate" and "Next chapter →". */
@Composable
internal fun TransitionCardView(
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
            // Reader SPEC §11: the language is one tap away here too.
            val language = LocalReaderLanguageAction.current
            if (card.forward && language != null) GhostButton("文A  ${language.label}", language.open)
        }
    }
}

/**
 * "How to read": the reader's real tap zones under a card of gesture lines. Static, no timer: the first tap anywhere
 * closes it (and is not a page turn); the "?" in the bars brings it back.
 */
@Composable
private fun CoachOverlay(
    coach: ReaderCoach,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brand.Ink.copy(alpha = if (coach.zones == null) 0.82f else 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = coach.dismiss,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        coach.zones?.let { TapZonesOverlay(it) }
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .background(Brand.Ink.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                .border(2.dp, Brand.Paper.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                coach.kicker.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(color = Brand.Ink, letterSpacing = 1.6.sp),
                modifier = Modifier.background(Brand.Yellow).padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Text(coach.title, style = MaterialTheme.typography.headlineSmall.copy(color = Brand.Paper))
            coach.hints.forEach { hint ->
                val help = hint.gesture == CoachGesture.Help
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        coachGlyph(hint.gesture),
                        style = MaterialTheme.typography.titleMedium.copy(color = if (help) Brand.Muted else Brand.Red),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(hint.text, style = MaterialTheme.typography.bodyMedium.copy(color = if (help) Brand.Muted else Brand.Paper))
                }
            }
            Text(
                coach.dismiss,
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .drawBehind {
                            val shift = 3.dp.toPx()
                            drawRect(Brand.Paper, topLeft = Offset(shift, shift), size = size)
                            drawRect(Brand.Red, size = size)
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** A text pictogram per gesture (no icon library on Android). */
private fun coachGlyph(gesture: CoachGesture): String =
    when (gesture) {
        CoachGesture.Tap -> "●"
        CoachGesture.Swipe -> "⇆"
        CoachGesture.DoubleTap -> "⊕"
        CoachGesture.Pull -> "↧"
        CoachGesture.Help -> "?"
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
internal fun PageImage(
    page: ReaderPage,
    modifier: Modifier,
    scale: ContentScale = ContentScale.FillWidth,
    reserve: Boolean = true,
    onSize: ((Int, Int) -> Unit)? = null,
    onLoadedRatio: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    // A page of unknown size is decoded for the screen's width, whatever its height: sized for the placeholder (a
    // box fitted to the screen), a webtoon strip came out a few hundred pixels wide and was blurred once it filled
    // the width.
    val screenWidth =
        with(LocalDensity.current) {
            LocalConfiguration.current.screenWidthDp.dp
                .roundToPx()
        }
    var attempt by remember(page.url) { mutableIntStateOf(0) }
    var failed by remember(page.url) { mutableStateOf(false) }
    // The image's own width / height once loaded, for a page whose size the source didn't give.
    var loadedRatio by remember(page.url) { mutableStateOf<Float?>(null) }
    // Webtoon pages can be very tall (800 × 14 000 px): beyond what a GPU texture holds, a hardware bitmap fails
    // to draw, so tall pages, and pages of unknown height, are decoded as ordinary bitmaps.
    val request =
        remember(page.url, attempt, screenWidth) {
            ImageRequest
                .Builder(context)
                .data(page.url)
                .allowHardware(page.hasSize && page.height <= MAX_HARDWARE_SIDE)
                .apply { if (!page.hasSize) size(Size(Dimension(screenWidth), Dimension.Undefined)) }
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
            Text(I18n.text("reader.pageFailed"), style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            Spacer(Modifier.size(12.dp))
            GhostButton(
                I18n.text("common.retry"),
                onClick = {
                    failed = false
                    attempt++
                },
                Modifier.width(160.dp),
            )
        }
        return
    }
    // The reader's language: the translated lettering over the page, in a box of the page's own shape (the image
    // may be fitted inside a larger area).
    val lettering = LocalPageLettering.current(page.index)
    Box(contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = I18n.textWith("library.page", mapOf("n" to page.index + 1)),
            contentScale = scale,
            onSuccess = { success ->
                val image = success.result.image
                if (image.width > 0 && image.height > 0) {
                    loadedRatio = image.width.toFloat() / image.height
                    if (!page.hasSize) onSize?.invoke(image.width, image.height)
                    onLoadedRatio(image.width.toFloat() / image.height)
                }
            },
            onError = { failed = true },
            modifier = sized.background(Brand.Ink2),
        )
        if (lettering.isNotEmpty()) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                LetteringOverlay(lettering, Modifier.aspectRatio(ratio))
            }
        }
    }
}

@Composable
private fun TopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
    onWatch: () -> Unit = {},
    onHelp: () -> Unit = {},
    language: @Composable () -> Unit = {},
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
        if (state.voiceAvailable) {
            Text(
                "▶︎",
                style = MaterialTheme.typography.titleMedium.copy(color = Brand.Red),
                modifier =
                    Modifier
                        .clickable(onClickLabel = state.voiceLabel) { onWatch() }
                        .semantics { contentDescription = state.voiceLabel }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        language()
        val help = I18n.text("reader.coach.show")
        Text(
            "?",
            style = MaterialTheme.typography.titleMedium.copy(color = Brand.Muted),
            modifier =
                Modifier
                    .clickable(onClickLabel = help, onClick = onHelp)
                    .semantics { contentDescription = help }
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        )
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
        Text(
            state.positionLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state.canFocus) {
            Text(
                state.focusLabel,
                style = MaterialTheme.typography.labelLarge.copy(color = if (state.focusOn) Brand.Yellow else Brand.Muted),
                modifier =
                    Modifier
                        .toggleable(state.focusOn, role = Role.Switch) { onIntent(ReaderIntent.ToggleFocus) }
                        .padding(end = 20.dp),
            )
        }
        if (state.isBook && state.canGoForward) {
            Text(
                I18n.text("reader.nextSection"),
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onIntent(ReaderIntent.Next) },
            )
            return@Row
        }
        state.previousChapterId?.let { previous ->
            Text(
                I18n.text("reader.previousArrow"),
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier.clickable {
                        onOpenChapter(previous)
                    },
            )
            Spacer(Modifier.width(20.dp))
        }
        state.nextChapterId?.let { next ->
            Text(
                I18n.text("reader.nextArrow"),
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
                modifier = Modifier.clickable { onOpenChapter(next) },
            )
        }
    }
}
