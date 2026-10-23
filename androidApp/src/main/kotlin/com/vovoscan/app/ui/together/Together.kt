package com.vovoscan.app.ui.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vovoscan.app.BuildConfig
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.together.presentation.BurstUi
import com.vovoscan.feature.together.presentation.CircleUi
import com.vovoscan.feature.together.presentation.InviteIntent
import com.vovoscan.feature.together.presentation.JoinedChapter
import com.vovoscan.feature.together.presentation.StampUi
import com.vovoscan.feature.together.presentation.TicketUi
import com.vovoscan.feature.together.presentation.TogetherIntent
import com.vovoscan.feature.together.presentation.TogetherInviteViewModel
import com.vovoscan.feature.together.presentation.TogetherSheet
import com.vovoscan.feature.together.presentation.TogetherUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/*
 * The reading circle next to a reader: `TogetherViewModel` (shared Kotlin) decides everything; these
 * Composables draw the button, the sheet, the dock, the rail, the stickers, the lines and the toasts, the same as iOS
 * (`TogetherViews.swift`).
 */

/** Each reader's ink, by slot: the same eight as the web and iOS, the brand's accents first. */
object CircleInk {
    private val slots =
        listOf(
            Color(0xFFFF2D55),
            Color(0xFF2DE2E6),
            Color(0xFFFFD60A),
            Color(0xFF4ADE80),
            Color(0xFFC084FC),
            Color(0xFFFB923C),
            Color(0xFF60A5FA),
            Color(0xFFF472B6),
        )

    fun of(slot: Int): Color = slots[Math.floorMod(slot, slots.size)]
}

/** "Together" in the reader's top bar: the sheet (read together, join with a code), or the ticket once in a circle. */
@Composable
fun TogetherButton(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val inCircle = state.circle != null
    Row(
        Modifier
            .padding(start = 12.dp)
            .background(if (inCircle) Brand.Cyan else Brand.Ink2, MaterialTheme.shapes.extraLarge)
            .border(if (inCircle) 0.dp else 1.dp, Brand.Line, MaterialTheme.shapes.extraLarge)
            .clickable(onClickLabel = state.buttonLabel) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                send(TogetherIntent.OpenSheet)
            }.semantics { contentDescription = state.buttonLabel }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val ink = if (inCircle) Brand.Ink else Brand.Paper
        Text(state.buttonLabel, style = MaterialTheme.typography.labelLarge.copy(color = ink), maxLines = 1, softWrap = false)
        state.circle?.stamps?.size?.takeIf { it > 0 }?.let {
            Text("$it", style = MaterialTheme.typography.labelSmall.copy(color = ink, fontWeight = FontWeight.Bold))
        }
    }
}

