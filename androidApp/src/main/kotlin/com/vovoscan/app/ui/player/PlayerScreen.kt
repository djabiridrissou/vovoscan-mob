package com.vovoscan.app.ui.player

import android.app.Activity
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.LanguageDock
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.common.content
import com.vovoscan.app.ui.episode.EpisodeLanguageSheet
import com.vovoscan.app.ui.reader.KeysSheet
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.episodes.domain.player.CaptionFrame
import com.vovoscan.feature.episodes.domain.player.CaptionPlacement
import com.vovoscan.feature.episodes.domain.player.PageShot
import com.vovoscan.feature.episodes.domain.player.PlaybackPlan
import com.vovoscan.feature.episodes.domain.player.PlayerFrame
import com.vovoscan.feature.episodes.domain.player.StageTransform
import com.vovoscan.feature.episodes.presentation.EpisodeText
import com.vovoscan.feature.episodes.presentation.PlayerIntent
import com.vovoscan.feature.episodes.presentation.PlayerUiState
import com.vovoscan.feature.episodes.presentation.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The full-screen episode player: a 9:16 stage (letterboxed on other screens) drawn on a Compose Canvas at the
 * display's rate, with the controls over it (they fade while playing). Every frame asks [PlayerViewModel.frame] what
 * to draw and play; this screen only draws it and drives the audio players.
 */
@Composable
fun PlayerScreen(
    chapterId: String,
    nextChapterId: String?,
    onClose: () -> Unit,
    /** The next chapter and the language now playing (it may have changed in the player). */
    onNextChapter: (String, String) -> Unit,
    /** The episode's language ("" = the original). */
    language: String = "",
    /** Opened from the reader: start on this chapter page. */
    startPage: Int? = null,
    viewModel: PlayerViewModel =
        koinViewModel(key = "player:$chapterId~$language") { parametersOf(chapterId, nextChapterId, language, startPage) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // "Remove animations" in the accessibility settings: the whole panel, held.
    val reducedMotion =
        remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val images = remember { PageImages(context, scope) { viewModel.onIntent(PlayerIntent.MediaFailed(it)) } }
    val audio = remember { CueAudio(context) { viewModel.onIntent(PlayerIntent.MediaFailed(it)) } }
    DisposableEffect(audio) { onDispose { audio.release() } }

    // Full screen while the player is open.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // The screen stays on while the episode plays.
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }
    // In the background no frame comes: pause the clock and the sound.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onIntent(PlayerIntent.Pause)
        audio.pauseAll()
    }
    // Starts playing once, when the episode is loaded.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(state.ready) {
        if (state.ready && !started) {
            started = true
            viewModel.onIntent(PlayerIntent.Play)
        }
    }
    // Warm what the next beat needs: its page image and its voice line.
    LaunchedEffect(state.beat, state.ready) {
        viewModel.plan?.prefetchUrls(state.beat)?.forEach { url ->
            if (url in state.pageUrls) images.request(url) else audio.warmUp(url)
        }
    }

    var languagesOpen by rememberSaveable { mutableStateOf(false) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    var controlsShown by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShown, state.playing, touches) {
        if (controlsShown && state.playing) {
            delay(CONTROLS_MS)
            controlsShown = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    controlsShown = !controlsShown
                    touches += 1
                }
            },
    ) {
        when {
            state.ready -> Stage(viewModel, images, audio, reducedMotion)
            state.loading -> Centered { Text(EpisodeText.PLAYER_LOADING, style = MaterialTheme.typography.bodyMedium) }
            else ->
                Centered {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ErrorText(state.error.orEmpty())
                        PrimaryButton(I18n.text("common.tryAgain"), { viewModel.onIntent(PlayerIntent.Retry) })
                        GhostButton(I18n.text("common.close"), onClose)
                    }
                }
        }
        if (state.ready) {
            AnimatedVisibility(
                visible = controlsShown || !state.playing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(state, viewModel::onIntent, onClose, { languagesOpen = true }) { touches += 1 }
            }
            if (state.ended) EndCard(state, viewModel::onIntent, { languagesOpen = true }) { onNextChapter(it, state.language) }
        }
        // Another language on its way: the episode plays on under the dock, and switches once it is ready.
        AnimatedVisibility(
            state.languageDock != null,
            enter = if (reducedMotion) EnterTransition.None else slideInVertically { it } + fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            var shown by remember { mutableStateOf(state.languageDock) }
            state.languageDock?.let { shown = it }
            shown?.let { dock ->
                LanguageDock(
                    dock.content(),
                    onConfirm = { viewModel.onIntent(PlayerIntent.StartLanguage) },
                    onAddKey = {
                        viewModel.onIntent(PlayerIntent.Pause)
                        keysOpen = true
                    },
                    onRetry = { viewModel.onIntent(PlayerIntent.RetryLanguage) },
                    onClose = { viewModel.onIntent(PlayerIntent.CloseLanguageDock) },
                )
            }
        }
    }
    if (languagesOpen) {
        EpisodeLanguageSheet(
            state.languageOptions,
            state.languageQuery,
            onQuery = { viewModel.onIntent(PlayerIntent.LanguageQuery(it)) },
            onChoose = { viewModel.onIntent(PlayerIntent.ChooseLanguage(it)) },
        ) {
            languagesOpen = false
            viewModel.onIntent(PlayerIntent.LanguageQuery(""))
        }
    }
    if (keysOpen) KeysSheet { keysOpen = false }
}

