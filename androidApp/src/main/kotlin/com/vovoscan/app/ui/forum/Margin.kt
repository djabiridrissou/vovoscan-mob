package com.vovoscan.app.ui.forum

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.common.ToggleChip
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.forum.domain.PostKind
import com.vovoscan.feature.forum.presentation.MarginIntent
import com.vovoscan.feature.forum.presentation.MarginTab
import com.vovoscan.feature.forum.presentation.MarginUiState
import com.vovoscan.feature.forum.presentation.MarkTool
import com.vovoscan.feature.forum.presentation.PinShape
import com.vovoscan.feature.forum.presentation.PinUi
import com.vovoscan.feature.forum.presentation.PostUi
import com.vovoscan.feature.forum.presentation.VerdictUi
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The margin (余白) as the reader's pages see it: the state and the intents. Provided only while the margin is visible
 * (the `forum` flag, a chapter of the server); the pins show while it is open.
 */
class PageMargin(
    val state: MarginUiState,
    val send: (MarginIntent) -> Unit,
)

val LocalPageMargin = staticCompositionLocalOf<PageMargin?> { null }

/** "余白 Margin" in the reader's bar, with the chapter's count; yellow while open. */
@Composable
fun MarginButton(
    state: MarginUiState,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val label = listOfNotNull(state.switchLabel, state.countLabel).joinToString(", ")
    Row(
        Modifier
            .padding(start = 12.dp)
            .background(if (state.open) Brand.Yellow else Brand.Ink2, MaterialTheme.shapes.extraLarge)
            .border(if (state.open) 0.dp else 1.dp, Brand.Line, MaterialTheme.shapes.extraLarge)
            .clickable(onClickLabel = label) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }.semantics {
                contentDescription = label
                selected = state.open
            }.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (state.open) Brand.Ink else Brand.Paper
        Text(state.switchLabel, maxLines = 1, style = MaterialTheme.typography.labelLarge.copy(color = ink))
        state.countLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(Brand.Yellow, MaterialTheme.shapes.extraLarge).padding(horizontal = 5.dp),
            )
        }
    }
}

/**
 * Over one page, in a box of the page's own shape: its pins (sticker glyph on yellow, "？" on a spoiler, frames and
 * strokes in red) and, while a tool is chosen, the surface that takes the mark (a tap, a frame dragged, a stroke drawn)
 * before the pager or the strip can scroll, in fractions of the page.
 */
