package com.vovoscan.app.ui.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.vovoscan.app.BuildConfig
import com.vovoscan.app.ui.common.AppIcons
import com.vovoscan.app.ui.common.ErrorText
import com.vovoscan.app.ui.common.GhostButton
import com.vovoscan.app.ui.common.OfflineNotice
import com.vovoscan.app.ui.common.PrimaryButton
import com.vovoscan.app.ui.sources.BrandAlert
import com.vovoscan.app.ui.theme.Brand
import com.vovoscan.app.ui.together.CircleInk
import com.vovoscan.core.common.i18n.I18n
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.GroupLinks
import com.vovoscan.feature.groups.presentation.ActionsUi
import com.vovoscan.feature.groups.presentation.GameCardUi
import com.vovoscan.feature.groups.presentation.GroupHeader
import com.vovoscan.feature.groups.presentation.GroupIntent
import com.vovoscan.feature.groups.presentation.GroupOpen
import com.vovoscan.feature.groups.presentation.GroupUiState
import com.vovoscan.feature.groups.presentation.GroupViewModel
import com.vovoscan.feature.groups.presentation.JoinAction
import com.vovoscan.feature.groups.presentation.MessageRow
import com.vovoscan.feature.groups.presentation.NewGameForm
import com.vovoscan.feature.groups.presentation.ReactionUi
import com.vovoscan.feature.groups.presentation.RowKind
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** One group: its card, and for members the live chat, its games, sharing and its settings. [ref]: an id or `@handle`. */
@Composable
fun GroupScreen(
    ref: String,
    onBack: () -> Unit,
    onOpenGame: (gameId: String) -> Unit,
    onOpenAdmin: (groupId: String) -> Unit,
    viewModel: GroupViewModel = koinViewModel(key = "group:$ref") { parametersOf(ref) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val send = viewModel::onIntent
    LifecycleEventEffect(Lifecycle.Event.ON_START) { send(GroupIntent.Start) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { send(GroupIntent.Stop) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    state.open?.let { open ->
        LaunchedEffect(open.seq) {
            when (open) {
                is GroupOpen.GameScreen -> onOpenGame(open.gameId)
                is GroupOpen.Back -> onBack()
            }
            send(GroupIntent.Handled(open.seq))
        }
    }
    state.toast?.let { toast ->
        LaunchedEffect(toast) {
            delay(3_000)
            send(GroupIntent.ToastShown)
        }
    }
    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onBack, onShare = { sharing = true }, onOpenAdmin = { state.header?.let { onOpenAdmin(it.id) } }, send = send)
            state.notice?.let {
                Box(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) { OfflineNotice(it) { send(GroupIntent.Refresh) } }
            }
            state.error?.let { Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(it) } }
            if (state.member) {
                Chat(state, send, Modifier.weight(1f))
                Composer(state, send)
            } else {
                Visitor(state, send, Modifier.weight(1f))
            }
        }
        state.toast?.let { Toast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) }
    }
    if (sharing) state.header?.let { ShareSheet(it) { sharing = false } }
    state.actions?.let { ActionsSheet(it, send) }
    state.newGame?.let { NewGameSheet(it, send) }
    if (state.leaveConfirm) {
        BrandAlert(
            title = state.leaveLabel,
            message = state.leaveQuestion,
            confirmLabel = state.leaveLabel,
            onConfirm = { send(GroupIntent.ConfirmLeave) },
            onCancel = { send(GroupIntent.CancelLeave) },
        )
    }
}

