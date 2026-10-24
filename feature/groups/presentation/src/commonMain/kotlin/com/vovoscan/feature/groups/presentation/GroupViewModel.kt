package com.vovoscan.feature.groups.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.DAY_MILLIS
import com.vovoscan.core.common.DeviceTimeZone
import com.vovoscan.core.common.UtcOffset
import com.vovoscan.core.common.i18n.LocalFormat
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.groups.domain.Chat
import com.vovoscan.feature.groups.domain.Entry
import com.vovoscan.feature.groups.domain.Game
import com.vovoscan.feature.groups.domain.GameKind
import com.vovoscan.feature.groups.domain.GameStatus
import com.vovoscan.feature.groups.domain.Group
import com.vovoscan.feature.groups.domain.GroupLinks
import com.vovoscan.feature.groups.domain.GroupRules
import com.vovoscan.feature.groups.domain.GroupStatus
import com.vovoscan.feature.groups.domain.GroupsAccess
import com.vovoscan.feature.groups.domain.GroupsRepository
import com.vovoscan.feature.groups.domain.GroupsUser
import com.vovoscan.feature.groups.domain.Live
import com.vovoscan.feature.groups.domain.Member
import com.vovoscan.feature.groups.domain.MemberState
import com.vovoscan.feature.groups.domain.Message
import com.vovoscan.feature.groups.domain.MessageKind
import com.vovoscan.feature.groups.domain.MessageStatus
import com.vovoscan.feature.groups.domain.Reaction
import com.vovoscan.feature.groups.domain.ReportReason
import com.vovoscan.feature.groups.domain.Sticker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The group's card at the top of its screen. */
data class GroupHeader(
    val id: String,
    val name: String,
    val handle: String,
    val about: String,
    val accent: Int,
    val verified: Boolean,
    /** "128 members ✦ Approval ✦ Unlisted". */
    val meta: String,
    /** `/g/<handle>`: the native side adds the website's origin (the share sheet, the QR code). */
    val sharePath: String,
    /** `vovoscan://g/<handle>`. */
    val appLink: String,
    /** "Owner", "Admin", or null for a member. */
    val role: String?,
    val verifiedLabel: String,
)

enum class JoinAction { None, Join, Ask, Withdraw, Banned }

data class JoinUi(
    val action: JoinAction,
    /** "Join", "Ask to join", "Request sent ✦ Take it back", "You can't join this group". */
    val label: String,
    val hint: String?,
    val note: String = "",
    val notePlaceholder: String = t("groups.join.notePlaceholder"),
    val working: Boolean = false,
)

data class ReactionUi(
    val sticker: StickerUi,
    val count: Int,
    val mine: Boolean,
    val a11y: String,
)

enum class RowKind { Text, Sticker, System, Game, Gone }

data class MessageRow(
    val id: String,
    val kind: RowKind,
    /** Written by the caller: drawn on the right. */
    val own: Boolean,
    /** Shown above the first of a run of messages by the same author. */
    val author: String?,
    /** 0..7, the author's colour (stable per account). */
    val authorSlot: Int,
    val text: String,
    val sticker: StickerUi?,
    val reply: String?,
    /** "15:05", "15:05 ✦ edited". */
    val time: String,
    /** A day starts here: "Today", "Yesterday", "Oct 21, 2026". */
    val day: String?,
    /** "Held for review: only you and the admins see it." */
    val status: String?,
    val reactions: List<ReactionUi>,
    /** A game card: its id, its live card in [GroupUiState.games]. */
    val gameId: String?,
    val a11y: String,
)

data class Composer(
    val text: String = "",
    /** "Replying to Aya: see you at…" */
    val replyTo: String? = null,
    val replyToId: String? = null,
    /** Editing one's own message (within 15 minutes). */
    val editingId: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val canSend: Boolean get() = !sending && GroupRules.textProblem(text) == null
    val counter: String? get() = text.length.takeIf { it > GroupRules.TEXT_MAX - 200 }?.let { "$it / ${GroupRules.TEXT_MAX}" }
    val placeholder: String get() = if (editingId != null) t("groups.composer.editing") else t("groups.composer.placeholder")
}

/** What can be done with one message (a long press). */
data class ActionsUi(
    val messageId: String,
    val excerpt: String,
    val canReply: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val canReport: Boolean,
    val canRemove: Boolean,
    val canRestore: Boolean,
    val reactions: List<ReactionUi>,
    val reasons: List<ReasonUi>,
)

data class ReasonUi(
    val reason: ReportReason,
    val label: String,
)