@Composable
fun MarginPinsOverlay(
    pageIndex: Int,
    margin: PageMargin,
    modifier: Modifier = Modifier,
) {
    val state = margin.state
    val pins = state.pinsOn(pageIndex)
    val draft = state.draftOn(pageIndex)
    if (!state.placing && pins.isEmpty() && draft == null) return
    val haptics = LocalHapticFeedback.current
    val send by rememberUpdatedState(margin.send)
    val tool = state.composer.tool
    // The frame or stroke under the finger, before it is let go (pixels of this box).
    var start by remember { mutableStateOf<Offset?>(null) }
    var current by remember { mutableStateOf<Offset?>(null) }
    val stroke = remember { mutableStateListOf<Offset>() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }

        fun fx(o: Offset) = (o.x / w).toDouble().coerceIn(0.0, 1.0)

        fun fy(o: Offset) = (o.y / h).toDouble().coerceIn(0.0, 1.0)
        if (state.placing) {
            Box(
                Modifier.fillMaxSize().pointerInput(tool, pageIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        start = down.position
                        current = down.position
                        stroke.clear()
                        stroke += down.position
                        var last = down.position
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            change.consume()
                            if (!change.pressed) break
                            current = last
                            if (tool == MarkTool.Draw) stroke += last
                        }
                        val a = down.position
                        when (tool) {
                            MarkTool.Point -> send(MarginIntent.PlacePoint(pageIndex, fx(last), fy(last)))
                            MarkTool.Frame -> send(MarginIntent.PlaceFrame(pageIndex, fx(a), fy(a), fx(last), fy(last)))
                            MarkTool.Draw -> send(MarginIntent.PlaceStroke(pageIndex, stroke.flatMap { listOf(fx(it), fy(it)) }))
                            MarkTool.None -> Unit
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        start = null
                        current = null
                        stroke.clear()
                    }
                },
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            // A tool is armed: a faint red hatch and a dashed outline say the page takes the mark (never a dimming).
            if (state.placing) {
                val step = 14.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(Brand.Red.copy(alpha = 0.05f), Offset(x, size.height), Offset(x + size.height, 0f), 2.dp.toPx())
                    x += step
                }
                val inset = 1.dp.toPx()
                drawRect(
                    Brand.Red,
                    Offset(inset, inset),
                    Size(size.width - 2 * inset, size.height - 2 * inset),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 18f))),
                )
            }
            (pins + listOfNotNull(draft)).forEach { pin ->
                when (pin.shape) {
                    PinShape.Frame ->
                        drawRect(
                            Brand.Red,
                            Offset((pin.x * size.width).toFloat(), (pin.y * size.height).toFloat()),
                            Size((pin.w * size.width).toFloat(), (pin.h * size.height).toFloat()),
                            style = Stroke(2.dp.toPx()),
                        )
                    PinShape.Stroke -> drawPath(strokePath(pin.path, size), Brand.Red, style = penStroke(3.dp.toPx()))
                    PinShape.Point -> Unit
                }
            }
            val a = start
            val b = current
            if (a != null && b != null && tool == MarkTool.Frame) {
                drawRect(
                    Brand.Red,
                    Offset(min(a.x, b.x), min(a.y, b.y)),
                    Size(abs(b.x - a.x), abs(b.y - a.y)),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))),
                )
            }
            if (stroke.size > 1) {
                val path = Path().apply { stroke.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, Brand.Red, style = penStroke(3.dp.toPx()))
            }
        }
        pins.forEach { pin -> PinLabel(pin, w, h, draft = false) { send(MarginIntent.PinTapped(pin.postId)) } }
        draft?.let { PinLabel(it, w, h, draft = true) {} }
    }
}