/** The sheet: the two ways in, the code with its preview, or the admission ticket. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherSheetView(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { send(TogetherIntent.CloseSheet) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
        contentColor = Brand.Paper,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper))
            when (state.sheet) {
                TogetherSheet.Ticket -> state.circle?.let { Ticket(it.ticket, state, send) }
                TogetherSheet.Join -> Join(state, send)
                else -> {
                    Text(state.startHelp, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
                    PrimaryButton(state.startLabel, { send(TogetherIntent.StartCircle) }, enabled = !state.busy)
                    GhostButton(state.joinLabel, { send(TogetherIntent.ShowJoin) })
                }
            }
            state.error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun Join(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    BasicTextField(
        value = state.codeInput,
        onValueChange = { send(TogetherIntent.CodeChanged(it)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = Brand.Paper, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Brand.Red),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Brand.InputBg, RoundedCornerShape(8.dp))
                .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
                .padding(14.dp)
                .semantics { contentDescription = state.joinLabel },
        decorationBox = { field ->
            if (state.codeInput.isEmpty()) {
                Text(
                    state.codePlaceholder,
                    style = MaterialTheme.typography.headlineMedium.copy(color = Brand.Muted, fontFamily = FontFamily.Monospace),
                )
            }
            field()
        },
    )
    state.preview?.let { preview ->
        Column(
            Modifier.fillMaxWidth().background(Brand.Ink, RoundedCornerShape(10.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(preview.heading, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Cyan, fontWeight = FontWeight.Bold))
            Text(preview.chapterLine, style = MaterialTheme.typography.bodyLarge.copy(color = Brand.Paper, fontWeight = FontWeight.Bold))
            Text(preview.hostLine, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            Text(preview.presentLine, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            preview.blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Danger)) }
        }
    }
    PrimaryButton(state.joinAction, { send(TogetherIntent.Join) }, enabled = state.canJoin)
}

/** The admission ticket: the code in large type in a dashed frame, the link to share or copy, and the host's door. */
@Composable
private fun Ticket(
    ticket: TicketUi,
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    val context = LocalContext.current
    val link = BuildConfig.WEB_BASE_URL.trimEnd('/') + "/" + ticket.webPath.trimStart('/')
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().offset(5.dp, 5.dp).background(Brand.Red, RoundedCornerShape(10.dp)))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Paper, RoundedCornerShape(10.dp))
                .padding(6.dp)
                .dashedBorder()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(state.ticketTitle, style = MaterialTheme.typography.titleSmall.copy(color = Brand.Ink), modifier = Modifier.weight(1f))
                Text(ticket.admission, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontWeight = FontWeight.Bold))
            }
            Text(
                ticket.code,
                maxLines = 1,
                style = TextStyleCode.copy(color = Brand.Ink),
                modifier = Modifier.semantics { contentDescription = ticket.code },
            )
            Text(
                ticket.chapterLine,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
            )
            Text(ticket.closesLine, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Ink.copy(alpha = 0.7f)))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(state.shareLabel, {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            context.startActivity(Intent.createChooser(share, state.shareLabel))
        }, Modifier.weight(1f))
        GhostButton(if (copied) I18n.text("together.copied") else state.copyLabel, {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(state.ticketTitle, link))
            copied = true
        }, Modifier.weight(1f))
    }
    if (ticket.isHost) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(state.lockLabel, { send(TogetherIntent.ToggleLock) }, Modifier.weight(1f))
            GhostButton(state.renewLabel, { send(TogetherIntent.RenewCode) }, Modifier.weight(1f))
        }
        RedLink(state.endLabel) { send(TogetherIntent.EndCircle) }
    } else {
        RedLink(state.leaveLabel) { send(TogetherIntent.Leave) }
    }
}

private val TextStyleCode =
    TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 38.sp)

@Composable
private fun RedLink(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Red),
        modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

/** The ticket's dashed inner frame. */
private fun Modifier.dashedBorder(): Modifier =
    drawBehind {
        drawRoundRect(
            color = Brand.Ink,
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
    }

/**
 * The dock at the bottom of the reader: the stamps (tap one: the host's menu about that reader), follow / let go,
 * Gather for the host or Wait for a reader, the log, the ticket, the eight stickers and a line.
 */
@Composable
fun TogetherDock(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val circle = state.circle ?: return
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(Modifier.matchParentSize().offset(4.dp, 4.dp).background(Brand.Ink, RoundedCornerShape(12.dp)))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brand.Paper, RoundedCornerShape(12.dp))
                .border(2.dp, Brand.Ink, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .semantics { contentDescription = state.dockLabel },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.logOpen) Log(circle)
            state.balloons.forEach { balloon ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).background(CircleInk.of(balloon.slot), CircleShape))
                    Column(
                        Modifier
                            .background(Color.White, RoundedCornerShape(14.dp))
                            .border(1.5.dp, Brand.Ink, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(balloon.name, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink.copy(alpha = 0.7f)))
                        Text(balloon.text, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink))
                    }
                }
            }
            Stamps(circle, state, send)
            if (!state.collapsed) {
                Controls(circle, state, send)
                Stickers(state, send)
                LineField(state, send)
            }
        }
    }
}