@Composable
private fun TopBar(
    state: GroupUiState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenAdmin: () -> Unit,
    send: (GroupIntent) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = I18n.text("common.back") },
            contentAlignment = Alignment.Center,
        ) { Text("←", style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper)) }
        val header = state.header
        if (header != null) {
            GroupEmblem(header.name, header.accent, size = 38)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        header.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (header.verified) VerifiedMark(header.verifiedLabel)
                }
                Text(header.meta, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted), maxLines = 1)
            }
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(onClick = onShare)
                    .semantics { contentDescription = I18n.text("groups.share") },
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.Share, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(22.dp)) }
            if (state.admin) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clickable(onClick = onOpenAdmin)
                        .semantics { contentDescription = I18n.text("groups.admin.title") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Settings, contentDescription = null, tint = Brand.Paper, modifier = Modifier.size(22.dp))
                    state.pendingLabel?.let {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .background(Brand.Yellow, RoundedCornerShape(99.dp)),
                        )
                    }
                }
            }
            if (state.member) {
                Box {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clickable { menu = true }
                            .semantics { contentDescription = I18n.text("groups.admin.settings") },
                        contentAlignment = Alignment.Center,
                    ) { Text("⋯", style = MaterialTheme.typography.titleLarge.copy(color = Brand.Paper)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Brand.Ink2) {
                        DropdownMenuItem(text = { Text(state.notifyLabel) }, onClick = {
                            menu = false
                            send(GroupIntent.ToggleNotify)
                        })
                        DropdownMenuItem(text = { Text(state.leaveLabel, color = Brand.Danger) }, onClick = {
                            menu = false
                            send(GroupIntent.AskLeave)
                        })
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Not a member: the group's card and its way in. */
@Composable
private fun Visitor(
    state: GroupUiState,
    send: (GroupIntent) -> Unit,
    modifier: Modifier,
) {
    val header = state.header ?: return
    val join = state.join
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(12.dp))
        GroupEmblem(header.name, header.accent, size = 88)
        Text(header.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(header.meta, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted), textAlign = TextAlign.Center)
        if (header.about.isNotEmpty()) Text(header.about, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (join != null) {
            if (join.action == JoinAction.Ask) {
                NoteField(join.note, join.notePlaceholder) { send(GroupIntent.Note(it)) }
            }
            when (join.action) {
                JoinAction.Join, JoinAction.Ask -> PrimaryButton(join.label, { send(GroupIntent.JoinAct) }, enabled = !join.working)
                JoinAction.Withdraw -> GhostButton(join.label, { send(GroupIntent.JoinAct) })
                JoinAction.None, JoinAction.Banned ->
                    Text(
                        join.label,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                    )
            }
            join.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun NoteField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brand.InputBg, RoundedCornerShape(8.dp))
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper),
            cursorBrush = SolidColor(Brand.Red),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        )
    }
}

@Composable
private fun Chat(
    state: GroupUiState,
    send: (GroupIntent) -> Unit,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    // Item 0 is "Earlier messages" (or nothing): a message's index is its position + 1.
    state.scroll?.let { scroll ->
        LaunchedEffect(scroll.seq) {
            val index = state.messages.indexOfFirst { it.id == scroll.messageId }
            if (index >= 0) {
                if (scroll.animated) {
                    list.animateScrollToItem(index + 1)
                } else {
                    list.scrollToItem(index + 1)
                }
            }
        }
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "older") {
            if (state.hasOlder && state.messages.isNotEmpty()) {
                Text(
                    I18n.text("groups.chat.older"),
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Cyan),
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = !state.loadingOlder,
                            ) { send(GroupIntent.LoadOlder) }
                            .padding(10.dp),
                )
            } else if (!state.loading && state.messages.isEmpty()) {
                Text(
                    state.emptyChat,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        items(state.messages, key = { it.id }) { row -> MessageItem(row, state.games[row.gameId], send) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    row: MessageRow,
    card: GameCardUi?,
    send: (GroupIntent) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        row.day?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted, letterSpacing = 1.2.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
        when (row.kind) {
            RowKind.System ->
                Text(
                    row.text,
                    style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = row.a11y },
                )
            RowKind.Game -> card?.let { GameCard(it, send) } ?: Text(row.text, style = MaterialTheme.typography.bodySmall)
            else -> {
                val align = if (row.own) Alignment.End else Alignment.Start
                Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
                    row.author?.let {
                        Text(
                            it,
                            style =
                                MaterialTheme.typography.labelMedium.copy(
                                    color = CircleInk.of(row.authorSlot),
                                    fontWeight = FontWeight.Bold,
                                ),
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    val gone = row.kind == RowKind.Gone
                    Column(
                        Modifier
                            .widthIn(max = 300.dp)
                            .background(if (row.own) Brand.Paper else Brand.Ink2, RoundedCornerShape(14.dp))
                            .border(1.dp, if (row.own) Brand.Ink else Brand.Line, RoundedCornerShape(14.dp))
                            .combinedClickable(
                                enabled = !gone,
                                onClick = {},
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    send(GroupIntent.OpenActions(row.id))
                                },
                            ).padding(horizontal = 12.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) { contentDescription = row.a11y },
                    ) {
                        val ink = if (row.own) Brand.Ink else Brand.Paper
                        row.reply?.let {
                            Text(
                                it,
                                maxLines = 2,
                                style = MaterialTheme.typography.bodySmall.copy(color = ink.copy(alpha = 0.7f)),
                                modifier =
                                    Modifier
                                        .padding(
                                            bottom = 4.dp,
                                        ).border(width = 0.dp, color = Color.Transparent)
                                        .padding(start = 8.dp),
                            )
                        }
                        when {
                            row.sticker != null ->
                                Text(
                                    row.sticker!!.glyph,
                                    style =
                                        MaterialTheme.typography.headlineSmall.copy(
                                            color = Brand.Red,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 1.sp,
                                        ),
                                )
                            gone -> Text(row.text, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
                            else -> Text(row.text, style = MaterialTheme.typography.bodyLarge.copy(color = ink))
                        }
                        Text(
                            row.time,
                            style = MaterialTheme.typography.labelSmall.copy(color = ink.copy(alpha = 0.55f)),
                            modifier = Modifier.align(Alignment.End),
                        )
                        row.status?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(color = Brand.Yellow)) }
                    }
                    if (row.reactions.isNotEmpty()) Reactions(row.reactions) { send(GroupIntent.React(row.id, it)) }
                }
            }
        }
    }
}