/** A game's card in the chat, kept current by the group's live snapshot. */
data class GameCardUi(
    val gameId: String,
    val kind: GameKind?,
    val title: String,
    /** "Aya ✦ Ben", "1 seat left", "Aya won", "Your turn". */
    val line: String,
    val status: GameStatus,
    val myTurn: Boolean,
    val canJoin: Boolean,
    val action: String,
)

data class InviteChoice(
    val userId: String,
    val name: String,
    val chosen: Boolean,
)

data class NewGameForm(
    val kind: GameKind = GameKind.Four,
    val seats: Int = 2,
    val ranked: Boolean = true,
    val inviteOnly: Boolean = false,
    val members: List<InviteChoice> = emptyList(),
    val working: Boolean = false,
    val error: String? = null,
) {
    val seatChoices: List<Int> get() = kind.seats
    val invited: List<String> get() = members.filter { it.chosen }.map { it.userId }
    val canInviteMore: Boolean get() = invited.size < seats - 1
    val kinds: List<Pair<GameKind, String>> get() = GameKind.entries.map { it to MessageLines.gameName(it) }
    val rules: String get() = t("games.${kind.wire}.rules")
}

/** Something the screen opens once, answered with [GroupIntent.Handled]. */
sealed interface GroupOpen {
    val seq: Int

    data class GameScreen(
        override val seq: Int,
        val gameId: String,
    ) : GroupOpen

    /** The group is gone, or the caller left it: back to the list. */
    data class Back(
        override val seq: Int,
    ) : GroupOpen
}

data class ScrollTo(
    val seq: Int,
    val messageId: String,
    val animated: Boolean,
)

data class GroupUiState(
    val visible: Boolean = false,
    val loading: Boolean = true,
    val header: GroupHeader? = null,
    val member: Boolean = false,
    val admin: Boolean = false,
    val owner: Boolean = false,
    val join: JoinUi? = null,
    /** A paused group: members read, nobody writes. */
    val readOnly: String? = null,
    val messages: List<MessageRow> = emptyList(),
    val hasOlder: Boolean = true,
    val loadingOlder: Boolean = false,
    val composer: Composer = Composer(),
    val actions: ActionsUi? = null,
    val stickers: List<StickerUi> = StickerUi.all,
    val games: Map<String, GameCardUi> = emptyMap(),
    val notify: Boolean = true,
    val pending: Int = 0,
    val newGame: NewGameForm? = null,
    val notice: String? = null,
    val error: String? = null,
    /** One line for a few seconds ("Reported. Thank you."). */
    val toast: String? = null,
    val open: GroupOpen? = null,
    val scroll: ScrollTo? = null,
    val leaveConfirm: Boolean = false,
) {
    val notifyLabel: String get() = if (notify) t("groups.notify.on") else t("groups.notify.off")
    val pendingLabel: String? get() = pending.takeIf { admin && it > 0 }?.let { t("groups.pending", "count" to it) }
    val leaveLabel: String get() = t("groups.leave")
    val leaveQuestion: String get() = if (owner) t("groups.leave.owner") else t("groups.leave.confirm")
    val playLabel: String get() = t("games.play")
    val emptyChat: String get() = t("groups.chat.empty")
}

sealed interface GroupIntent {
    data object Start : GroupIntent

    data object Stop : GroupIntent

    data object Refresh : GroupIntent

    data object LoadOlder : GroupIntent

    data class Note(
        val text: String,
    ) : GroupIntent

    /** Join, ask to join, or take a request back, as [JoinUi.action] says. */
    data object JoinAct : GroupIntent

    data object AskLeave : GroupIntent

    data object CancelLeave : GroupIntent

    data object ConfirmLeave : GroupIntent

    data object ToggleNotify : GroupIntent

    data class Text(
        val text: String,
    ) : GroupIntent

    data object Send : GroupIntent

    data class SendSticker(
        val key: String,
    ) : GroupIntent

    data class OpenActions(
        val messageId: String,
    ) : GroupIntent

    data object CloseActions : GroupIntent

    data class Reply(
        val messageId: String,
    ) : GroupIntent

    data object CancelReply : GroupIntent

    data class React(
        val messageId: String,
        val key: String,
    ) : GroupIntent

    data class Edit(
        val messageId: String,
    ) : GroupIntent

    data object CancelEdit : GroupIntent

    data class Delete(
        val messageId: String,
    ) : GroupIntent

    data class Report(
        val messageId: String,
        val reason: ReportReason,
    ) : GroupIntent

    /** Admins: take a message down. */
    data class Remove(
        val messageId: String,
    ) : GroupIntent