@Composable
private fun Stamps(
    circle: CircleUi,
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            circle.stamps.forEach { stamp ->
                Box {
                    Hanko(stamp, Modifier.clickable(onClickLabel = stamp.a11y) { send(TogetherIntent.SelectStamp(stamp.userId)) })
                    // The host's choices about that reader, anchored on their stamp.
                    val menu = state.memberMenu?.takeIf { it.userId == stamp.userId }
                    DropdownMenu(expanded = menu != null, onDismissRequest = { send(TogetherIntent.CloseMenu) }) {
                        menu?.let {
                            Text(
                                it.name,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            DropdownMenuItem(text = { Text(it.handOverLabel) }, onClick = { send(TogetherIntent.HandOver(it.userId)) })
                            DropdownMenuItem(
                                text = { Text(it.removeLabel, color = Brand.Red) },
                                onClick = { send(TogetherIntent.Remove(it.userId)) },
                            )
                        }
                    }
                }
            }
        }
        val fold = I18n.text(if (state.collapsed) "together.expand" else "together.collapse")
        Text(
            if (state.collapsed) "▲" else "▼",
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
            modifier =
                Modifier
                    .clickable(onClickLabel = fold) { send(TogetherIntent.ToggleCollapsed) }
                    .semantics { contentDescription = fold }
                    .padding(14.dp),
        )
    }
}

@Composable
private fun Controls(
    circle: CircleUi,
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        circle.followLabel?.let { label ->
            Text(
                label,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
                modifier =
                    Modifier
                        .background(if (circle.following) Brand.Cyan else Brand.Yellow, CircleShape)
                        .clickable { if (!circle.following) send(TogetherIntent.Follow) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        circle.hostSummary?.let {
            Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Ink.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.weight(1f))
        if (circle.canGather) {
            DockText(state.gatherLabel, Brand.Red) { send(TogetherIntent.Gather) }
        } else if (!circle.isHost) {
            DockText(state.waitLabel, Brand.Ink) { send(TogetherIntent.Wait) }
        }
        DockText("☰", Brand.Ink, state.logLabel) { send(TogetherIntent.ToggleLog) }
        DockIcon(AppIcons.Ticket, state.ticketTitle) { send(TogetherIntent.ShowTicket) }
    }
}

/** A dock control drawn as a line icon, with its [label] for TalkBack. */
@Composable
private fun DockIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = label,
        tint = Brand.Ink,
        modifier =
            Modifier
                .clickable(onClickLabel = label, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .size(20.dp),
    )
}

@Composable
private fun DockText(
    text: String,
    color: Color,
    label: String = text,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(color = color, fontWeight = FontWeight.Bold),
        modifier =
            Modifier
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = label }
                .padding(horizontal = 6.dp, vertical = 12.dp),
    )
}

@Composable
private fun Stickers(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        state.stickers.forEach { sticker ->
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .background(Brand.Yellow, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = sticker.label) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        send(TogetherIntent.SendSticker(sticker.key))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(sticker.glyph, maxLines = 1, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontSize = 11.sp))
            }
        }
    }
}

@Composable
private fun LineField(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
) {
    val submit = { send(TogetherIntent.SendLine) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = state.lineDraft,
            onValueChange = { send(TogetherIntent.LineChanged(it)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink),
            cursorBrush = SolidColor(Brand.Red),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier =
                Modifier
                    .weight(1f)
                    .background(Brand.Paper2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            decorationBox = { field ->
                if (state.lineDraft.isEmpty()) {
                    Text(state.linePlaceholder, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink.copy(alpha = 0.5f)))
                }
                field()
            },
        )
        DockText(state.sendLabel, Brand.Red, onClick = submit)
    }
    state.lineError?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Red)) }
}