private fun strokePath(
    xy: List<Double>,
    size: Size,
): Path =
    Path().apply {
        for (i in 0 until xy.size - 1 step 2) {
            val x = (xy[i] * size.width).toFloat()
            val y = (xy[i + 1] * size.height).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

private fun penStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** The pin's sticker, centred on its point (44 dp to tap, whatever the label's size). */
@Composable
private fun PinLabel(
    pin: PinUi,
    w: Float,
    h: Float,
    draft: Boolean,
    onTap: () -> Unit,
) {
    val half = with(LocalDensity.current) { 22.dp.roundToPx() }
    Box(
        Modifier
            .offset { IntOffset((pin.x * w).roundToInt() - half, (pin.y * h).roundToInt() - half) }
            .size(44.dp)
            .clickable(enabled = !draft, onClickLabel = pin.a11y, onClick = onTap)
            .semantics { contentDescription = pin.a11y },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            pin.label,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
            modifier =
                Modifier
                    .rotate(-4f)
                    .background(if (pin.masked) Brand.Muted else Brand.Yellow)
                    .border(1.5.dp, Brand.Ink)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * The margin's panel, docked at the bottom of the reader and not modal: the pages above stay readable and take a mark
 * while a tool is chosen. Three tabs (this chapter, theories, salon), the list, the composer; a post's replies replace
 * the list; the report reasons and the toast over it. Back closes the replies, then the panel.
 */
@Composable
fun MarginPanel(
    state: MarginUiState,
    send: (MarginIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tall by remember { mutableStateOf(false) }
    BackHandler { send(if (state.thread != null) MarginIntent.CloseThread else MarginIntent.Close) }
    Column(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(if (tall) 0.9f else 0.45f)
            .background(Brand.Ink2, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // The handle: a tap makes the panel taller or shorter.
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = I18n.text("forum.resize")) { tall = !tall }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).background(Brand.Line, RoundedCornerShape(2.dp)))
        }
        val thread = state.thread
        if (thread != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "←",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(onClickLabel = I18n.text("common.back")) { send(MarginIntent.CloseThread) }.padding(8.dp),
                )
                Text(state.repliesTitle, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MarginPost(thread.post, send, inThread = true) }
                items(thread.replies, key = { it.id }) { MarginPost(it, send, inThread = true, Modifier.padding(start = 20.dp)) }
            }
            if (thread.post.canReply) {
                Composer(
                    text = thread.draft,
                    placeholder = state.replyPlaceholder,
                    counter = thread.counter,
                    sendLabel = I18n.text("forum.reply"),
                    canSend = thread.canSend,
                    onType = { send(MarginIntent.TypeReply(it)) },
                    onSend = { send(MarginIntent.SendReply) },
                )
            }
        } else {
            Header(state, send)
            val posts =
                when (state.tab) {
                    MarginTab.Chapter -> state.notes
                    MarginTab.Theories -> state.theories
                    MarginTab.Salon -> state.salon?.posts.orEmpty()
                }
            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.offlineLine?.let { item { OfflineNotice(it) } }
                state.error?.let { item { ErrorText(it) } }
                state.salon?.takeIf { state.tab == MarginTab.Salon }?.let { salon ->
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            salon.weeks.forEach { week ->
                                ToggleChip(week.label, week.key == salon.weekKey) { send(MarginIntent.SelectWeek(week.key)) }
                            }
                        }
                    }
                }
                state.empty?.let {
                    item {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted),
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                items(posts, key = { it.id }) { MarginPost(it, send) }
            }
            if (state.canWrite) NoteComposer(state, send)
        }
        state.reporting?.let { ReportReasons(state, send) }
        state.toast?.let { toast ->
            LaunchedEffect(toast) {
                delay(3000)
                send(MarginIntent.ToastShown)
            }
            Text(
                toast,
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
                modifier =
                    Modifier
                        .padding(16.dp)
                        .background(Brand.Yellow, RoundedCornerShape(10.dp))
                        .padding(12.dp),
            )
        }
    }
}

@Composable
private fun Header(
    state: MarginUiState,
    send: (MarginIntent) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tabs.forEach { tab ->
                ToggleChip(tab.label, state.tab == tab.tab) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    send(MarginIntent.SelectTab(tab.tab))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "✕",
                style = MaterialTheme.typography.titleMedium.copy(color = Brand.Muted),
                modifier =
                    Modifier
                        .clickable(onClickLabel = I18n.text("common.close")) { send(MarginIntent.Close) }
                        .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                        .padding(10.dp),
            )
        }
        if (state.tab != MarginTab.Salon) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                state.orders.forEach { order ->
                    Text(
                        order.label,
                        style =
                            MaterialTheme.typography.labelLarge.copy(
                                color = if (state.order == order.order) Brand.Paper else Brand.Muted,
                            ),
                        modifier =
                            Modifier
                                .clickable { send(MarginIntent.SetOrder(order.order)) }
                                .semantics { selected = state.order == order.order }
                                .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** A note's tools (point, frame, draw) and stickers, then the words, the spoiler switch and Send. */
@Composable
private fun NoteComposer(
    state: MarginUiState,
    send: (MarginIntent) -> Unit,
) {
    val c = state.composer
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Brand.Line)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (c.kind == PostKind.Note) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                state.tools.forEach { tool -> ToggleChip(tool.label, c.tool == tool.tool) { send(MarginIntent.ChooseTool(tool.tool)) } }
                if (c.anchor != null) {
                    Text(
                        state.clearMarkLabel,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                        modifier = Modifier.clickable { send(MarginIntent.ClearMark) }.padding(6.dp),
                    )
                }
            }
            c.toolHint?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Cyan)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.stickers.forEach { sticker ->
                    val on = c.sticker == sticker.key
                    Text(
                        sticker.glyph,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
                        modifier =
                            Modifier
                                .background(if (on) Brand.Yellow else Brand.Paper.copy(alpha = 0.85f))
                                .clickable(onClickLabel = "${state.stickerLabel} ${sticker.glyph}") {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    send(MarginIntent.PickSticker(if (on) null else sticker.key))
                                }.semantics { selected = on }
                                .heightIn(min = 36.dp)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Composer(
            text = c.body,
            placeholder = c.placeholder,
            counter = c.counter,
            sendLabel = state.sendLabel,
            canSend = c.canSend,
            onType = { send(MarginIntent.Type(it)) },
            onSend = { send(MarginIntent.Send) },
            padded = false,
            spoiler = c.spoiler to state.spoilerLabel,
            onSpoiler = { send(MarginIntent.SetSpoiler(it)) },
        )
        c.problem?.let { ErrorText(it) }
    }
}

