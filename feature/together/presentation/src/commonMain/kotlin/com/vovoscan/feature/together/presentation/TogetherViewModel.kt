package com.vovoscan.feature.together.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.together.domain.ChapterKind
import com.vovoscan.feature.together.domain.InviteCode
import com.vovoscan.feature.together.domain.Lines
import com.vovoscan.feature.together.domain.MutedReaders
import com.vovoscan.feature.together.domain.PagePoint
import com.vovoscan.feature.together.domain.Place
import com.vovoscan.feature.together.domain.Presence
import com.vovoscan.feature.together.domain.Rail
import com.vovoscan.feature.together.domain.Room
import com.vovoscan.feature.together.domain.Signal
import com.vovoscan.feature.together.domain.SignalKind
import com.vovoscan.feature.together.domain.Sticker
import com.vovoscan.feature.together.domain.ThisDevice
import com.vovoscan.feature.together.domain.TogetherAccess
import com.vovoscan.feature.together.domain.TogetherLanguage
import com.vovoscan.feature.together.domain.TogetherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TogetherUiState(
    /** The Together button shows: the `shared_reading` flag is on (a chapter of the server). */
    val visible: Boolean = false,
    val sheet: TogetherSheet? = null,
    val codeInput: String = "",
    val preview: PreviewUi? = null,
    val busy: Boolean = false,
    /** A calm line in the sheet ("This code doesn't open a circle."). */
    val error: String? = null,
    /** In a circle: the dock, the rail and the ticket. */
    val circle: CircleUi? = null,
    val stickers: List<StickerUi> = TogetherText.stickers,
    val bursts: List<BurstUi> = emptyList(),
    val balloons: List<BalloonUi> = emptyList(),
    val toast: ToastUi? = null,
    val memberMenu: MemberMenuUi? = null,
    val lineDraft: String = "",
    val lineError: String? = null,
    val logOpen: Boolean = false,
    /** The dock folded to a row of stamps. */
    val collapsed: Boolean = false,
    /** "You're offline: reading on your own". */
    val offline: String? = null,
    val command: TogetherCommand? = null,
    val reducedMotion: Boolean = false,
) {
    val buttonLabel: String get() = t("together.button")
    val title: String get() = t("together.title")
    val startLabel: String get() = t("together.start")
    val startHelp: String get() = t("together.startHelp")
    val joinLabel: String get() = t("together.joinWithCode")
    val codePlaceholder: String get() = t("together.codePlaceholder")
    val joinAction: String get() = t("together.join")
    val copyLabel: String get() = t("together.copyLink")
    val shareLabel: String get() = t("together.share")
    val ticketTitle: String get() = t("together.ticket")
    val lockLabel: String get() = t(if (circle?.ticket?.locked == true) "together.openDoor" else "together.closeDoor")
    val renewLabel: String get() = t("together.newCode")
    val endLabel: String get() = t("together.end")
    val leaveLabel: String get() = t("together.leave")
    val waitLabel: String get() = t("together.wait")
    val gatherLabel: String get() = t("together.gather")
    val linePlaceholder: String get() = t("together.linePlaceholder")
    val sendLabel: String get() = t("together.send")
    val logLabel: String get() = t("together.log")
    val dockLabel: String get() = t("together.dock")
    val closeLabel: String get() = t("common.close")

    /** The dock stays while a line is typed, even with the reader's bars hidden. */
    val typing: Boolean get() = lineDraft.isNotEmpty()

    /** Join is offered once the code has its 8 characters and the preview says nothing against it. */
    val canJoin: Boolean get() = !busy && preview != null && preview.blocked == null

    companion object {
        val hidden = TogetherUiState()
    }
}

sealed interface TogetherIntent {
    /** The reader is on screen / left it: the live link and heartbeats run only in between. */
    data object Start : TogetherIntent

    data object Stop : TogetherIntent

    data object OpenSheet : TogetherIntent

    data object CloseSheet : TogetherIntent

    data object StartCircle : TogetherIntent

    data object ShowJoin : TogetherIntent