@Composable
private fun Log(circle: CircleUi) {
    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        circle.log.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).background(entry.slot?.let(CircleInk::of) ?: Brand.Muted, CircleShape))
                Text(entry.text, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Ink))
            }
        }
    }
}

/** A reader's round hanko stamp: the initial in their ink; the host ringed red with a star; away dashed and faded. */
@Composable
private fun Hanko(
    stamp: StampUi,
    modifier: Modifier = Modifier,
) {
    val ink = CircleInk.of(stamp.slot)
    val ring =
        if (stamp.asking) {
            Brand.Cyan
        } else if (stamp.host) {
            Brand.Red
        } else {
            ink
        }
    Box(modifier.size(44.dp).semantics { contentDescription = stamp.a11y }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(38.dp)
                .alpha(if (stamp.away) 0.45f else 1f)
                .background(Brand.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val width = (if (stamp.host || stamp.asking) 3 else 2).dp.toPx()
                drawCircle(
                    ring,
                    radius = size.minDimension / 2 - width / 2,
                    style =
                        Stroke(
                            width,
                            pathEffect = if (stamp.away) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null,
                        ),
                )
            }
            Text(stamp.initial, style = MaterialTheme.typography.titleMedium.copy(color = ink))
        }
        if (stamp.host) {
            Text(
                "★",
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Ink, fontSize = 9.sp, fontWeight = FontWeight.Black),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(15.dp)
                        .background(Brand.Red, CircleShape)
                        .padding(top = 1.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The rail at the right edge: one dot per reader at their place in the chapter; tap a dot to go there. Only with the
 * bars ([interactive]): while they are hidden a dot's tap only brings them back ([wake]). It never moves the reader
 * (a tap near the edge meant to show the bars sent a host to the top of page 1), and never reaches the page under it
 * (its tap zone scrolled the strip back a page).
 */
@Composable
fun TogetherRail(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    wake: () -> Unit = {},
) {
    val rail = state.circle?.rail.orEmpty()
    if (rail.isEmpty()) return
    val label = I18n.text("together.rail")
    BoxWithConstraints(modifier.width(44.dp).padding(vertical = 80.dp).semantics { contentDescription = label }) {
        val track = (maxHeight - 44.dp).coerceAtLeast(0.dp)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(3.dp)
                .fillMaxHeight()
                .background(Brand.Paper.copy(alpha = 0.18f), CircleShape),
        )
        rail.forEach { dot ->
            val size = if (dot.host) 14.dp else 10.dp
            Box(
                Modifier
                    .offset(y = track * dot.share)
                    .size(44.dp)
                    .clickable { if (interactive) send(TogetherIntent.JumpTo(dot.userId)) else wake() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size)
                        .background(CircleInk.of(dot.slot), CircleShape)
                        .border(2.dp, if (dot.me) Brand.Paper else Brand.Ink, CircleShape),
                )
            }
        }
    }
}

/** Stickers bursting at the reading line (offset by the sender's ink), or at the point given across the page. */
@Composable
fun TogetherBursts(
    state: TogetherUiState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        state.bursts.forEach { burst ->
            val x = if (burst.page >= 0) burst.x else 0.22f + 0.08f * burst.slot
            key(burst.id) {
                BurstGlyph(
                    burst,
                    state.reducedMotion,
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = maxWidth * x.coerceIn(0.08f, 0.92f) - 40.dp, y = maxHeight / 3 - 24.dp),
                )
            }
        }
    }
}