@Composable
private fun Reactions(
    reactions: List<ReactionUi>,
    onTap: (String) -> Unit,
) {
    Row(Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { r ->
            Text(
                "${r.sticker.glyph} ${r.count}",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = if (r.mine) Brand.Ink else Brand.Paper,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier =
                    Modifier
                        .background(if (r.mine) Brand.Yellow else Brand.Ink2, RoundedCornerShape(999.dp))
                        .border(1.dp, Brand.Line, RoundedCornerShape(999.dp))
                        .clickable { onTap(r.sticker.key) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .semantics { contentDescription = r.a11y },
            )
        }
    }
}

@Composable
private fun GameCard(
    card: GameCardUi,
    send: (GroupIntent) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Brand.Ink2, RoundedCornerShape(12.dp))
            .border(2.dp, if (card.myTurn) Brand.Red else Brand.Line, RoundedCornerShape(12.dp))
            .clickable { send(if (card.canJoin) GroupIntent.JoinGame(card.gameId) else GroupIntent.OpenGame(card.gameId)) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameGlyph(card.kind)
        Column(Modifier.weight(1f)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            Text(card.line, style = MaterialTheme.typography.bodySmall.copy(color = if (card.myTurn) Brand.Red else Brand.Muted))
        }
        Text(
            card.action,
            style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink),
            modifier =
                Modifier
                    .background(
                        if (card.canJoin ||
                            card.myTurn
                        ) {
                            Brand.Red
                        } else {
                            Brand.Paper
                        },
                        RoundedCornerShape(8.dp),
                    ).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** A game's small picture: two discs, a grid, a clash. */
@Composable
fun GameGlyph(kind: GameKind?) {
    Canvas(Modifier.size(34.dp)) {
        when (kind) {
            GameKind.Four -> {
                drawCircle(Brand.Red, size.minDimension / 4, Offset(size.width * 0.3f, size.height * 0.62f))
                drawCircle(Brand.Cyan, size.minDimension / 4, Offset(size.width * 0.7f, size.height * 0.62f))
                drawCircle(Brand.Red, size.minDimension / 4, Offset(size.width * 0.5f, size.height * 0.28f))
            }
            GameKind.Panels -> {
                val s = size.width / 3
                for (i in 0..3) {
                    drawLine(Brand.Paper, Offset(i * s, 0f), Offset(i * s, size.height), 2f)
                    drawLine(Brand.Paper, Offset(0f, i * s), Offset(size.width, i * s), 2f)
                }
                drawRect(Brand.Red, Offset(s, s), Size(s, s))
            }
            else -> {
                drawRect(Brand.Yellow, Offset.Zero, size)
                drawLine(Brand.Ink, Offset(size.width * 0.2f, size.height * 0.8f), Offset(size.width * 0.8f, size.height * 0.2f), 5f)
                drawLine(Brand.Ink, Offset(size.width * 0.2f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.8f), 5f)
            }
        }
    }
}

@Composable
private fun Composer(
    state: GroupUiState,
    send: (GroupIntent) -> Unit,
) {
    var stickers by rememberSaveable { mutableStateOf(false) }
    val c = state.composer
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.Ink2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.readOnly?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(color = Brand.Muted),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
            return@Column
        }
        val banner = c.replyTo ?: if (c.editingId != null) c.placeholder else null
        banner?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Cyan), modifier = Modifier.weight(1f))
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelLarge.copy(color = Brand.Muted),
                    modifier =
                        Modifier
                            .clickable { send(if (c.editingId != null) GroupIntent.CancelEdit else GroupIntent.CancelReply) }
                            .padding(8.dp)
                            .semantics { contentDescription = I18n.text("common.cancel") },
                )
            }
        }
        if (stickers) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.stickers.forEach { s ->
                    Text(
                        s.glyph,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Black),
                        modifier =
                            Modifier
                                .background(Brand.Yellow, RoundedCornerShape(8.dp))
                                .clickable {
                                    stickers = false
                                    send(GroupIntent.SendSticker(s.key))
                                }.padding(horizontal = 10.dp, vertical = 8.dp)
                                .semantics { contentDescription = s.label },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "BOOM",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = if (stickers) Brand.Ink else Brand.Yellow,
                        fontWeight = FontWeight.Black,
                    ),
                modifier =
                    Modifier
                        .background(if (stickers) Brand.Yellow else Brand.Ink, RoundedCornerShape(8.dp))
                        .border(1.dp, Brand.Yellow, RoundedCornerShape(8.dp))
                        .clickable { stickers = !stickers }
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .semantics { contentDescription = I18n.text("groups.chat.stickers") },
            )
            Text(
                "▶",
                style = MaterialTheme.typography.labelLarge.copy(color = Brand.Cyan),
                modifier =
                    Modifier
                        .border(1.dp, Brand.Line, RoundedCornerShape(8.dp))
                        .clickable { send(GroupIntent.ShowNewGame) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .semantics { contentDescription = state.playLabel },
            )
            Box(
                Modifier
                    .weight(1f)
                    .background(Brand.InputBg, RoundedCornerShape(10.dp))
                    .border(1.dp, Brand.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (c.text.isEmpty()) Text(c.placeholder, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
                BasicTextField(
                    value = c.text,
                    onValueChange = { send(GroupIntent.Text(it)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Brand.Paper),
                    cursorBrush = SolidColor(Brand.Red),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "→",
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        color = if (c.canSend) Brand.Ink else Brand.Muted,
                        fontWeight = FontWeight.Bold,
                    ),
                modifier =
                    Modifier
                        .background(if (c.canSend) Brand.Red else Brand.Ink, RoundedCornerShape(10.dp))
                        .border(1.dp, if (c.canSend) Brand.Ink else Brand.Line, RoundedCornerShape(10.dp))
                        .clickable(enabled = c.canSend) { send(GroupIntent.Send) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { contentDescription = I18n.text("groups.chat.send") },
            )
        }
        c.counter?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(color = Brand.Muted),
                modifier = Modifier.align(Alignment.End),
            )
        }
        c.error?.let { ErrorText(it) }
    }
}