    data class CodeChanged(
        val text: String,
    ) : TogetherIntent

    data object Join : TogetherIntent

    data object ShowTicket : TogetherIntent

    data object ToggleLock : TogetherIntent

    data object RenewCode : TogetherIntent

    data object EndCircle : TogetherIntent

    data object Leave : TogetherIntent

    data class SelectStamp(
        val userId: String,
    ) : TogetherIntent

    data object CloseMenu : TogetherIntent

    data class HandOver(
        val userId: String,
    ) : TogetherIntent

    data class Remove(
        val userId: String,
    ) : TogetherIntent

    data object Follow : TogetherIntent

    /** A reading gesture of the reader's own (drag, tap zone, key): they read on their own. */
    data object LetGo : TogetherIntent

    data object Gather : TogetherIntent

    data object Wait : TogetherIntent

    /** A sticker from the tray (at the reading line), or on a long-pressed point ([page] ≥ 0, [x], [y] in 0..1). */
    data class SendSticker(
        val key: String,
        val page: Int = -1,
        val x: Float = 0f,
        val y: Float = 0f,
    ) : TogetherIntent

    data class LineChanged(
        val text: String,
    ) : TogetherIntent

    data object SendLine : TogetherIntent

    data object ToggleLog : TogetherIntent

    data object ToggleCollapsed : TogetherIntent

    data class ToastTapped(
        val id: Long,
    ) : TogetherIntent

    data object DismissToast : TogetherIntent

    /** Tap on a rail dot: the reader goes to that reader's place. */
    data class JumpTo(
        val userId: String,
    ) : TogetherIntent

    /**
     * The reader's place (`ReaderUiState.place`): comic [page] + [fraction] (+ [panel], -1 unknown), book [section]
     * of [count] sections.
     */
    data class PlaceChanged(
        val book: Boolean,
        val page: Int,
        val fraction: Float,
        val panel: Int,
        val section: Int,
        val count: Int,
    ) : TogetherIntent

    /** The reader opens another chapter: a host moves the circle there first. */
    data class MovingOn(
        val chapterId: String,
    ) : TogetherIntent

    data class ReducedMotion(
        val on: Boolean,
    ) : TogetherIntent

    data class CommandHandled(
        val seq: Int,
    ) : TogetherIntent
}

/**
 * The circle next to a reader of one chapter of the server (shared-reading contract v1). The circle itself lives in
 * [TogetherSession]; this screen keeps it live while it is on screen, glides a follower to the host's place, sends this
 * reader's place (host: at most every 300 ms, trailing; on their own: every 1 s; following: never) and shows the
 * others' stickers, lines and calls once each.
 */