@Composable
private fun BurstGlyph(
    burst: BurstUi,
    reducedMotion: Boolean,
    modifier: Modifier,
) {
    val scale = remember { Animatable(if (reducedMotion) 1f else 0.6f) }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(burst.id) {
        launch { opacity.animateTo(1f, tween(if (reducedMotion) 200 else 0)) }
        if (!reducedMotion) {
            scale.animateTo(1.15f, spring(dampingRatio = 0.5f, stiffness = 1200f))
            scale.animateTo(1f, tween(200))
        }
        delay(if (reducedMotion) 1_200L else 820L)
        opacity.animateTo(0f, tween(400))
    }
    val ink = CircleInk.of(burst.slot)
    Box(modifier.width(80.dp).scale(scale.value).alpha(opacity.value), contentAlignment = Alignment.Center) {
        if (!reducedMotion) {
            repeat(8) { index ->
                Box(Modifier.rotate(index * 45f).size(2.dp, 84.dp)) {
                    Box(Modifier.align(Alignment.TopCenter).size(2.dp, 18.dp).background(ink))
                }
            }
        }
        Text(
            burst.glyph,
            style = MaterialTheme.typography.titleLarge.copy(color = Brand.Ink),
            modifier =
                Modifier
                    .background(Brand.Yellow, RoundedCornerShape(8.dp))
                    .border(3.dp, ink, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        burst.countLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Paper),
                modifier = Modifier.align(Alignment.TopEnd).background(Brand.Red, CircleShape).padding(4.dp),
            )
        }
    }
}

/** One calm line at the top (joined, left, new host, wait, gather, moved on), read out politely by TalkBack. */
@Composable
fun TogetherToast(
    state: TogetherUiState,
    send: (TogetherIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.toast?.let { toast ->
            LaunchedEffect(toast.id) { announce(context, toast.text) }
            Row(
                Modifier
                    .background(Brand.Paper, CircleShape)
                    .border(2.dp, Brand.Ink, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(toast.text, style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink))
                toast.actionLabel?.let { label -> DockText(label, Brand.Red) { send(TogetherIntent.ToastTapped(toast.id)) } }
            }
        }
        state.offline?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(color = Brand.Paper),
                modifier = Modifier.background(Brand.Ink2, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

private fun announce(
    context: Context,
    text: String,
) {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
    if (!manager.isEnabled) return
    @Suppress("DEPRECATION")
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    event.text.add(text)
    manager.sendAccessibilityEvent(event)
}

/** An invite link opened from outside: the circle's preview card, then Join (the reader opens there, following). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TogetherInviteSheet(
    link: String,
    onJoined: (JoinedChapter) -> Unit,
    onClose: () -> Unit,
) {
    val viewModel: TogetherInviteViewModel = koinViewModel(key = "invite:$link") { parametersOf(link) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.joined) {
        val joined = state.joined ?: return@LaunchedEffect
        viewModel.onIntent(InviteIntent.Opened)
        onJoined(joined)
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
        contentColor = Brand.Paper,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper))
            if (state.loading) Text("…", style = MaterialTheme.typography.titleLarge.copy(color = Brand.Cyan))
            state.preview?.let { preview ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brand.Paper, RoundedCornerShape(10.dp))
                        .padding(6.dp)
                        .dashedBorder()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(preview.code, style = TextStyleCode.copy(color = Brand.Ink, fontSize = 28.sp))
                    Text(
                        preview.chapterLine,
                        style = MaterialTheme.typography.bodyLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Bold),
                    )
                    Text(preview.hostLine, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink.copy(alpha = 0.75f)))
                    Text(preview.presentLine, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink.copy(alpha = 0.75f)))
                    preview.blocked?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Red, fontWeight = FontWeight.Bold))
                    }
                }
            }
            state.error?.let { ErrorText(it) }
            PrimaryButton(state.joinLabel, { viewModel.onIntent(InviteIntent.Join) }, enabled = state.canJoin)
            GhostButton(state.closeLabel, onClose)
        }
    }
}

/** Only the app's own invite links (`vovoscan://together/CODE`) reach the circle. */
fun inviteLinkOf(intent: Intent?): String? {
    val data = intent?.data ?: return null
    return data.toString().takeIf { data.scheme == "vovoscan" && data.host == "together" }
}