/** The 9:16 stage, centred and letterboxed, redrawn on every display frame. */
@Composable
private fun Stage(
    viewModel: PlayerViewModel,
    images: PageImages,
    audio: CueAudio,
    reducedMotion: Boolean,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Written on every frame and read only while drawing: the Canvas redraws without recomposing anything.
    var frame by remember { mutableStateOf<PlayerFrame?>(null) }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val placements = remember { HashMap<String, CaptionPlacement>() }
    val path = remember { Path() }
    val layerPaint = remember { Paint() }
    // Screen readers get the caption even when it is not drawn (the balloon is legible in frame).
    val spoken by remember { derivedStateOf { frame?.caption?.text.orEmpty() } }

    LaunchedEffect(viewModel, size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        while (isActive) {
            withFrameNanos { nanos ->
                val next = viewModel.frame(nanos / 1e6, w, h, reducedMotion)
                frame = next
                if (next != null) {
                    images.request(next.shot.pageUrl)
                    next.ghost?.let { images.request(it.pageUrl) }
                    val ui = viewModel.state.value
                    audio.sync(next.audio, ui.playing, ui.speed, SystemClock.uptimeMillis())
                }
            }
            // Paused: a few frames a second are enough to follow a seek.
            if (!viewModel.state.value.playing) delay(PAUSED_FRAME_MS)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val wide = maxWidth / maxHeight > STAGE_ASPECT
        Canvas(
            Modifier
                .aspectRatio(STAGE_ASPECT, matchHeightConstraintsFirst = wide)
                .clipToBounds()
                .onSizeChanged { size = it }
                .semantics { contentDescription = spoken },
        ) {
            val f = frame ?: return@Canvas
            drawShot(f.shot, images, path)
            f.ghost?.let { ghost ->
                val alpha = (1 - f.shot.framing.mix).toFloat().coerceIn(0f, 1f)
                if (alpha > 0.001f) {
                    layerPaint.alpha = alpha
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, this.size), layerPaint)
                    drawShot(ghost, images, path)
                    drawContext.canvas.restore()
                }
            }
            if (f.veil > 0) drawRect(Color.Black.copy(alpha = f.veil.toFloat().coerceIn(0f, 1f)))
            f.caption?.let { drawCaption(it, f.beat, viewModel, measurer, placements) }
        }
    }
}