/** A text field with its counter and button: a note, a theory, a salon message or a reply. */
@Composable
private fun Composer(
    text: String,
    placeholder: String,
    counter: String,
    sendLabel: String,
    canSend: Boolean,
    onType: (String) -> Unit,
    onSend: () -> Unit,
    padded: Boolean = true,
    spoiler: Pair<Boolean, String>? = null,
    onSpoiler: (Boolean) -> Unit = {},
) {
    Column(Modifier.then(if (padded) Modifier.padding(16.dp) else Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = text,
            onValueChange = onType,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brand.Paper),
            cursorBrush = SolidColor(Brand.Red),
            maxLines = 5,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brand.Ink, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.Muted))
                    field()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spoiler?.let { (on, label) ->
                Switch(
                    on,
                    onSpoiler,
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                    modifier = Modifier.semantics { contentDescription = label },
                )
                Text(label, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
            }
            Text(counter, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted), modifier = Modifier.weight(1f))
            PrimaryButton(sendLabel, onSend, Modifier.width(132.dp), enabled = canSend)
        }
    }
}

@Composable
private fun ReportReasons(
    state: MarginUiState,
    send: (MarginIntent) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(state.reportTitle, style = MaterialTheme.typography.titleMedium)
        state.reportReasons.forEach { reason -> GhostButton(reason.label, { send(MarginIntent.SendReport(reason.reason)) }) }
        GhostButton(I18n.text("common.cancel"), { send(MarginIntent.CancelReport) })
    }
}

/**
 * One post: author, badge, age, the sticker and words (or the spoiler line, tap to reveal), the eight reactions, the
 * verdict of a theory, replies, translate, and its menu (edit, delete, report, mute).
 */