@Composable
private fun Toast(
    text: String,
    modifier: Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Ink),
        modifier = modifier.background(Brand.Paper, RoundedCornerShape(999.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(
    actions: ActionsUi,
    send: (GroupIntent) -> Unit,
) {
    var reporting by remember(actions.messageId) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { send(GroupIntent.CloseActions) }, containerColor = Brand.Ink2) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(actions.excerpt, maxLines = 2, style = MaterialTheme.typography.bodyMedium.copy(color = Brand.Muted))
            if (reporting) {
                Text(I18n.text("groups.report.title"), style = MaterialTheme.typography.titleMedium)
                actions.reasons.forEach { r ->
                    GhostButton(r.label, { send(GroupIntent.Report(actions.messageId, r.reason)) })
                }
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.reactions.forEach { r ->
                    Text(
                        r.sticker.glyph,
                        style = MaterialTheme.typography.labelLarge.copy(color = Brand.Ink, fontWeight = FontWeight.Black),
                        modifier =
                            Modifier
                                .background(if (r.mine) Brand.Red else Brand.Yellow, RoundedCornerShape(8.dp))
                                .clickable { send(GroupIntent.React(actions.messageId, r.sticker.key)) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .semantics { contentDescription = r.a11y },
                    )
                }
            }
            if (actions.canReply) GhostButton(I18n.text("groups.actions.reply"), { send(GroupIntent.Reply(actions.messageId)) })
            if (actions.canEdit) GhostButton(I18n.text("groups.actions.edit"), { send(GroupIntent.Edit(actions.messageId)) })
            if (actions.canRestore) GhostButton(I18n.text("groups.actions.restore"), { send(GroupIntent.Restore(actions.messageId)) })
            if (actions.canDelete) GhostButton(I18n.text("groups.actions.delete"), { send(GroupIntent.Delete(actions.messageId)) })
            if (actions.canRemove) GhostButton(I18n.text("groups.actions.remove"), { send(GroupIntent.Remove(actions.messageId)) })
            if (actions.canReport) GhostButton(I18n.text("groups.actions.report"), { reporting = true })
            Spacer(Modifier.size(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    header: GroupHeader,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val link = GroupLinks.shareUrl(BuildConfig.WEB_BASE_URL, header.handle)
    var copied by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Brand.Ink2) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(I18n.text("groups.share.title"), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.size(232.dp)) {
                Box(Modifier.padding(start = 6.dp, top = 6.dp).fillMaxSize().background(Brand.Red, RoundedCornerShape(12.dp)))
                Box(
                    Modifier
                        .size(226.dp)
                        .background(Brand.Paper, RoundedCornerShape(12.dp))
                        .border(2.dp, Brand.Ink, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) { QrCode(link, Modifier.fillMaxSize().semantics { contentDescription = I18n.text("groups.share.qrA11y") }) }
            }
            Text(link, style = MaterialTheme.typography.bodySmall.copy(color = Brand.Cyan), textAlign = TextAlign.Center)
            Text(I18n.text("groups.share.qrHint"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(I18n.text("groups.share"), {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(share, header.name))
                }, Modifier.weight(1f))
                GhostButton(if (copied) I18n.text("groups.share.copied") else I18n.text("groups.share.copy"), {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(header.name, link))
                    copied = true
                }, Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** [text] as a QR code (ZXing, medium correction), ink on paper. */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
) {
    val matrix =
        remember(text) {
            runCatching {
                QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    0,
                    0,
                    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
                )
            }.getOrNull()
        } ?: return
    Canvas(modifier.aspectRatio(1f)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Brand.Ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGameSheet(
    form: NewGameForm,
    send: (GroupIntent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { send(GroupIntent.CancelNewGame) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Ink2,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(I18n.text("games.new.title"), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.kinds.forEach { (kind, name) ->
                    Choice(name, form.kind == kind, Modifier.weight(1f)) {
                        send(GroupIntent.EditNewGame(form.copy(kind = kind, seats = kind.seats.first())))
                    }
                }
            }
            Text(form.rules, style = MaterialTheme.typography.bodySmall)
            if (form.seatChoices.size > 1) {
                Text(I18n.text("games.new.seats"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.seatChoices.forEach { n ->
                        Choice(n.toString(), form.seats == n) { send(GroupIntent.EditNewGame(form.copy(seats = n))) }
                    }
                }
            }
            SwitchRow(I18n.text("games.new.ranked"), null, form.ranked) { send(GroupIntent.EditNewGame(form.copy(ranked = it))) }
            if (form.members.isNotEmpty()) {
                Text(I18n.text("games.new.invite"), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    form.members.forEach { m ->
                        Choice(m.name, m.chosen, enabled = m.chosen || form.canInviteMore) { send(GroupIntent.ToggleInvite(m.userId)) }
                    }
                }
                if (form.invited.isNotEmpty()) {
                    SwitchRow(
                        I18n.text("games.new.inviteOnly"),
                        null,
                        form.inviteOnly,
                    ) { send(GroupIntent.EditNewGame(form.copy(inviteOnly = it))) }
                }
            }
            form.error?.let { ErrorText(it) }
            PrimaryButton(I18n.text("games.new.start"), { send(GroupIntent.StartGame) }, enabled = !form.working)
            Spacer(Modifier.size(8.dp))
        }
    }
}