/** Backdrop (blurred, darkened), then the page clipped to the panel's outline, then the bubble's discreet outline. */
private fun DrawScope.drawShot(
    shot: PageShot,
    images: PageImages,
    path: Path,
) {
    val page = images.peek(shot.pageUrl) ?: return
    val backdrop = shot.backdropTransform
    drawPage(page.backdrop.width, page.backdrop.height, shot, backdrop) { drawImage(page.backdrop) }
    drawRect(BACKDROP_SHADE)
    val t = shot.transform
    val quad = shot.framing.shape
    path.reset()
    path.moveTo(x(quad.p0.x, t), y(quad.p0.y, t))
    path.lineTo(x(quad.p1.x, t), y(quad.p1.y, t))
    path.lineTo(x(quad.p2.x, t), y(quad.p2.y, t))
    path.lineTo(x(quad.p3.x, t), y(quad.p3.y, t))
    path.close()
    clipPath(path) { drawPage(page.image.width, page.image.height, shot, t) { drawImage(page.image) } }
    shot.bubbleBox?.let { box ->
        drawRoundRect(
            color = BUBBLE_OUTLINE,
            topLeft = Offset(x(box.x, t), y(box.y, t)),
            size = Size((box.w * t.scale).toFloat(), (box.h * t.scale).toFloat()),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** Draws a bitmap of the page (decoded at any size) as if it were `pageWidth × pageHeight` placed by [t]. */
private inline fun DrawScope.drawPage(
    bitmapWidth: Int,
    bitmapHeight: Int,
    shot: PageShot,
    t: StageTransform,
    crossinline draw: DrawScope.() -> Unit,
) {
    val sx = (t.scale * shot.pageWidth / bitmapWidth).toFloat()
    val sy = (t.scale * shot.pageHeight / bitmapHeight).toFloat()
    withTransform({
        translate(t.translateX.toFloat(), t.translateY.toFloat())
        scale(sx, sy, pivot = Offset.Zero)
    }) { draw() }
}

private fun x(
    px: Double,
    t: StageTransform,
) = (px * t.scale + t.translateX).toFloat()

private fun y(
    py: Double,
    t: StageTransform,
) = (py * t.scale + t.translateY).toFloat()

/**
 * The caption card, only when [CaptionFrame.needed]: placed by the ViewModel from the block the first card takes
 * (two lines at most, half its font size of padding above and below), with a shade when it crosses the panel.
 */
private fun DrawScope.drawCaption(
    caption: CaptionFrame,
    beat: Int,
    viewModel: PlayerViewModel,
    measurer: TextMeasurer,
    placements: HashMap<String, CaptionPlacement>,
) {
    if (!caption.needed || caption.cards.isEmpty()) return
    val w = size.width
    val h = size.height
    val font = h * (if (caption.small) SMALL_CAPTION else CAPTION)
    val pad = (PlaybackPlan.CAPTION_PAD * font).toFloat()
    val style = captionStyle(caption, font)
    val maxWidth = (w * CAPTION_WIDTH).toInt()
    val placement =
        placements.getOrPut("$beat:$w:$h") {
            val first = measure(measurer, caption.cards.first(), style, maxWidth)
            viewModel.captionPlacement(
                beat,
                w.toDouble(),
                h.toDouble(),
                (first.size.height + 2 * pad).toDouble(),
                (first.size.width + 2 * pad).toDouble(),
            )
        }
    if (!placement.visible || caption.text.isEmpty()) return
    val layout = measure(measurer, caption.text, style, maxWidth)
    val top = (placement.top * h).toFloat()
    val left = (w - layout.size.width) / 2
    if (placement.overArt) {
        drawRoundRect(
            CAPTION_SHADE,
            topLeft = Offset(left - pad, top),
            size = Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad),
            cornerRadius = CornerRadius(pad),
        )
    }
    drawText(layout, topLeft = Offset(left, top + pad))
}

private fun measure(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidth: Int,
): TextLayoutResult =
    measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = maxWidth))

private fun DrawScope.captionStyle(
    caption: CaptionFrame,
    fontPx: Double,
): TextStyle =
    TextStyle(
        color = Brand.Paper,
        fontSize = (fontPx / fontScale / density).sp,
        lineHeight = (fontPx * 1.25 / fontScale / density).sp,
        fontWeight = if (caption.tone == "shout") FontWeight.Bold else FontWeight.Medium,
        fontStyle = if (caption.tone == "whisper" || caption.tone == "thought") FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Center,
        shadow = Shadow(Color.Black, Offset(0f, 1f), 6f),
    )