@Composable
private fun MarginPost(
    post: PostUi,
    send: (MarginIntent) -> Unit,
    inThread: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .fillMaxWidth()
            .background(Brand.Ink, RoundedCornerShape(12.dp))
            .border(if (post.highlighted) 2.dp else 1.dp, if (post.highlighted) Brand.Yellow else Brand.Line, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(post.author, style = MaterialTheme.typography.labelLarge)
            post.badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink),
                    modifier = Modifier.background(Brand.Red).padding(horizontal = 5.dp),
                )
            }
            Text(
                listOfNotNull(post.age, post.place).filter { it.isNotEmpty() }.joinToString(" ✦ "),
                style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                modifier = Modifier.weight(1f),
            )
            PostMenu(post, send)
        }
        val masked = post.maskedLine
        if (masked != null) {
            Row(
                Modifier.clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    send(MarginIntent.Reveal(post.id))
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "？",
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
                    modifier = Modifier.background(Brand.Muted).padding(horizontal = 6.dp),
                )
                Text(masked, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            }
        } else {
            post.sticker?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium.copy(color = Brand.Ink),
                    modifier = Modifier.rotate(-3f).background(Brand.Yellow).padding(horizontal = 8.dp),
                )
            }
            post.body?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            post.translation?.let { Text(it, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.Cyan)) }
        }
        post.note?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
        post.verdict?.let { Verdict(post.id, it, send) }
        if (masked == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                post.reactions.forEach { reaction ->
                    Text(
                        listOfNotNull(reaction.glyph, reaction.count?.toString()).joinToString(" "),
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                color = if (reaction.mine) Brand.Ink else Brand.Paper,
                                fontWeight = FontWeight.Bold,
                            ),
                        modifier =
                            Modifier
                                .background(if (reaction.mine) Brand.Yellow else Brand.Ink2, MaterialTheme.shapes.extraLarge)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    send(MarginIntent.React(post.id, reaction.key))
                                }.semantics { selected = reaction.mine }
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val link = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted)
            val replies = post.replies
            if (!inThread && replies != null) {
                Text(replies, style = link, modifier = Modifier.clickable { send(MarginIntent.OpenThread(post.id)) })
            } else if (!inThread && post.canReply) {
                Text(I18n.text("forum.reply"), style = link, modifier = Modifier.clickable { send(MarginIntent.OpenThread(post.id)) })
            }
            if (post.translating) {
                Text(I18n.text("forum.translating"), style = link.copy(color = Brand.Cyan))
            } else if (post.canTranslate) {
                Text(I18n.text("forum.translate"), style = link, modifier = Modifier.clickable { send(MarginIntent.Translate(post.id)) })
            }
        }
    }
}

@Composable
private fun Verdict(
    postId: String,
    verdict: VerdictUi,
    send: (MarginIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(verdict.state, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Yellow))
        verdict.counts?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted)) }
        if (verdict.canVote) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(I18n.text("forum.happened"), verdict.mine == true) { send(MarginIntent.Vote(postId, true)) }
                ToggleChip(I18n.text("forum.didntHappen"), verdict.mine == false) { send(MarginIntent.Vote(postId, false)) }
            }
        }
    }
}

@Composable
private fun PostMenu(
    post: PostUi,
    send: (MarginIntent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "⋯",
            style = MaterialTheme.typography.titleMedium.copy(color = Brand.Muted),
            modifier =
                Modifier
                    .clickable(onClickLabel = I18n.text("forum.more")) { open = true }
                    .sizeIn(minWidth = 44.dp, minHeight = 32.dp)
                    .padding(horizontal = 12.dp),
        )
        DropdownMenu(open, { open = false }, containerColor = Brand.Ink2) {
            val entries =
                buildList {
                    if (post.editable) add(I18n.text("forum.edit") to MarginIntent.StartEdit(post.id))
                    if (post.mine) {
                        add(I18n.text("common.delete") to MarginIntent.Delete(post.id))
                    } else {
                        add(I18n.text("forum.report") to MarginIntent.Report(post.id))
                        add(I18n.text("forum.mute") to MarginIntent.Mute(post.authorId))
                    }
                }
            entries.forEach { (label, intent) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        send(intent)
                    },
                )
            }
        }
    }
}

/** Under the last page: "ここまで読んだ Read this far", how many readers got here, the best notes, and the way in. */
@Composable
fun MarginEndCard(margin: PageMargin) {
    val state = margin.state
    val end = state.endCard ?: return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(end.title, style = MaterialTheme.typography.titleMedium)
        Text(end.readers, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted))
        end.best.forEach { post ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                post.sticker?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium.copy(color = Brand.Ink),
                        modifier = Modifier.background(Brand.Yellow).padding(horizontal = 4.dp),
                    )
                }
                Text(post.body.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tabs.forEach { tab ->
                GhostButton(
                    if (tab.tab == MarginTab.Chapter) state.leaveMarkLabel else tab.label,
                    {
                        if (!state.open) margin.send(MarginIntent.Toggle)
                        margin.send(MarginIntent.SelectTab(tab.tab))
                    },
                    Modifier.weight(1f),
                )
            }
        }
    }
}