    /** Admins: put back a held message. */
    data class Restore(
        val messageId: String,
    ) : GroupIntent

    data class OpenGame(
        val gameId: String,
    ) : GroupIntent

    data class JoinGame(
        val gameId: String,
    ) : GroupIntent

    data object ShowNewGame : GroupIntent

    data object CancelNewGame : GroupIntent

    data class EditNewGame(
        val form: NewGameForm,
    ) : GroupIntent

    data class ToggleInvite(
        val userId: String,
    ) : GroupIntent

    data object StartGame : GroupIntent

    data class Handled(
        val seq: Int,
    ) : GroupIntent

    data object ToastShown : GroupIntent
}

/**
 * One group: its card (join, ask, leave, share link and QR), and for members the live chat (history paged up,
 * replies, stickers, reactions, edits within 15 minutes, deletes, reports; admins remove and restore), notifications
 * from it on or off, and the games played in it. [ref] is the group's id, or `@handle` (a link, a QR code).
 */
class GroupViewModel(
    private val ref: String,
    private val repository: GroupsRepository,
    private val access: GroupsAccess = GroupsAccess.Off,
    private val user: GroupsUser = GroupsUser.None,
    private val clock: Clock,
    private val offset: UtcOffset = DeviceTimeZone,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GroupUiState())
    val state: StateFlow<GroupUiState> = mutableState.asStateFlow()

    private var groupId: String? = ref.takeUnless { it.startsWith("@") }
    private var group: Group? = null
    private var messages: List<Message> = emptyList()
    private val games = mutableMapOf<String, Game>()
    private var liveJob: Job? = null
    private var tickJob: Job? = null
    private var readJob: Job? = null
    private var started = false
    private var on = false
    private var seq = 0
    private var lastSeenNewest: String? = null

    init {
        access
            .enabled()
            .distinctUntilChanged()
            .onEach { enabled ->
                on = enabled
                mutableState.update { it.copy(visible = enabled) }
                if (enabled) load()
            }.launchIn(viewModelScope)
    }

    fun onIntent(intent: GroupIntent) {
        when (intent) {
            GroupIntent.Start -> {
                started = true
                follow()
                tick()
            }
            GroupIntent.Stop -> {
                started = false
                liveJob?.cancel()
                liveJob = null
                tickJob?.cancel()
                tickJob = null
            }
            GroupIntent.Refresh -> load()
            GroupIntent.LoadOlder -> loadOlder()
            is GroupIntent.Note -> mutableState.update { s -> s.copy(join = s.join?.copy(note = intent.text.take(GroupRules.NOTE_MAX))) }
            GroupIntent.JoinAct -> joinAct()
            GroupIntent.AskLeave -> mutableState.update { it.copy(leaveConfirm = true) }
            GroupIntent.CancelLeave -> mutableState.update { it.copy(leaveConfirm = false) }
            GroupIntent.ConfirmLeave -> leave()
            GroupIntent.ToggleNotify -> toggleNotify()
            is GroupIntent.Text -> composer { it.copy(text = intent.text, error = null) }
            GroupIntent.Send -> send()
            is GroupIntent.SendSticker -> Sticker.of(intent.key)?.let(::sendSticker)
            is GroupIntent.OpenActions -> openActions(intent.messageId)
            GroupIntent.CloseActions -> mutableState.update { it.copy(actions = null) }
            is GroupIntent.Reply -> reply(intent.messageId)
            GroupIntent.CancelReply -> composer { it.copy(replyTo = null, replyToId = null) }
            is GroupIntent.React -> react(intent.messageId, Sticker.of(intent.key))
            is GroupIntent.Edit -> edit(intent.messageId)
            GroupIntent.CancelEdit -> composer { Composer() }
            is GroupIntent.Delete -> takeDown(intent.messageId)
            is GroupIntent.Report -> report(intent.messageId, intent.reason)
            is GroupIntent.Remove -> takeDown(intent.messageId)
            is GroupIntent.Restore -> restore(intent.messageId)
            is GroupIntent.OpenGame -> open { GroupOpen.GameScreen(it, intent.gameId) }
            is GroupIntent.JoinGame -> joinGame(intent.gameId)
            GroupIntent.ShowNewGame -> showNewGame()
            GroupIntent.CancelNewGame -> mutableState.update { it.copy(newGame = null) }
            is GroupIntent.EditNewGame -> editNewGame(intent.form)
            is GroupIntent.ToggleInvite -> toggleInvite(intent.userId)
            GroupIntent.StartGame -> startGame()
            is GroupIntent.Handled -> mutableState.update { if (it.open?.seq == intent.seq) it.copy(open = null) else it }
            GroupIntent.ToastShown -> mutableState.update { it.copy(toast = null) }
        }
    }

    private fun load() {
        if (!on) return
        viewModelScope.launch {
            val id = groupId
            if (id != null && messages.isEmpty()) repository.cachedLive(id)?.let { apply(it, offline = true) }
            val result = if (id != null) repository.group(id) else repository.byHandle(ref.removePrefix("@"))
            when (result) {
                is AppResult.Ok -> {
                    groupId = result.value.id
                    showGroup(result.value)
                    if (result.value.me?.isMember == true) {
                        if (messages.isEmpty()) catchUp()
                        follow()
                    }
                }
                is AppResult.Err -> failed(result.error)
            }
        }
    }

    /** No snapshot yet: the latest messages, so the chat shows before the stream answers. */
    private suspend fun catchUp() {
        val id = groupId ?: return
        when (val result = repository.messages(id)) {
            is AppResult.Ok -> {
                messages = Chat.merge(messages, result.value)
                mutableState.update { it.copy(hasOlder = result.value.size >= HISTORY_PAGE) }
                showMessages(scrollToEnd = true)
            }
            is AppResult.Err -> Unit
        }
    }

    private fun follow() {
        val id = groupId ?: return
        if (!started || !on || liveJob?.isActive == true || group?.me?.isMember != true) return
        liveJob =
            viewModelScope.launch {
                val result =
                    repository.live(id) { live ->
                        apply(live, offline = false)
                        true
                    }
                if (result is AppResult.Err) {
                    when (result.error) {
                        // Out of the group (removed, banned, left elsewhere): show its card again.
                        is AppError.Rejected, AppError.Forbidden -> load()
                        else -> mutableState.update { it.copy(notice = t("groups.offline")) }
                    }
                }
            }
    }

    private fun apply(
        live: Live,
        offline: Boolean,
    ) {
        groupId = live.group.id
        live.games.forEach { games[it.id] = it }
        val before = messages.lastOrNull()?.id
        messages = Chat.merge(messages, live.messages)
        showGroup(live.group)
        mutableState.update { it.copy(notice = if (offline) t("groups.offline") else null) }
        val newest = messages.lastOrNull()?.id
        showMessages(scrollToEnd = newest != before)
        if (!offline && newest != null && newest != lastSeenNewest && started) {
            lastSeenNewest = newest
            markRead()
        }
    }

    private fun markRead() {
        val id = groupId ?: return
        readJob?.cancel()
        readJob =
            viewModelScope.launch {
                delay(READ_DELAY_MS)
                repository.markRead(id)
            }
    }

    private fun showGroup(g: Group) {
        group = g
        val me = g.me
        val member = me?.isMember == true
        val join =
            when {
                member -> null
                me?.state == MemberState.Banned -> JoinUi(JoinAction.Banned, t("groups.join.banned"), null)
                me?.state == MemberState.Requested -> JoinUi(JoinAction.Withdraw, t("groups.join.withdraw"), t("groups.join.requested"))
                g.status != GroupStatus.Active -> JoinUi(JoinAction.None, t("groups.join.paused"), null)
                g.entry == Entry.Approval ->
                    JoinUi(
                        JoinAction.Ask,
                        t("groups.join.ask"),
                        t("groups.join.askHint"),
                        state.value.join
                            ?.note
                            .orEmpty(),
                    )
                else -> JoinUi(JoinAction.Join, t("groups.join.join"), null)
            }
        mutableState.update {
            it.copy(
                loading = false,
                header = header(g),
                member = member,
                admin = me?.isAdmin == true,
                owner = me?.isOwner == true,
                join = join?.copy(working = false),
                readOnly = if (member && g.status == GroupStatus.Suspended) t("groups.suspended") else null,
                notify = me?.notify ?: true,
                pending = g.pending,
                error = null,
            )
        }
    }

    private fun header(g: Group): GroupHeader =
        GroupHeader(
            id = g.id,
            name = g.name,
            handle = "@${g.handle}",
            about = g.about,
            accent = g.accent,
            verified = g.verified,
            meta =
                listOfNotNull(
                    t("groups.members", "count" to g.memberCount),
                    if (g.entry == Entry.Approval) t("groups.entry.approval") else t("groups.entry.open"),
                    if (!g.listed) t("groups.unlisted") else null,
                ).joinToString(" ✦ "),
            sharePath = g.sharePath,
            appLink = GroupLinks.appUrl(g.handle),
            role =
                when {
                    g.me?.isOwner == true -> t("groups.role.owner")
                    g.me?.isAdmin == true -> t("groups.role.admin")
                    else -> null
                },
            verifiedLabel = t("groups.verified"),
        )

    private fun showMessages(scrollToEnd: Boolean) {
        val me = user.id()
        val now = clock.nowMillis()
        val today = (now + offset.offsetMillis(now)).floorDiv(DAY_MILLIS)
        val rows = ArrayList<MessageRow>(messages.size)
        var previous: Message? = null
        for (m in messages) {
            val localDay = (m.createdAtMillis + offset.offsetMillis(m.createdAtMillis)).floorDiv(DAY_MILLIS)
            val prevDay = previous?.let { (it.createdAtMillis + offset.offsetMillis(it.createdAtMillis)).floorDiv(DAY_MILLIS) }
            val day =
                if (prevDay == localDay) {
                    null
                } else {
                    when (today - localDay) {
                        0L -> t("groups.day.today")
                        1L -> t("groups.day.yesterday")
                        else -> LocalFormat.date(m.createdAtMillis + offset.offsetMillis(m.createdAtMillis))
                    }
                }
            val run =
                previous != null &&
                    day == null &&
                    previous.kind != MessageKind.System &&
                    previous.authorId == m.authorId &&
                    m.createdAtMillis - previous.createdAtMillis < RUN_MS
            rows += row(m, me, showAuthor = !run, day = day)
            previous = m
        }
        mutableState.update { s ->
            s.copy(
                messages = rows,
                games = games.mapValues { (_, g) -> card(g, me) },
                scroll = if (scrollToEnd && rows.isNotEmpty()) ScrollTo(nextSeq(), rows.last().id, s.messages.isNotEmpty()) else s.scroll,
            )
        }
    }

    private fun row(
        m: Message,
        me: String?,
        showAuthor: Boolean,
        day: String?,
    ): MessageRow {
        val own = me != null && m.authorId == me
        val gone = m.status == MessageStatus.Deleted || m.status == MessageStatus.Removed
        val kind =
            when {
                gone -> RowKind.Gone
                m.kind == MessageKind.System -> RowKind.System
                m.kind == MessageKind.Game -> RowKind.Game
                m.kind == MessageKind.Sticker -> RowKind.Sticker
                else -> RowKind.Text
            }
        val text =
            when (kind) {
                RowKind.Gone -> if (m.status == MessageStatus.Deleted) t("groups.message.deleted") else t("groups.message.removed")
                RowKind.System -> m.system?.let(MessageLines::system).orEmpty()
                RowKind.Game -> m.card?.let { t("games.card.started", "game" to MessageLines.gameName(it.game)) }.orEmpty()
                RowKind.Sticker -> m.sticker?.glyph.orEmpty()
                RowKind.Text -> m.body
            }
        val local = m.createdAtMillis + offset.offsetMillis(m.createdAtMillis)
        val time = listOfNotNull(clockTime(local), m.editedAtMillis?.let { t("groups.message.edited") }).joinToString(" ✦ ")
        val reactions = m.reactions.map { reactionUi(it.sticker, it.count, it.sticker == m.mine) }
        val author = m.authorName?.takeIf { showAuthor && !own && kind != RowKind.System }
        return MessageRow(
            id = m.id,
            kind = kind,
            own = own,
            author = author,
            authorSlot = slotOf(m.authorId),
            text = text,
            sticker = m.sticker?.takeIf { kind == RowKind.Sticker }?.let(StickerUi::of),
            reply =
                m.replyTo?.let { q ->
                    t("groups.message.replyTo", "name" to (q.authorName ?: t("groups.someone")), "text" to q.excerpt)
                },
            time = time,
            day = day,
            status = if (m.status == MessageStatus.Held) t("groups.message.heldHint") else null,
            reactions = reactions,
            gameId = m.card?.gameId?.takeIf { kind == RowKind.Game },
            a11y =
                listOfNotNull(
                    if (own) t("groups.message.you") else m.authorName,
                    if (kind == RowKind.Sticker) m.sticker?.a11y() else text,
                    time,
                ).joinToString(", "),
        )
    }

    private fun reactionUi(
        sticker: Sticker,
        count: Int,
        mine: Boolean,
    ) = ReactionUi(
        StickerUi.of(sticker),
        count,
        mine,
        t("groups.reaction.a11y", "sticker" to t("sticker.${sticker.key}"), "count" to count),
    )

    private fun card(
        g: Game,
        me: String?,
    ): GameCardUi {
        val title = g.kind?.let(MessageLines::gameName) ?: t("games.aGame")
        val names = g.players.joinToString(" ✦ ") { it.name }
        val line =
            when (g.status) {
                GameStatus.Open ->
                    listOf(names, t("games.seatsLeft", "count" to g.freeSeats)).filter { it.isNotEmpty() }.joinToString(" ✦ ")
                GameStatus.Playing -> if (g.myTurn) t("games.yourTurn") else names
                GameStatus.Over -> GameLines.result(g)
                GameStatus.Cancelled -> t("games.cancelled")
            }
        val canJoin = g.canJoin(me)
        return GameCardUi(
            gameId = g.id,
            kind = g.kind,
            title = if (g.ranked) t("games.ranked", "game" to title) else title,
            line = line,
            status = g.status,
            myTurn = g.myTurn,
            canJoin = canJoin,
            action =
                when {
                    canJoin -> t("games.take")
                    g.myTurn -> t("games.playNow")
                    g.status == GameStatus.Over -> t("games.review")
                    else -> t("games.watch")
                },
        )
    }

    private fun loadOlder() {
        val id = groupId ?: return
        val oldest = messages.firstOrNull()?.id ?: return
        if (state.value.loadingOlder || !state.value.hasOlder) return
        mutableState.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            when (val result = repository.messages(id, before = oldest)) {
                is AppResult.Ok -> {
                    messages = Chat.merge(messages, result.value)
                    mutableState.update { it.copy(loadingOlder = false, hasOlder = result.value.size >= HISTORY_PAGE) }
                    showMessages(scrollToEnd = false)
                }
                is AppResult.Err -> mutableState.update { it.copy(loadingOlder = false, error = groupError(result.error)) }
            }
        }
    }

    private fun joinAct() {
        val join = state.value.join ?: return
        val g = group ?: return
        mutableState.update { it.copy(join = join.copy(working = true)) }
        viewModelScope.launch {
            when (join.action) {
                JoinAction.Join, JoinAction.Ask -> {
                    val note = GroupRules.clean(join.note, lines = true).takeIf { join.action == JoinAction.Ask && it.isNotEmpty() }
                    when (val result = repository.join(g.id, note)) {
                        is AppResult.Ok -> {
                            showGroup(result.value)
                            if (result.value.me?.isMember == true) {
                                catchUp()
                                follow()
                            } else {
                                mutableState.update { it.copy(toast = t("groups.join.sent")) }
                            }
                        }
                        is AppResult.Err -> joinFailed(result.error)
                    }
                }
                JoinAction.Withdraw ->
                    when (val result = repository.leave(g.id)) {
                        is AppResult.Ok -> load()
                        is AppResult.Err -> joinFailed(result.error)
                    }
                JoinAction.None, JoinAction.Banned -> mutableState.update { it.copy(join = join) }
            }
        }
    }

    private fun joinFailed(error: AppError) =
        mutableState.update { it.copy(join = it.join?.copy(working = false), error = groupError(error)) }

    private fun leave() {
        val g = group ?: return
        mutableState.update { it.copy(leaveConfirm = false) }
        viewModelScope.launch {
            when (val result = repository.leave(g.id)) {
                is AppResult.Ok -> {
                    liveJob?.cancel()
                    open { GroupOpen.Back(it) }
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun toggleNotify() {
        val g = group ?: return
        val next = !state.value.notify
        mutableState.update { it.copy(notify = next) }
        viewModelScope.launch {
            val result = repository.notify(g.id, next)
            if (result is AppResult.Err) mutableState.update { it.copy(notify = !next, error = groupError(result.error)) }
        }
    }

    private fun composer(change: (Composer) -> Composer) = mutableState.update { it.copy(composer = change(it.composer)) }

    private fun send() {
        val id = groupId ?: return
        val c = state.value.composer
        GroupRules.textProblem(c.text)?.let { code ->
            composer { it.copy(error = groupError(AppError.Rejected(code, ""))) }
            return
        }
        val body = GroupRules.clean(c.text, lines = true)
        composer { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val editing = c.editingId
            val result = if (editing != null) repository.edit(editing, body) else repository.send(id, body, null, c.replyToId)
            when (result) {
                is AppResult.Ok -> {
                    messages = Chat.merge(messages, listOf(result.value))
                    composer { Composer() }
                    showMessages(scrollToEnd = editing == null)
                }
                is AppResult.Err -> composer { it.copy(sending = false, error = groupError(result.error)) }
            }
        }
    }

    private fun sendSticker(sticker: Sticker) {
        val id = groupId ?: return
        val replyTo = state.value.composer.replyToId
        viewModelScope.launch {
            when (val result = repository.send(id, null, sticker, replyTo)) {
                is AppResult.Ok -> {
                    messages = Chat.merge(messages, listOf(result.value))
                    composer { it.copy(replyTo = null, replyToId = null) }
                    showMessages(scrollToEnd = true)
                }
                is AppResult.Err -> composer { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun openActions(messageId: String) {
        val m = messages.firstOrNull { it.id == messageId } ?: return
        if (m.kind == MessageKind.System) return
        val me = user.id()
        val own = me != null && m.authorId == me
        val visible = m.status == MessageStatus.Visible
        val admin = state.value.admin
        val writable = state.value.member && state.value.readOnly == null
        mutableState.update {
            it.copy(
                actions =
                    ActionsUi(
                        messageId = m.id,
                        excerpt = MessageLines.preview(m),
                        canReply = writable && visible,
                        canEdit = writable && GroupRules.editable(m, me, clock.nowMillis()),
                        canDelete = own && m.status != MessageStatus.Deleted && m.status != MessageStatus.Removed,
                        canReport = !own && visible,
                        canRemove = admin && !own && (visible || m.status == MessageStatus.Held),
                        canRestore = admin && m.status == MessageStatus.Held,
                        reactions =
                            if (visible && writable) {
                                Sticker.entries.map { s ->
                                    reactionUi(s, m.reactions.firstOrNull { r -> r.sticker == s }?.count ?: 0, m.mine == s)
                                }
                            } else {
                                emptyList()
                            },
                        reasons = ReportReason.entries.map { r -> ReasonUi(r, t("groups.report.${r.wire}")) },
                    ),
            )
        }
    }

    private fun reply(messageId: String) {
        val m = messages.firstOrNull { it.id == messageId } ?: return
        mutableState.update { it.copy(actions = null) }
        composer {
            it.copy(
                replyToId = m.id,
                replyTo = t("groups.composer.replyingTo", "text" to MessageLines.preview(m)),
                editingId = null,
            )
        }
    }

    private fun edit(messageId: String) {
        val m = messages.firstOrNull { it.id == messageId } ?: return
        if (!GroupRules.editable(m, user.id(), clock.nowMillis())) return
        mutableState.update { it.copy(actions = null) }
        composer { Composer(text = m.body, editingId = m.id) }
    }

    private fun react(
        messageId: String,
        sticker: Sticker?,
    ) {
        val m = messages.firstOrNull { it.id == messageId } ?: return
        // The same sticker again takes it back.
        val next = if (sticker == m.mine) null else sticker
        mutableState.update { it.copy(actions = null) }
        val previous = m
        messages = messages.map { if (it.id == messageId) withReaction(it, next) else it }
        showMessages(scrollToEnd = false)
        viewModelScope.launch {
            val result = repository.react(messageId, next)
            if (result is AppResult.Err) {
                messages = messages.map { if (it.id == messageId) previous else it }
                showMessages(scrollToEnd = false)
                mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    /** The reaction shown at once; the next snapshot brings the server's counts. */
    private fun withReaction(
        m: Message,
        next: Sticker?,
    ): Message {
        val counts = m.reactions.associate { it.sticker to it.count }.toMutableMap()
        m.mine?.let { old -> counts[old] = (counts[old] ?: 1) - 1 }
        next?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val reactions =
            counts
                .filterValues { it > 0 }
                .map { (s, n) -> Reaction(s, n) }
                .sortedWith(compareByDescending<Reaction> { it.count }.thenBy { it.sticker.key })
        return m.copy(reactions = reactions, mine = next)
    }

    private fun takeDown(messageId: String) {
        mutableState.update { it.copy(actions = null) }
        viewModelScope.launch {
            when (val result = repository.deleteMessage(messageId)) {
                is AppResult.Ok -> {
                    val own = messages.firstOrNull { it.id == messageId }?.authorId == user.id()
                    messages =
                        messages.map {
                            if (it.id == messageId) {
                                it.copy(
                                    status = if (own) MessageStatus.Deleted else MessageStatus.Removed,
                                    body = "",
                                    reactions = emptyList(),
                                )
                            } else {
                                it
                            }
                        }
                    showMessages(scrollToEnd = false)
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun restore(messageId: String) {
        mutableState.update { it.copy(actions = null) }
        viewModelScope.launch {
            when (val result = repository.restore(messageId)) {
                is AppResult.Ok -> {
                    messages = messages.map { if (it.id == messageId) it.copy(status = MessageStatus.Visible) else it }
                    showMessages(scrollToEnd = false)
                    mutableState.update { it.copy(toast = t("groups.restored")) }
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun report(
        messageId: String,
        reason: ReportReason,
    ) {
        mutableState.update { it.copy(actions = null) }
        viewModelScope.launch {
            when (val result = repository.report(messageId, reason)) {
                is AppResult.Ok -> mutableState.update { it.copy(toast = t("groups.reported")) }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun joinGame(gameId: String) {
        viewModelScope.launch {
            when (val result = repository.joinGame(gameId)) {
                is AppResult.Ok -> {
                    games[gameId] = result.value
                    showMessages(scrollToEnd = false)
                    open { GroupOpen.GameScreen(it, gameId) }
                }
                is AppResult.Err -> mutableState.update { it.copy(error = groupError(result.error)) }
            }
        }
    }

    private fun showNewGame() {
        val id = groupId ?: return
        mutableState.update { it.copy(newGame = NewGameForm()) }
        // Who may be invited: the group's members (the first page, owner and admins first), never oneself.
        viewModelScope.launch {
            val result = repository.members(id, MemberState.Member, null, 1)
            if (result is AppResult.Ok) {
                val me = user.id()
                val choices =
                    result.value.members
                        .filter { it.userId != me }
                        .map { m: Member -> InviteChoice(m.userId, m.name, false) }
                mutableState.update { s -> s.copy(newGame = s.newGame?.copy(members = choices)) }
            }
        }
    }

    private fun editNewGame(form: NewGameForm) {
        val seats = if (form.seats in form.kind.seats) form.seats else form.kind.seats.first()
        // Fewer seats than invited: the last ones chosen are dropped.
        var kept = 0
        val members = form.members.map { if (it.chosen && kept < seats - 1) it.also { kept++ } else it.copy(chosen = false) }
        mutableState.update {
            it.copy(newGame = form.copy(seats = seats, members = members, inviteOnly = form.inviteOnly && kept > 0, error = null))
        }
    }

    private fun toggleInvite(userId: String) {
        val form = state.value.newGame ?: return
        val members =
            form.members.map { m ->
                when {
                    m.userId != userId -> m
                    m.chosen -> m.copy(chosen = false)
                    form.canInviteMore -> m.copy(chosen = true)
                    else -> m
                }
            }
        val next = form.copy(members = members)
        mutableState.update { it.copy(newGame = next.copy(inviteOnly = next.inviteOnly && next.invited.isNotEmpty())) }
    }

    private fun startGame() {
        val id = groupId ?: return
        val form = state.value.newGame ?: return
        if (form.working) return
        mutableState.update { it.copy(newGame = form.copy(working = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.startGame(id, form.kind, form.seats, form.ranked, form.invited, form.inviteOnly)) {
                is AppResult.Ok -> {
                    games[result.value.id] = result.value
                    mutableState.update { it.copy(newGame = null) }
                    showMessages(scrollToEnd = false)
                    open { GroupOpen.GameScreen(it, result.value.id) }
                }
                is AppResult.Err ->
                    mutableState.update {
                        it.copy(
                            newGame = it.newGame?.copy(working = false, error = groupError(result.error)),
                        )
                    }
            }
        }
    }

    private fun failed(error: AppError) {
        mutableState.update {
            it.copy(
                loading = false,
                notice = if (error == AppError.Network) t("groups.offline") else it.notice,
                error = if (error == AppError.Network) null else groupError(error),
            )
        }
        if (error is AppError.Rejected && error.code == "GROUP_NOT_FOUND" && group != null) open { GroupOpen.Back(it) }
    }

    private fun open(make: (Int) -> GroupOpen) = mutableState.update { it.copy(open = make(nextSeq())) }

    private fun nextSeq(): Int {
        seq += 1
        return seq
    }

    /** While on screen, a timer keeps game cards and "edited" windows honest. */
    private fun tick() {
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive && started) {
                    delay(TICK_MS)
                    if (games.values.any { it.status == GameStatus.Playing || it.status == GameStatus.Open }) showMessages(false)
                }
            }
    }

    private companion object {
        const val HISTORY_PAGE = 50
        const val RUN_MS = 5 * 60_000L
        const val READ_DELAY_MS = 800L
        const val TICK_MS = 15_000L
    }
}

/** 0..7 from an account id: the same reader keeps the same colour in every group. */
internal fun slotOf(userId: String?): Int = userId?.let { id -> id.fold(0) { h, c -> (h * 31 + c.code) and 0x7fffffff } % 8 } ?: 0