class TogetherViewModel(
    private val chapterId: String,
    private val repository: TogetherRepository,
    private val session: TogetherSession,
    private val access: TogetherAccess,
    private val language: TogetherLanguage,
    private val muted: MutedReaders,
    private val device: ThisDevice,
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TogetherUiState.hidden)
    val state: StateFlow<TogetherUiState> = mutableState.asStateFlow()

    private var enabled = false
    private var started = false
    private var liveJob: Job? = null
    private var beatJob: Job? = null
    private var tickJob: Job? = null
    private var sendJob: Job? = null
    private var previewJob: Job? = null
    private var toastJob: Job? = null
    private var seq = 0
    private var ids = 0L

    /** server time − device time, at the last snapshot. */
    private var offsetMs = 0L
    private var place: Place? = null
    private var sections = 0
    private var sentPlace: Place? = null
    private var lastSentAt = 0L
    private var mutedIds: Set<String> = emptySet()
    private val asking = mutableMapOf<String, Long>()
    private var announcedElsewhere: String? = null

    init {
        access
            .enabled()
            .distinctUntilChanged()
            .onEach { on ->
                enabled = on
                mutableState.update { if (on) it.copy(visible = true) else TogetherUiState.hidden }
                if (on) attach() else stopLink()
            }.launchIn(viewModelScope)
        session.room
            .onEach { room -> if (enabled) show(room) }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: TogetherIntent) {
        when (intent) {
            TogetherIntent.Start -> start()
            TogetherIntent.Stop -> {
                started = false
                stopLink()
            }
            TogetherIntent.OpenSheet ->
                mutableState.update {
                    it.copy(sheet = if (it.circle != null) TogetherSheet.Ticket else TogetherSheet.Choose, error = null)
                }
            TogetherIntent.CloseSheet -> mutableState.update { it.copy(sheet = null, error = null, preview = null, codeInput = "") }
            TogetherIntent.StartCircle -> startCircle()
            TogetherIntent.ShowJoin -> mutableState.update { it.copy(sheet = TogetherSheet.Join, error = null) }
            is TogetherIntent.CodeChanged -> codeChanged(intent.text)
            TogetherIntent.Join -> join()
            TogetherIntent.ShowTicket -> mutableState.update { it.copy(sheet = TogetherSheet.Ticket, error = null) }
            TogetherIntent.ToggleLock -> hostAction { room -> repository.lock(room.id, !room.locked) }
            TogetherIntent.RenewCode -> hostAction { room -> repository.renewCode(room.id) }
            TogetherIntent.EndCircle -> end(close = true)
            TogetherIntent.Leave -> end(close = false)
            is TogetherIntent.SelectStamp -> selectStamp(intent.userId)
            TogetherIntent.CloseMenu -> mutableState.update { it.copy(memberMenu = null) }
            is TogetherIntent.HandOver -> {
                mutableState.update { it.copy(memberMenu = null) }
                hostAction { room -> repository.handOver(room.id, intent.userId) }
            }
            is TogetherIntent.Remove -> {
                mutableState.update { it.copy(memberMenu = null) }
                val room = session.room.value ?: return
                viewModelScope.launch { report(repository.remove(room.id, intent.userId)) }
            }
            TogetherIntent.Follow -> setFollowing(true)
            TogetherIntent.LetGo -> if (session.room.value?.let { !it.isHost && following(it) } == true) setFollowing(false)
            TogetherIntent.Gather -> signal(SignalKind.Gather)
            TogetherIntent.Wait -> signal(SignalKind.Matte)
            is TogetherIntent.SendSticker -> sendSticker(intent)
            is TogetherIntent.LineChanged -> mutableState.update { it.copy(lineDraft = intent.text, lineError = null) }
            TogetherIntent.SendLine -> sendLine()
            TogetherIntent.ToggleLog -> mutableState.update { it.copy(logOpen = !it.logOpen) }
            TogetherIntent.ToggleCollapsed -> mutableState.update { it.copy(collapsed = !it.collapsed) }
            is TogetherIntent.ToastTapped -> toastTapped(intent.id)
            TogetherIntent.DismissToast -> mutableState.update { it.copy(toast = null) }
            is TogetherIntent.JumpTo -> jumpTo(intent.userId)
            is TogetherIntent.PlaceChanged -> placeChanged(intent)
            is TogetherIntent.MovingOn ->
                if (session.room.value
                        ?.chapter
                        ?.id == chapterId
                ) {
                    session.moveOn(intent.chapterId)
                }
            is TogetherIntent.ReducedMotion -> mutableState.update { it.copy(reducedMotion = intent.on) }
            is TogetherIntent.CommandHandled -> mutableState.update { if (it.command?.seq == intent.seq) it.copy(command = null) else it }
        }
    }

    // --- The circle -----------------------------------------------------------------------------------------------

    /** A circle already open on this chapter (kept from the last reader, or found on the server once per launch). */
    private fun attach() {
        viewModelScope.launch {
            mutedIds = runCatching { muted.ids() }.getOrDefault(emptySet())
            if (session.room.value != null || session.looked) return@launch
            session.looked = true
            val brief = (repository.rooms() as? AppResult.Ok)?.value?.firstOrNull { it.open && it.chapterId == chapterId } ?: return@launch
            (repository.room(brief.id) as? AppResult.Ok)?.value?.let { session.set(it) }
        }
    }

    private fun start() {
        started = true
        val room = session.room.value ?: return
        live(room)
    }

    private fun live(room: Room) {
        if (!started || !enabled || liveJob?.isActive == true) return
        liveJob =
            viewModelScope.launch {
                // Back on screen: a heartbeat first, then the live link.
                heartbeat(room.id)
                when (val result = repository.live(room.id) { next -> session.room.value?.id == next.id && keep(next) }) {
                    is AppResult.Ok -> Unit
                    is AppResult.Err -> gone(result.error)
                }
            }
        beatJob?.cancel()
        beatJob =
            viewModelScope.launch {
                while (true) {
                    delay(Presence.HEARTBEAT_MS)
                    session.room.value?.let { heartbeat(it.id) }
                }
            }
        tickJob?.cancel()
        // Who is away changes with time alone: the stamps are drawn again every 5 s.
        tickJob =
            viewModelScope.launch {
                while (true) {
                    delay(TICK_MS)
                    session.room.value?.let { render(it) }
                }
            }
    }

    private fun stopLink() {
        liveJob?.cancel()
        beatJob?.cancel()
        tickJob?.cancel()
        liveJob = null
    }

    private suspend fun heartbeat(id: String) {
        when (val result = repository.me(id)) {
            is AppResult.Ok -> mutableState.update { it.copy(offline = null) }
            is AppResult.Err -> if (result.error == AppError.Network) mutableState.update { it.copy(offline = t("together.offline")) }
        }
    }

    /** A snapshot from the live link: kept unless the circle closed. */
    private fun keep(room: Room): Boolean {
        mutableState.update { it.copy(offline = null) }
        if (!room.open) {
            gone(null)
            return false
        }
        session.set(room)
        return true
    }

    /** The circle is over for this reader: closed, removed, or no longer reachable. */
    private fun gone(error: AppError?) {
        if (error == AppError.Network) {
            mutableState.update { it.copy(offline = t("together.offline")) }
            return
        }
        val code = (error as? AppError.Rejected)?.code
        val line =
            when (code) {
                "ROOM_REMOVED" -> t("together.removed")
                "FEATURE_UNAVAILABLE", "FEATURE_DISABLED" -> null
                else -> t("together.closed")
            }
        if (code == "FEATURE_UNAVAILABLE" || code == "FEATURE_DISABLED") enabled = false
        session.clear()
        stopLink()
        mutableState.update {
            if (enabled) {
                it.copy(
                    circle = null,
                    bursts = emptyList(),
                    balloons = emptyList(),
                )
            } else {
                TogetherUiState.hidden
            }
        }
        line?.let { toast(it) }
    }

    private fun show(room: Room?) {
        if (room == null) {
            mutableState.update { it.copy(circle = null, memberMenu = null) }
            return
        }
        offsetMs = room.serverTimeMillis - clock.nowMillis()
        if (started && liveJob?.isActive != true) live(room)
        signals(room)
        follow(room)
        render(room)
    }

    private fun following(room: Room): Boolean = !room.isHost && (session.following ?: room.following)

    /** A follower goes where the host is: to another chapter, or to a newer place in this one. */
    private fun follow(room: Room) {
        if (room.chapter.id != chapterId) {
            if (following(room)) {
                if (session.openedChapter != room.chapter.id) {
                    session.openedChapter = room.chapter.id
                    command { TogetherCommand.OpenChapter(it, room.chapter.id, room.chapter.titleRef) }
                }
            } else if (announcedElsewhere != room.chapter.id && !room.isHost) {
                announcedElsewhere = room.chapter.id
                val name = room.host?.name.orEmpty()
                val n =
                    room.chapter.number
                        ?.let(TogetherText::number)
                        .orEmpty()
                toast(t("together.movedOn", "name" to name, "n" to n), ToastAction.Go, t("together.go"), room.chapter.id)
            }
            return
        }
        if (!following(room)) return
        val target = room.place ?: return
        if (room.placeSeq <= session.appliedSeq) return
        session.appliedSeq = room.placeSeq
        goTo(target)
    }

    private fun goTo(target: Place) {
        val animated = !state.value.reducedMotion
        command {
            when (target) {
                is Place.Comic -> TogetherCommand.GoToPlace(it, target.page, target.fraction.toFloat(), 0, animated)
                is Place.Book -> TogetherCommand.GoToPlace(it, 0, 0f, target.section, animated)
            }
        }
    }

    private fun render(room: Room) {
        val now = clock.nowMillis()
        val following = following(room)
        val here =
            room.members
                .filter { Presence.here(it.lastSeenAtMillis, now, offsetMs) }
                .map { it.userId }
                .toSet()
        asking.entries.removeAll { now - it.value > ASKING_MS }
        val host = room.host
        val stamps =
            room.members.map { m ->
                val away = m.userId !in here
                StampUi(
                    userId = m.userId,
                    initial = TogetherText.initial(m.name),
                    name = m.name,
                    slot = m.slot,
                    host = m.userId == room.hostId,
                    away = away,
                    me = m.userId == room.myId,
                    asking = m.userId in asking,
                    a11y =
                        listOfNotNull(
                            m.name,
                            t("together.host").takeIf { m.userId == room.hostId },
                            t("together.away").takeIf { away },
                        ).joinToString(", "),
                )
            }
        val others = room.members.filter { it.userId != room.hostId }
        val onOwn = others.count { !it.following }
        val count = if (room.chapter.kind == ChapterKind.Book) sections else room.chapter.pageCount
        val rail =
            room.members.mapNotNull { m ->
                val at =
                    when {
                        m.userId == room.myId -> place ?: m.place
                        m.userId == room.hostId -> room.place ?: m.place
                        m.following -> room.place
                        else -> m.place
                    }
                Rail.share(at, count)?.let { RailDot(m.userId, m.slot, it.toFloat(), m.userId == room.hostId, m.userId == room.myId) }
            }
        val slots = room.members.associate { it.userId to it.slot }
        val log =
            room.signals
                .filter { it.userId == null || it.userId !in mutedIds }
                .mapNotNull { TogetherText.log(it, it.userId?.let(slots::get), room) }
        val circle =
            CircleUi(
                stamps = stamps,
                isHost = room.isHost,
                following = following,
                followLabel =
                    when {
                        room.isHost -> null
                        following -> t("together.following", "name" to host?.name.orEmpty())
                        else -> t("together.onYourOwn")
                    },
                hostSummary =
                    if (room.isHost && others.isNotEmpty()) {
                        t("together.summary", "following" to others.size - onOwn, "own" to onOwn)
                    } else {
                        null
                    },
                canGather = room.isHost && onOwn > 0,
                rail = rail,
                log = log,
                ticket =
                    TicketUi(
                        code = room.prettyCode,
                        admission = t("together.admission", "count" to room.members.size, "capacity" to room.capacity),
                        chapterLine = TogetherText.chapterLine(room.chapter),
                        closesLine = TogetherText.closes(room.closesAtMillis),
                        appLink = InviteCode.appLink(room.code),
                        webPath = room.invitePath.trimStart('/'),
                        locked = room.locked,
                        isHost = room.isHost,
                    ),
                elsewhere =
                    if (room.chapter.id != chapterId) {
                        t("together.elsewhere", "n" to (room.chapter.number?.let(TogetherText::number) ?: ""))
                    } else {
                        null
                    },
            )
        mutableState.update { it.copy(circle = circle) }
    }

    // --- Signals --------------------------------------------------------------------------------------------------

    /** Each signal once; on (re)connection only those younger than 10 s are shown live (older ones are in the log). */
    private fun signals(room: Room) {
        for (signal in room.signals) {
            if (session.shown(signal.id)) continue
            session.markShown(signal.id)
            if (room.serverTimeMillis - signal.createdAtMillis > LIVE_MS) continue
            if (signal.userId != null && signal.userId in mutedIds) continue
            live(signal, room)
        }
    }

    private fun live(
        signal: Signal,
        room: Room,
    ) {
        val mine = signal.userId == room.myId
        val slot = room.members.firstOrNull { it.userId == signal.userId }?.slot ?: 0
        val name = signal.name.orEmpty()
        when (signal.kind) {
            SignalKind.Sticker -> if (!mine) signal.sticker?.let { burst(it, slot, signal.at) }
            SignalKind.Say -> if (!mine) signal.text?.let { balloon(signal.userId.orEmpty(), slot, name, it) }
            SignalKind.Matte ->
                if (room.isHost && !mine) {
                    signal.userId?.let { asking[it] = clock.nowMillis() }
                    toast(t("together.waitFor", "name" to name))
                }
            SignalKind.Gather ->
                if (!mine && !following(room)) toast(t("together.comeHere", "name" to name), ToastAction.Follow, t("together.follow"))
            SignalKind.Join -> if (!mine) toast(t("together.joined", "name" to name))
            SignalKind.Leave -> if (!mine) toast(t("together.left", "name" to name))
            SignalKind.Host -> toast(if (mine) t("together.youHost") else t("together.newHost", "name" to name))
            SignalKind.Chapter, SignalKind.Removed, SignalKind.Closed -> Unit
        }
    }

    private fun burst(
        sticker: Sticker,
        slot: Int,
        at: PagePoint?,
    ) {
        val id = ++ids
        mutableState.update { current ->
            val bursts =
                if (current.bursts.size >= MAX_BURSTS) {
                    // More coalesce into a "×n" on the last one.
                    val last = current.bursts.last()
                    current.bursts.dropLast(1) + last.copy(id = id, count = last.count + 1)
                } else {
                    current.bursts +
                        BurstUi(id, sticker.glyph, slot, at?.page ?: -1, (at?.x ?: 0.0).toFloat(), (at?.y ?: 0.0).toFloat(), 1)
                }
            current.copy(bursts = bursts)
        }
        viewModelScope.launch {
            delay(BURST_MS)
            mutableState.update { current -> current.copy(bursts = current.bursts.filterNot { it.id == id }) }
        }
    }

    private fun balloon(
        userId: String,
        slot: Int,
        name: String,
        text: String,
    ) {
        val id = ++ids
        mutableState.update { current ->
            current.copy(
                balloons =
                    current.balloons.filterNot { it.userId == userId } + BalloonUi(id, userId, slot, name, text),
            )
        }
        viewModelScope.launch {
            delay(BALLOON_MS)
            mutableState.update { current -> current.copy(balloons = current.balloons.filterNot { it.id == id }) }
        }
    }

    private fun toast(
        text: String,
        action: ToastAction? = null,
        actionLabel: String? = null,
        chapterId: String? = null,
    ) {
        val id = ++ids
        mutableState.update { it.copy(toast = ToastUi(id, text, action, actionLabel, chapterId)) }
        toastJob?.cancel()
        toastJob =
            viewModelScope.launch {
                delay(if (action != null) TOAST_ACTION_MS else TOAST_MS)
                mutableState.update { if (it.toast?.id == id) it.copy(toast = null) else it }
            }
    }

    private fun toastTapped(id: Long) {
        val toast = state.value.toast?.takeIf { it.id == id } ?: return
        mutableState.update { it.copy(toast = null) }
        when (toast.action) {
            ToastAction.Follow -> setFollowing(true)
            ToastAction.Go -> {
                val room = session.room.value ?: return
                val target = toast.chapterId ?: return
                command { TogetherCommand.OpenChapter(it, target, room.chapter.titleRef) }
            }
            null -> Unit
        }
    }

    // --- This reader's own actions --------------------------------------------------------------------------------

    private fun startCircle() {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = repository.open(chapterId, place, language.current(), device.device)
            when (result) {
                is AppResult.Ok -> {
                    session.set(result.value)
                    mutableState.update { it.copy(busy = false, sheet = TogetherSheet.Ticket) }
                }
                is AppResult.Err -> mutableState.update { it.copy(busy = false, error = message(result.error)) }
            }
        }
    }

    private fun codeChanged(text: String) {
        mutableState.update { it.copy(codeInput = text, preview = null, error = null) }
        previewJob?.cancel()
        val code = InviteCode.fromLink(text) ?: return
        previewJob =
            viewModelScope.launch {
                when (val result = repository.preview(code)) {
                    is AppResult.Ok -> mutableState.update { it.copy(preview = TogetherText.preview(code, result.value)) }
                    is AppResult.Err -> mutableState.update { it.copy(error = message(result.error)) }
                }
            }
    }

    private fun join() {
        val code = InviteCode.fromLink(state.value.codeInput) ?: return
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.join(code, language.current(), device.device)) {
                is AppResult.Ok -> {
                    val room = result.value
                    session.set(room)
                    mutableState.update { it.copy(busy = false, sheet = null, codeInput = "", preview = null) }
                    // Another chapter: the reader opens it (already following); this one: the host's place.
                    if (room.chapter.id != chapterId) {
                        session.openedChapter = room.chapter.id
                        command { TogetherCommand.OpenChapter(it, room.chapter.id, room.chapter.titleRef) }
                    }
                }
                is AppResult.Err -> mutableState.update { it.copy(busy = false, error = message(result.error)) }
            }
        }
    }

    private fun end(close: Boolean) {
        val room = session.room.value ?: return
        session.clear()
        stopLink()
        mutableState.update { it.copy(sheet = null, circle = null, bursts = emptyList(), balloons = emptyList(), memberMenu = null) }
        viewModelScope.launch { if (close && room.isHost) repository.close(room.id) else repository.leave(room.id) }
    }

    private fun hostAction(call: suspend (Room) -> AppResult<Room>) {
        val room = session.room.value?.takeIf { it.isHost } ?: return
        viewModelScope.launch {
            when (val result = call(room)) {
                is AppResult.Ok -> session.set(result.value)
                is AppResult.Err -> report(result)
            }
        }
    }

    private fun selectStamp(userId: String) {
        val room = session.room.value ?: return
        val member = room.members.firstOrNull { it.userId == userId } ?: return
        if (!room.isHost || userId == room.myId) {
            jumpTo(userId)
            return
        }
        mutableState.update {
            it.copy(
                memberMenu =
                    MemberMenuUi(
                        userId = userId,
                        name = member.name,
                        handOverLabel = t("together.handOver", "name" to member.name),
                        removeLabel = t("together.remove", "name" to member.name),
                    ),
            )
        }
    }

    private fun setFollowing(on: Boolean) {
        val room = session.room.value ?: return
        if (room.isHost) return
        session.following = on
        if (on) {
            // Back to the host at once, whatever was applied before.
            session.appliedSeq = room.placeSeq
            room.place?.takeIf { room.chapter.id == chapterId }?.let(::goTo)
        }
        render(room)
        viewModelScope.launch {
            val result = repository.me(room.id, following = on)
            if (result is AppResult.Err) {
                report(result)
                return@launch
            }
            // On their own, their place goes out at once (the rail shows it).
            if (!on) place?.let { sendPlace(room.id, it) }
        }
    }

    private fun jumpTo(userId: String) {
        val room = session.room.value ?: return
        val member = room.members.firstOrNull { it.userId == userId } ?: return
        if (userId == room.myId) return
        val target = (if (userId == room.hostId || member.following) room.place else member.place) ?: return
        if (room.chapter.id != chapterId) return
        if (following(room) && userId != room.hostId) setFollowing(false)
        goTo(target)
    }

    private fun signal(kind: SignalKind) {
        val room = session.room.value ?: return
        viewModelScope.launch {
            val result = repository.signal(room.id, kind)
            if (result is AppResult.Ok) session.markShown(result.value.id) else report(result)
            if (result is AppResult.Ok && kind == SignalKind.Matte) toast(t("together.waitSent"))
        }
    }

    private fun sendSticker(intent: TogetherIntent.SendSticker) {
        val room = session.room.value ?: return
        val sticker = Sticker.of(intent.key) ?: return
        val me = room.members.firstOrNull { it.userId == room.myId }
        val at =
            if (intent.page >=
                0
            ) {
                PagePoint(intent.page, intent.x.toDouble().coerceIn(0.0, 1.0), intent.y.toDouble().coerceIn(0.0, 1.0))
            } else {
                null
            }
        // Shown here at once; the server's copy is then known as shown.
        burst(sticker, me?.slot ?: 0, at)
        viewModelScope.launch {
            val result = repository.signal(room.id, SignalKind.Sticker, sticker = sticker, at = at)
            if (result is AppResult.Ok) session.markShown(result.value.id) else report(result)
        }
    }

    private fun sendLine() {
        val room = session.room.value ?: return
        when (val check = Lines.check(state.value.lineDraft)) {
            Lines.Check.Empty -> return
            Lines.Check.TooLong -> mutableState.update { it.copy(lineError = t("together.tooLong", "max" to Lines.MAX)) }
            Lines.Check.Link -> mutableState.update { it.copy(lineError = t("together.noLinks")) }
            is Lines.Check.Ok -> {
                val me = room.members.firstOrNull { it.userId == room.myId }
                mutableState.update { it.copy(lineDraft = "", lineError = null) }
                balloon(room.myId, me?.slot ?: 0, me?.name.orEmpty(), check.text)
                viewModelScope.launch {
                    when (val result = repository.signal(room.id, SignalKind.Say, text = check.text)) {
                        is AppResult.Ok -> session.markShown(result.value.id)
                        is AppResult.Err -> mutableState.update { it.copy(lineError = message(result.error), lineDraft = check.text) }
                    }
                }
            }
        }
    }

    // --- Places ---------------------------------------------------------------------------------------------------

    private fun placeChanged(intent: TogetherIntent.PlaceChanged) {
        val next =
            if (intent.book) {
                sections = intent.count
                Place.Book(intent.section.coerceAtLeast(0))
            } else {
                Place.comic(intent.page, intent.fraction.toDouble(), intent.panel.takeIf { it >= 0 })
            }
        if (next == place) return
        place = next
        val room = session.room.value?.takeIf { it.chapter.id == chapterId } ?: return
        // This reader's own dot moves with them.
        render(room)
        // A follower is where the host is: heartbeats only.
        if (following(room)) return
        scheduleSend(room.id, if (room.isHost) HOST_SEND_MS else OWN_SEND_MS)
    }

    /** At most one place per [every] ms, trailing: the last place always goes out. */
    private fun scheduleSend(
        roomId: String,
        every: Long,
    ) {
        if (sendJob?.isActive == true) return
        sendJob =
            viewModelScope.launch {
                val wait = (lastSentAt + every - clock.nowMillis()).coerceIn(0, every)
                delay(wait)
                place?.let { sendPlace(roomId, it) }
            }
    }

    private suspend fun sendPlace(
        roomId: String,
        at: Place,
    ) {
        if (at == sentPlace) return
        lastSentAt = clock.nowMillis()
        sentPlace = at
        val result = repository.me(roomId, place = at)
        if (result is AppResult.Err) {
            sentPlace = null
            if (result.error == AppError.Network) mutableState.update { it.copy(offline = t("together.offline")) }
        }
    }

    // --- Helpers --------------------------------------------------------------------------------------------------

    private fun command(make: (Int) -> TogetherCommand) {
        seq += 1
        val next = make(seq)
        mutableState.update { it.copy(command = next) }
    }

    private fun report(result: AppResult<*>) {
        val error = (result as? AppResult.Err)?.error ?: return
        toast(message(error))
    }

    private fun message(error: AppError): String =
        when {
            error == AppError.Network -> t("together.offline")
            error is AppError.Rejected && error.code == "ROOM_NOT_FOUND" -> t("together.noSuchCircle")
            else -> error.userMessage()
        }

    private companion object {
        const val LIVE_MS = 10_000L
        const val TICK_MS = 5_000L
        const val ASKING_MS = 6_000L
        const val MAX_BURSTS = 6
        const val BURST_MS = 1_600L
        const val BALLOON_MS = 5_000L
        const val TOAST_MS = 4_000L
        const val TOAST_ACTION_MS = 8_000L
        const val HOST_SEND_MS = 300L
        const val OWN_SEND_MS = 1_000L
    }
}