@Composable
private fun Controls(
    state: PlayerUiState,
    send: (PlayerIntent) -> Unit,
    onClose: () -> Unit,
    onLanguages: () -> Unit,
    onTouch: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // Top: close, page, sound.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(I18n.text("common.close"), onClose) { CloseGlyph() }
            Spacer(Modifier.width(12.dp))
            Text(state.pageLabel, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper), modifier = Modifier.weight(1f))
            if (state.offline) {
                Text(
                    I18n.text("common.offlineShort"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            // Reader SPEC §11: dub or captions, one tap away while it plays.
            Pill(state.languageLabel, selected = state.language.isNotEmpty()) {
                onLanguages()
                onTouch()
            }
            Spacer(Modifier.width(8.dp))
            Pill(state.soundLabel, selected = !state.muted && !state.silent) {
                if (!state.silent) send(PlayerIntent.ToggleMute)
                onTouch()
            }
        }
        // Middle: previous beat, play / pause, next beat.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(I18n.text("episode.previousBubble"), {
                send(PlayerIntent.PreviousBeat)
                onTouch()
            }, enabled = state.canGoBack) { SkipGlyph(forward = false) }
            RoundButton(state.playLabel, {
                send(PlayerIntent.TogglePlay)
                onTouch()
            }, large = true) { if (state.playing) PauseGlyph() else PlayGlyph() }
            RoundButton(I18n.text("episode.nextBubble"), {
                send(PlayerIntent.NextBeat)
                onTouch()
            }, enabled = state.canGoForward) { SkipGlyph(forward = true) }
        }
        // Bottom: scrubber, time, speeds, silent hint.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var scrubbing by remember { mutableStateOf<Float?>(null) }
            val duration = state.durationMs.toFloat().coerceAtLeast(1f)
            Slider(
                value = scrubbing ?: state.positionMs.toFloat().coerceIn(0f, duration),
                onValueChange = {
                    scrubbing = it
                    onTouch()
                },
                onValueChangeFinished = {
                    scrubbing?.let { send(PlayerIntent.SeekTo(it.toDouble())) }
                    scrubbing = null
                },
                valueRange = 0f..duration,
                colors =
                    SliderDefaults.colors(
                        thumbColor = Brand.Red,
                        activeTrackColor = Brand.Red,
                        inactiveTrackColor = Brand.Line,
                    ),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.timeLabel, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper), modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.speeds.forEach { option ->
                        Pill(option.label, option.selected) {
                            send(PlayerIntent.SetSpeed(option.value))
                            onTouch()
                        }
                    }
                }
            }
            state.silentHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EndCard(
    state: PlayerUiState,
    send: (PlayerIntent) -> Unit,
    onLanguages: () -> Unit,
    onNextChapter: (String) -> Unit,
) {
    Centered {
        Column(
            Modifier
                .padding(32.dp)
                .background(Brand.Ink2, RoundedCornerShape(12.dp))
                .border(1.dp, Brand.Line, RoundedCornerShape(12.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.endTitle, style = MaterialTheme.typography.titleMedium)
            PrimaryButton(state.replayLabel, { send(PlayerIntent.Replay) })
            val next = state.nextChapterId
            state.nextLabel?.let { label -> if (next != null) GhostButton(label, { onNextChapter(next) }) }
            GhostButton(I18n.text("player.lang.title"), onLanguages)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall.copy(color = if (selected) Brand.Ink else Brand.Paper),
        modifier =
            Modifier
                .background(if (selected) Brand.Paper else Color.Black.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
                .border(1.dp, if (selected) Brand.Paper else Brand.Line, RoundedCornerShape(999.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun RoundButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    large: Boolean = false,
    glyph: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(if (large) 72.dp else 48.dp)
            .background(if (large) Brand.Red else Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(if (large) 2.dp else 1.dp, if (large) Brand.Ink else Brand.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (large) 26.dp else 18.dp)) { glyph() }
        if (!enabled) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f), CircleShape))
    }
}

@Composable
private fun PlayGlyph() =
    Canvas(Modifier.fillMaxSize()) {
        val p =
            Path().apply {
                moveTo(size.width * 0.18f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(size.width * 0.18f, size.height)
                close()
            }
        drawPath(p, Brand.Ink)
    }

@Composable
private fun PauseGlyph() =
    Canvas(Modifier.fillMaxSize()) {
        val bar = size.width * 0.32f
        drawRect(Brand.Ink, Offset(size.width * 0.1f, 0f), Size(bar, size.height))
        drawRect(Brand.Ink, Offset(size.width * 0.9f - bar, 0f), Size(bar, size.height))
    }

@Composable
private fun SkipGlyph(forward: Boolean) =
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val p =
            Path().apply {
                if (forward) {
                    moveTo(0f, 0f)
                    lineTo(w * 0.78f, h / 2)
                    lineTo(0f, h)
                } else {
                    moveTo(w, 0f)
                    lineTo(w * 0.22f, h / 2)
                    lineTo(w, h)
                }
                close()
            }
        drawPath(p, Brand.Paper)
        drawRect(Brand.Paper, Offset(if (forward) w * 0.8f else 0f, 0f), Size(w * 0.2f, h))
    }

@Composable
private fun CloseGlyph() =
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        drawLine(Brand.Paper, Offset(0f, 0f), Offset(size.width, size.height), stroke)
        drawLine(Brand.Paper, Offset(size.width, 0f), Offset(0f, size.height), stroke)
    }

private const val STAGE_ASPECT = 9f / 16f
private const val CONTROLS_MS = 3_000L
private const val PAUSED_FRAME_MS = 80L
private const val CAPTION = 0.028
private const val SMALL_CAPTION = 0.024
private const val CAPTION_WIDTH = 0.86f
private val BACKDROP_SHADE = Color.Black.copy(alpha = 0.55f)
private val CAPTION_SHADE = Color.Black.copy(alpha = 0.6f)
private val BUBBLE_OUTLINE = Brand.Yellow.copy(alpha = 0.55f)
