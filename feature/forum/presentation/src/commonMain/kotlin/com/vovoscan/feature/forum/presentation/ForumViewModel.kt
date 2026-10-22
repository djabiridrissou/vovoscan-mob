package com.vovoscan.feature.forum.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Clock
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.forum.domain.Anchor
import com.vovoscan.feature.forum.domain.Anchors
import com.vovoscan.feature.forum.domain.Draft
import com.vovoscan.feature.forum.domain.ForumAccess
import com.vovoscan.feature.forum.domain.ForumRepository
import com.vovoscan.feature.forum.domain.ForumRules
import com.vovoscan.feature.forum.domain.Margin
import com.vovoscan.feature.forum.domain.Order
import com.vovoscan.feature.forum.domain.Post
import com.vovoscan.feature.forum.domain.PostKind
import com.vovoscan.feature.forum.domain.PostStatus
import com.vovoscan.feature.forum.domain.PostTranslation
import com.vovoscan.feature.forum.domain.ReportReason
import com.vovoscan.feature.forum.domain.Salon
import com.vovoscan.feature.forum.domain.Sticker
import com.vovoscan.feature.forum.domain.TranslationStatus
import com.vovoscan.feature.forum.domain.VerdictState
import com.vovoscan.feature.forum.domain.WritingLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MarginTab { Chapter, Theories, Salon }

/** How the next note is placed on a page: not at all, a point, a frame, or a stroke drawn on a panel. */
enum class MarkTool { None, Point, Frame, Draw }

enum class PinShape { Point, Frame, Stroke }

/**
 * A note pinned on a page: its sticker's glyph, "？" while it is a spoiler for this reader, "✎" for words only. Where, in
 * fractions of the page: the point ([x], [y]), the frame ([x], [y], [w], [h]) or the stroke ([path]: x, y, x, y…; the
 * label sits at its first point).
 */
data class PinUi(
    val postId: String,
    val pageIndex: Int,
    val shape: PinShape,
    val x: Double,
    val y: Double,
    val w: Double = 0.0,
    val h: Double = 0.0,
    val path: List<Double> = emptyList(),
    val label: String,
    val masked: Boolean,
    /** For screen readers: "Note by Aya: …" or "A note further in the story…". */
    val a11y: String,
)

/** A choice drawn as a chip: tabs, orders, tools, stickers, report reasons, salon weeks. */
data class TabUi(
    val tab: MarginTab,
    val label: String,
)

data class OrderUi(
    val order: Order,
    val label: String,
)

data class ToolUi(
    val tool: MarkTool,
    val label: String,
)

data class StickerUi(
    val key: String,
    val glyph: String,
)

data class ReasonUi(
    val reason: ReportReason,
    val label: String,
)

data class WeekUi(
    val key: String,
    val label: String,
)

data class ReactionUi(
    val key: String,
    val glyph: String,
    /** Null when nobody reacted so (the picker still offers it). */
    val count: Int?,
    val mine: Boolean,
)

data class VerdictUi(
    /** "Open" / "It happened" / "It didn't" / "Readers disagree". */
    val state: String,
    /** "12 say it happened ✦ 3 say it didn't", or null below 3 readers. */
    val counts: String?,
    val canVote: Boolean,
    /** The reader's own vote: true = it happened, false = it didn't. */
    val mine: Boolean?,
)

data class PostUi(
    val id: String,
    val kind: PostKind,
    val authorId: String,
    val author: String,
    /** "Creator" / "Staff", frozen at posting. */
    val badge: String?,
    /** "now", "5 min", "3 h", "2 d". */
    val age: String,
    /** Null while masked, or once taken back. */
    val body: String?,
    /** "Spoiler: chapter 12. Tap to reveal.", while masked. */
    val maskedLine: String?,
    val sticker: String?,
    /** "Page 4" for a note pinned on a page. */
    val place: String?,
    /** "Held for review", "Hidden", "Removed", "Deleted", "Edited": null when visible and untouched. */
    val note: String?,
    val reactions: List<ReactionUi>,
    val replies: String?,
    val canReply: Boolean,
    val mine: Boolean,
    val editable: Boolean,
    /** "Translate" is offered: written in another language than the reader's, with words to translate. */
    val canTranslate: Boolean,
    val translation: String?,
    val translating: Boolean,
    val verdict: VerdictUi?,
    val highlighted: Boolean,
)

data class ComposerUi(
    val kind: PostKind = PostKind.Note,
    val tool: MarkTool = MarkTool.None,
    val pageIndex: Int? = null,
    val anchor: Anchor? = null,
    val body: String = "",
    val sticker: String? = null,
    val spoiler: Boolean = false,
    /** Set while a post of the reader's own is being edited (15 minutes after posting). */
    val editingId: String? = null,
    val sending: Boolean = false,
    val problem: String? = null,
) {
    val max: Int get() = ForumRules.max(kind)

    /** "120 / 500". */
    val counter: String get() = "${body.length} / $max"

    val hasMark: Boolean get() = sticker != null || anchor is Anchor.Stroke

    val canSend: Boolean
        get() = !sending && ForumRules.problem(kind, body, hasMark && editingId == null && kind == PostKind.Note) == null

    val placeholder: String
        get() =
            when (kind) {
                PostKind.Note -> t("forum.notePlaceholder")
                PostKind.Theory -> t("forum.theoryPlaceholder")
                PostKind.Salon -> t("forum.salonPlaceholder")
            }

    /** What the chosen tool waits for: "Tap the page where it happens." */
    val toolHint: String?
        get() =
            when {
                anchor != null -> t("forum.marked", "page" to (pageIndex ?: 0) + 1)
                tool == MarkTool.Point -> t("forum.toolPointHint")
                tool == MarkTool.Frame -> t("forum.toolFrameHint")
                tool == MarkTool.Draw -> t("forum.toolDrawHint")
                else -> null
            }
}

data class ThreadUi(
    val post: PostUi,
    val replies: List<PostUi>,
    val draft: String = "",
    val sending: Boolean = false,
) {
    val counter: String get() = "${draft.length} / ${ForumRules.REPLY_MAX}"
    val canSend: Boolean get() = !sending && ForumRules.problem(PostKind.Note, draft, hasMark = false, reply = true) == null
}

data class SalonUi(
    /** "Week 43" (ISO week). */
    val week: String,
    val weekKey: String,
    val current: Boolean,
    /** The 8 latest weeks: "Week 43 ✦ 12". */
    val weeks: List<WeekUi>,
    val posts: List<PostUi>,
)

/** Under the last page: "ここまで読んだ Read this far", how many readers got here, the best notes. */
data class EndCardUi(
    val title: String,
    val readers: String,
    val best: List<PostUi>,
)

data class MarginUiState(
    /** The `forum` flag: off, nothing of the margin shows. */
    val visible: Boolean = false,
    /** The margin is open: pins over the pages and the sheet. */
    val open: Boolean = false,
    val tab: MarginTab = MarginTab.Chapter,
    val order: Order = Order.Best,
    val loading: Boolean = false,
    /** Showing what was saved on this device (offline). */
    val offline: Boolean = false,
    val error: String? = null,
    val count: Int = 0,
    val pins: Map<Int, List<PinUi>> = emptyMap(),
    val notes: List<PostUi> = emptyList(),
    val theories: List<PostUi> = emptyList(),
    val salon: SalonUi? = null,
    val composer: ComposerUi = ComposerUi(),
    val thread: ThreadUi? = null,
    val endCard: EndCardUi? = null,
    /** The post being reported: the sheet with the reasons shows. */
    val reporting: String? = null,
    /** "Thanks. We'll look at it.", "Aya is muted.". */
    val toast: String? = null,
) {
    /** "余白 Margin". */
    val switchLabel: String get() = t("forum.switch")

    /** "余白 Margin ✦ 12" for screen readers and the bar's badge. */
    val countLabel: String? get() = count.takeIf { it > 0 }?.toString()

    val tabs: List<TabUi>
        get() =
            listOf(
                TabUi(MarginTab.Chapter, t("forum.tabChapter")),
                TabUi(MarginTab.Theories, t("forum.tabTheories")),
                TabUi(MarginTab.Salon, t("forum.tabSalon")),
            )

    val orders: List<OrderUi>
        get() =
            listOf(
                OrderUi(Order.Best, t("forum.orderBest")),
                OrderUi(Order.New, t("forum.orderNew")),
                OrderUi(Order.Old, t("forum.orderOld")),
            )

    val stickers: List<StickerUi> get() = Sticker.entries.map { StickerUi(it.key, it.glyph) }

    val tools: List<ToolUi>
        get() =
            listOf(
                ToolUi(MarkTool.Point, t("forum.toolPoint")),
                ToolUi(MarkTool.Frame, t("forum.toolFrame")),
                ToolUi(MarkTool.Draw, t("forum.toolDraw")),
            )

    val reportReasons: List<ReasonUi>
        get() = ReportReason.entries.map { ReasonUi(it, t("forum.report.${it.wire}")) }

    /** The pins on page [pageIndex] while the margin is open (none when it is closed). */
    fun pinsOn(pageIndex: Int): List<PinUi> = if (open) pins[pageIndex].orEmpty() else emptyList()

    /** The pin being placed (the composer's mark), drawn on its page in the same way. */
    fun draftOn(pageIndex: Int): PinUi? {
        val anchor = composer.anchor ?: return null
        if (!open || composer.pageIndex != pageIndex) return null
        return pinOf(
            "draft",
            pageIndex,
            anchor,
            composer.sticker?.let { k -> Sticker.of(k)?.glyph } ?: "✎",
            masked = false,
            a11y =
                t(
                    "forum.marked",
                    "page" to pageIndex + 1,
                ),
        )
    }

    /** A tool is chosen: the page takes the finger (a tap, a frame, a stroke) instead of turning. */
    val placing: Boolean get() = open && composer.tool != MarkTool.None

    val clearMarkLabel: String get() = t("forum.clearMark")
    val sendLabel: String get() = if (composer.editingId != null) t("forum.saveEdit") else t("forum.send")
    val spoilerLabel: String get() = t("forum.spoiler")
    val stickerLabel: String get() = t("forum.sticker")
    val offlineLine: String? get() = if (offline) t("forum.offline") else null
    val reportTitle: String get() = t("forum.reportTitle")
    val repliesTitle: String get() = t("forum.repliesTitle")
    val replyPlaceholder: String get() = t("forum.replyPlaceholder")
    val leaveMarkLabel: String get() = t("forum.leaveMark")

    val empty: String?
        get() =
            when {
                loading -> null
                tab == MarginTab.Chapter && notes.isEmpty() -> t("forum.emptyChapter")
                tab == MarginTab.Theories && theories.isEmpty() -> t("forum.emptyTheories")
                tab == MarginTab.Salon && salon?.posts?.isEmpty() == true -> t("forum.emptySalon")
                else -> null
            }

    /** Notes are written in the chapter tab, theories in theirs, the salon only in the current week. */
    val canWrite: Boolean get() = !offline && (tab != MarginTab.Salon || salon?.current == true)
}

internal fun pinOf(
    id: String,
    pageIndex: Int,
    anchor: Anchor,
    label: String,
    masked: Boolean,
    a11y: String,
): PinUi =
    when (anchor) {
        is Anchor.Point -> PinUi(id, pageIndex, PinShape.Point, anchor.x, anchor.y, label = label, masked = masked, a11y = a11y)
        is Anchor.Frame ->
            PinUi(
                id,
                pageIndex,
                PinShape.Frame,
                anchor.x,
                anchor.y,
                anchor.w,
                anchor.h,
                label = label,
                masked = masked,
                a11y = a11y,
            )
        is Anchor.Stroke -> {
            val (x, y) = anchor.points.first()
            PinUi(
                id,
                pageIndex,
                PinShape.Stroke,
                x,
                y,
                path =
                    anchor.points.flatMap {
                        listOf(it.first, it.second)
                    },
                label = label,
                masked = masked,
                a11y = a11y,
            )
        }
    }

sealed interface MarginIntent {
    data object Toggle : MarginIntent

    data object Close : MarginIntent

    data object Refresh : MarginIntent

    data class SelectTab(
        val tab: MarginTab,
    ) : MarginIntent

    data class SetOrder(
        val order: Order,
    ) : MarginIntent

    data class ChooseTool(
        val tool: MarkTool,
    ) : MarginIntent

    /** A tap on page [pageIndex] at ([x], [y]), fractions of the page. */
    data class PlacePoint(
        val pageIndex: Int,
        val x: Double,
        val y: Double,
    ) : MarginIntent

    data class PlaceFrame(
        val pageIndex: Int,
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
    ) : MarginIntent

    /** A stroke: x, y, x, y… in fractions of the page (thinned to 64 points). */
    data class PlaceStroke(
        val pageIndex: Int,
        val xy: List<Double>,
    ) : MarginIntent

    data object ClearMark : MarginIntent

    data class Type(
        val text: String,
    ) : MarginIntent

    /** Null takes the sticker off. */
    data class PickSticker(
        val key: String?,
    ) : MarginIntent

    data class SetSpoiler(
        val on: Boolean,
    ) : MarginIntent

    data object Send : MarginIntent

    data class StartEdit(
        val postId: String,
    ) : MarginIntent

    data object CancelEdit : MarginIntent

    data class Delete(
        val postId: String,
    ) : MarginIntent

    /** The same sticker again takes the reaction back. */
    data class React(
        val postId: String,
        val key: String,
    ) : MarginIntent

    /** "It happened" (true) / "It didn't" (false); the same again takes the vote back. */
    data class Vote(
        val postId: String,
        val happened: Boolean,
    ) : MarginIntent

    data class Reveal(
        val postId: String,
    ) : MarginIntent

    data class PinTapped(
        val postId: String,
    ) : MarginIntent

    data class OpenThread(
        val postId: String,
    ) : MarginIntent

    data class TypeReply(
        val text: String,
    ) : MarginIntent

    data object SendReply : MarginIntent

    data object CloseThread : MarginIntent

    data class Translate(
        val postId: String,
    ) : MarginIntent

    data class Report(
        val postId: String,
    ) : MarginIntent

    data class SendReport(
        val reason: ReportReason,
    ) : MarginIntent

    data object CancelReport : MarginIntent

    data class Mute(
        val authorId: String,
    ) : MarginIntent

    data class SelectWeek(
        val week: String,
    ) : MarginIntent

    data object ToastShown : MarginIntent
}

/**
 * The margin of one chapter in the reader (the app puts it next to the reader: the reader does not know this feature).
 * Online only; the last margin read shows offline. Every list comes from the server already masked for this reader.
 */
class ForumViewModel(
    private val chapterId: String,
    private val repository: ForumRepository,
    private val access: ForumAccess = ForumAccess.Off,
    private val writing: WritingLanguage = WritingLanguage { "en" },
    private val clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MarginUiState())
    val state: StateFlow<MarginUiState> = mutableState.asStateFlow()

    /** Every post shown, by id: the source of truth the lists are drawn from (a reaction or a reveal updates it). */
    private val posts = mutableMapOf<String, Post>()
    private var margin: Margin? = null
    private var salon: Salon? = null
    private var theoryIds: List<String> = emptyList()
    private var thread: Pair<String, List<String>>? = null
    private val translations = mutableMapOf<String, PostTranslation>()
    private val translating = mutableSetOf<String>()
    private val muted = mutableSetOf<String>()
    private var highlighted: String? = null
    private var language: String = ""
    private var loaded = false
    private var pollJob: Job? = null

    init {
        access
            .enabled()
            .distinctUntilChanged()
            .onEach { on ->
                mutableState.update { it.copy(visible = on) }
                if (on && !loaded) {
                    loaded = true
                    language = writing.current()
                    // What was read here last shows at once, the server's answer replaces it.
                    repository.observeMargin(chapterId).first()?.let { cached ->
                        if (margin == null) show(cached, offline = false)
                    }
                    load()
                }
            }.launchIn(viewModelScope)
    }

    fun onIntent(intent: MarginIntent) {
        when (intent) {
            MarginIntent.Toggle -> mutableState.update { it.copy(open = !it.open, error = null) }
            MarginIntent.Close -> mutableState.update { it.copy(open = false, composer = it.composer.copy(tool = MarkTool.None)) }
            MarginIntent.Refresh -> refreshTab()
            is MarginIntent.SelectTab -> selectTab(intent.tab)
            is MarginIntent.SetOrder -> {
                mutableState.update { it.copy(order = intent.order) }
                refreshTab()
            }
            is MarginIntent.ChooseTool ->
                composer { c -> c.copy(tool = if (c.tool == intent.tool) MarkTool.None else intent.tool, kind = PostKind.Note) }
            is MarginIntent.PlacePoint ->
                mark(intent.pageIndex, Anchors.point(intent.x, intent.y))
            is MarginIntent.PlaceFrame ->
                mark(intent.pageIndex, Anchors.frame(intent.x0, intent.y0, intent.x1, intent.y1))
            is MarginIntent.PlaceStroke ->
                Anchors
                    .stroke(
                        intent.xy
                            .chunked(2)
                            .filter { it.size == 2 }
                            .map { it[0] to it[1] },
                    )?.let { mark(intent.pageIndex, it) }
            MarginIntent.ClearMark -> composer { it.copy(anchor = null, pageIndex = null) }
            is MarginIntent.Type -> composer { it.copy(body = intent.text, problem = null) }
            is MarginIntent.PickSticker -> composer { it.copy(sticker = intent.key?.let { k -> Sticker.of(k)?.key }) }
            is MarginIntent.SetSpoiler -> composer { it.copy(spoiler = intent.on) }
            MarginIntent.Send -> send()
            is MarginIntent.StartEdit -> startEdit(intent.postId)
            MarginIntent.CancelEdit -> composer { ComposerUi(kind = kindOf(state.value.tab)) }
            is MarginIntent.Delete ->
                act {
                    repository.delete(intent.postId).also { if (it is AppResult.Ok) reloadAfterChange() }
                }
            is MarginIntent.React -> react(intent.postId, intent.key)
            is MarginIntent.Vote -> vote(intent.postId, intent.happened)
            is MarginIntent.Reveal -> actOnPost { repository.reveal(intent.postId) }
            is MarginIntent.PinTapped -> {
                highlighted = intent.postId
                mutableState.update { it.copy(open = true, tab = MarginTab.Chapter) }
                render()
            }
            is MarginIntent.OpenThread -> openThread(intent.postId)
            is MarginIntent.TypeReply -> mutableState.update { it.copy(thread = it.thread?.copy(draft = intent.text)) }
            MarginIntent.SendReply -> sendReply()
            MarginIntent.CloseThread -> {
                thread = null
                mutableState.update { it.copy(thread = null) }
            }
            is MarginIntent.Translate -> translate(intent.postId)
            is MarginIntent.Report -> mutableState.update { it.copy(reporting = intent.postId) }
            is MarginIntent.SendReport -> report(intent.reason)
            MarginIntent.CancelReport -> mutableState.update { it.copy(reporting = null) }
            is MarginIntent.Mute -> mute(intent.authorId)
            is MarginIntent.SelectWeek -> loadSalon(intent.week)
            MarginIntent.ToastShown -> mutableState.update { it.copy(toast = null) }
        }
    }

    private fun kindOf(tab: MarginTab) =
        when (tab) {
            MarginTab.Chapter -> PostKind.Note
            MarginTab.Theories -> PostKind.Theory
            MarginTab.Salon -> PostKind.Salon
        }

    private fun composer(change: (ComposerUi) -> ComposerUi) = mutableState.update { it.copy(composer = change(it.composer)) }

    private fun mark(
        pageIndex: Int,
        anchor: Anchor,
    ) = composer { it.copy(kind = PostKind.Note, pageIndex = pageIndex, anchor = anchor, tool = MarkTool.None) }

    private fun selectTab(tab: MarginTab) {
        mutableState.update {
            val c = it.composer
            // The draft follows the tab (a theory is not a note); a mark only belongs to a note.
            val kind = kindOf(tab)
            val composer = if (c.editingId != null) ComposerUi(kind = kind) else c.copy(kind = kind, tool = MarkTool.None)
            it.copy(
                tab = tab,
                composer =
                    if (kind ==
                        PostKind.Note
                    ) {
                        composer
                    } else {
                        composer.copy(anchor = null, pageIndex = null, sticker = null)
                    },
            )
        }
        refreshTab()
    }

    private fun refreshTab() =
        when (state.value.tab) {
            MarginTab.Chapter -> load()
            MarginTab.Theories -> loadTheories()
            MarginTab.Salon -> loadSalon(salon?.week?.takeIf { salon?.current == false })
        }

    private fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.margin(chapterId, state.value.order)) {
                is AppResult.Ok -> show(result.value, offline = false)
                is AppResult.Err -> failed(result.error, keepShowing = margin != null)
            }
        }
    }

    private fun loadTheories() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.theories(chapterId, state.value.order)) {
                is AppResult.Ok -> {
                    result.value.posts.forEach { posts[it.id] = it }
                    theoryIds = result.value.posts.map { it.id }
                    mutableState.update { it.copy(loading = false, offline = false) }
                    render()
                }
                is AppResult.Err -> failed(result.error, keepShowing = true)
            }
        }
    }

    private fun loadSalon(week: String?) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.salon(chapterId, week)) {
                is AppResult.Ok -> {
                    salon = result.value
                    result.value.posts.forEach { posts[it.id] = it }
                    mutableState.update { it.copy(loading = false, offline = false) }
                    render()
                }
                is AppResult.Err -> failed(result.error, keepShowing = true)
            }
        }
    }

    private fun show(
        value: Margin,
        offline: Boolean,
    ) {
        margin = value
        (value.pins + value.notes + value.theories).forEach { posts[it.id] = it }
        if (theoryIds.isEmpty()) theoryIds = value.theories.map { it.id }
        mutableState.update { it.copy(loading = false, offline = offline) }
        render()
    }

    private fun failed(
        error: AppError,
        keepShowing: Boolean,
    ) {
        // No `forum` flag on the server's side: nothing of the margin shows.
        if (error is AppError.Rejected && error.code in setOf("FEATURE_UNAVAILABLE", "FEATURE_DISABLED", "FEATURE_COMING_SOON")) {
            mutableState.update { it.copy(visible = false, open = false, loading = false) }
            return
        }
        mutableState.update {
            it.copy(
                loading = false,
                offline = error == AppError.Network && keepShowing,
                error = if (error == AppError.Network && keepShowing) null else error.forumMessage(),
            )
        }
    }

    private fun send() {
        val c = state.value.composer
        if (!c.canSend) {
            composer { it.copy(problem = ForumRules.problem(it.kind, it.body, it.hasMark)?.let(::problemLine)) }
            return
        }
        val body = ForumRules.clean(c.body)
        composer { it.copy(sending = true, problem = null) }
        viewModelScope.launch {
            val editing = c.editingId
            val result =
                if (editing != null) {
                    repository.edit(editing, body)
                } else {
                    repository.post(
                        chapterId,
                        Draft(
                            kind = c.kind,
                            language = language,
                            body = body.ifEmpty { null },
                            sticker = Sticker.of(c.sticker).takeIf { c.kind == PostKind.Note },
                            pageIndex = c.pageIndex.takeIf { c.kind == PostKind.Note && c.anchor != null },
                            anchor = c.anchor.takeIf { c.kind == PostKind.Note },
                            spoiler = c.spoiler,
                        ),
                    )
                }
            when (result) {
                is AppResult.Ok -> {
                    posts[result.value.id] = result.value
                    composer { ComposerUi(kind = it.kind) }
                    reloadAfterChange()
                }
                is AppResult.Err -> composer { it.copy(sending = false, problem = result.error.forumMessage()) }
            }
        }
    }

    private fun startEdit(postId: String) {
        val post = posts[postId] ?: return
        if (!post.editable) return
        composer { ComposerUi(kind = post.kind, body = post.body.orEmpty(), editingId = post.id) }
    }

    /** After a post, an edit or a removal: the open tab again (counts, order and masks are the server's). */
    private fun reloadAfterChange() {
        if (state.value.tab != MarginTab.Chapter) refreshTab()
        load()
    }

    private fun react(
        postId: String,
        key: String,
    ) {
        val post = posts[postId] ?: return
        val sticker = Sticker.of(key) ?: return
        actOnPost { repository.react(postId, sticker.takeIf { post.myReaction != it }) }
    }

    private fun vote(
        postId: String,
        happened: Boolean,
    ) {
        val verdict = posts[postId]?.verdict ?: return
        if (!verdict.canVote) return
        actOnPost { repository.vote(postId, happened.takeIf { verdict.mine != it }) }
    }

    /** A change answered with the post as it is now. */
    private fun actOnPost(call: suspend () -> AppResult<Post>) =
        viewModelScope.launch {
            when (val result = call()) {
                is AppResult.Ok -> {
                    posts[result.value.id] = result.value
                    render()
                }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.forumMessage()) }
            }
        }

    private fun act(call: suspend () -> AppResult<Unit>) =
        viewModelScope.launch {
            val result = call()
            if (result is AppResult.Err) mutableState.update { it.copy(error = result.error.forumMessage()) }
        }

    private fun openThread(postId: String) {
        viewModelScope.launch {
            when (val result = repository.replies(postId)) {
                is AppResult.Ok -> {
                    posts[result.value.post.id] = result.value.post
                    result.value.replies.forEach { posts[it.id] = it }
                    thread = result.value.post.id to result.value.replies.map { it.id }
                    render()
                }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.forumMessage()) }
            }
        }
    }

    private fun sendReply() {
        val open = state.value.thread ?: return
        if (!open.canSend) return
        val (parentId, _) = thread ?: return
        mutableState.update { it.copy(thread = it.thread?.copy(sending = true)) }
        viewModelScope.launch {
            when (val result = repository.reply(parentId, language, ForumRules.clean(open.draft), spoiler = false)) {
                is AppResult.Ok -> {
                    mutableState.update { it.copy(thread = it.thread?.copy(draft = "", sending = false)) }
                    openThread(parentId)
                }
                is AppResult.Err ->
                    mutableState.update { it.copy(thread = it.thread?.copy(sending = false), error = result.error.forumMessage()) }
            }
        }
    }

    private fun translate(postId: String) {
        if (postId in translating || translations[postId]?.status == TranslationStatus.Ready) return
        translating += postId
        render()
        viewModelScope.launch {
            var result = repository.translate(postId, language)
            // Queued: asked again every 2 s, two minutes at most.
            var tries = 0
            while (result is AppResult.Ok && result.value.status == TranslationStatus.Queued && tries < POLLS) {
                delay(POLL_MS)
                tries++
                result = repository.translation(result.value.id)
            }
            translating -= postId
            when (result) {
                is AppResult.Ok ->
                    if (result.value.status == TranslationStatus.Ready) {
                        translations[postId] = result.value
                    } else {
                        mutableState.update { it.copy(error = t("forum.translateFailed")) }
                    }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.forumMessage()) }
            }
            render()
        }
    }

    private fun report(reason: ReportReason) {
        val postId = state.value.reporting ?: return
        mutableState.update { it.copy(reporting = null) }
        viewModelScope.launch {
            when (val result = repository.report(postId, reason, null)) {
                is AppResult.Ok -> mutableState.update { it.copy(toast = t("forum.reported")) }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.forumMessage()) }
            }
        }
    }

    private fun mute(authorId: String) {
        val name =
            posts.values
                .firstOrNull { it.author.id == authorId }
                ?.author
                ?.name
                .orEmpty()
        viewModelScope.launch {
            when (val result = repository.mute(authorId, true)) {
                is AppResult.Ok -> {
                    // Silent for them: their posts leave this screen now, and the server's lists from now on.
                    muted += authorId
                    mutableState.update { it.copy(toast = t("forum.muted", "name" to name)) }
                    render()
                    reloadAfterChange()
                }
                is AppResult.Err -> mutableState.update { it.copy(error = result.error.forumMessage()) }
            }
        }
    }

    private fun shown(post: Post) = post.author.id !in muted

    private fun render() {
        val m = margin
        val notes =
            m
                ?.notes
                .orEmpty()
                .mapNotNull { posts[it.id] }
                .filter(::shown)
        val pins =
            m
                ?.pins
                .orEmpty()
                .mapNotNull { posts[it.id] }
                .filter { shown(it) && it.pageIndex != null && it.anchor != null }
                .map { pin(it) }
                .groupBy { it.pageIndex }
        val theories = theoryIds.mapNotNull { posts[it] }.filter(::shown).map(::ui)
        val salonUi =
            salon?.let { s ->
                SalonUi(
                    week = weekLabel(s.week),
                    weekKey = s.week,
                    current = s.current,
                    weeks = s.weeks.map { WeekUi(it.week, t("forum.weekCount", "week" to weekLabel(it.week), "count" to it.count)) },
                    posts =
                        s.posts
                            .mapNotNull { posts[it.id] }
                            .filter(::shown)
                            .map(::ui),
                )
            }
        val threadUi =
            thread?.let { (parentId, replyIds) ->
                posts[parentId]?.let { parent ->
                    ThreadUi(
                        post = ui(parent),
                        replies = replyIds.mapNotNull { posts[it] }.filter(::shown).map(::ui),
                        draft =
                            state.value.thread
                                ?.draft
                                .orEmpty(),
                        sending = state.value.thread?.sending == true,
                    )
                }
            }
        val endCard =
            m?.let {
                EndCardUi(
                    title = t("forum.readThisFar"),
                    readers = it.readersHere?.let { n -> t("forum.readersHere", "count" to n) } ?: t("forum.aHandful"),
                    best = notes.filter { p -> !p.masked && p.status == PostStatus.Visible && p.body != null }.take(BEST).map(::ui),
                )
            }
        mutableState.update {
            it.copy(
                count = (m?.notes.orEmpty() + m?.pins.orEmpty()).map { p -> p.id }.toSet().size,
                pins = pins,
                notes = notes.map(::ui),
                theories = theories,
                salon = salonUi,
                thread = threadUi,
                endCard = endCard,
            )
        }
    }

    private fun pin(post: Post): PinUi {
        val sticker = post.sticker
        val label =
            when {
                post.masked -> "？"
                sticker != null -> sticker.glyph
                else -> "✎"
            }
        val a11y =
            if (post.masked) {
                t("forum.pinMasked")
            } else {
                t("forum.pinBy", "name" to post.author.name, "text" to (post.body ?: post.sticker?.glyph.orEmpty()))
            }
        return pinOf(post.id, post.pageIndex ?: 0, post.anchor!!, label, post.masked, a11y)
    }

    private fun ui(post: Post): PostUi {
        val translation = translations[post.id]
        val hasWords = !post.masked && !post.body.isNullOrBlank()
        return PostUi(
            id = post.id,
            kind = post.kind,
            authorId = post.author.id,
            author = post.author.name,
            badge =
                when (post.badge) {
                    "creator" -> t("forum.badgeCreator")
                    "staff" -> t("forum.badgeStaff")
                    else -> null
                },
            age = age(post.createdAt),
            body = post.body.takeIf { !post.masked },
            maskedLine =
                if (post.masked) {
                    post.spoilerUntil?.let { t("forum.maskedAt", "chapter" to chapterLabel(it)) } ?: t("forum.masked")
                } else {
                    null
                },
            sticker = post.sticker?.glyph.takeIf { !post.masked },
            place = post.pageIndex?.let { t("forum.page", "n" to it + 1) },
            note =
                when (post.status) {
                    PostStatus.Held -> t("forum.statusHeld")
                    PostStatus.Hidden -> t("forum.statusHidden")
                    PostStatus.Removed -> t("forum.statusRemoved")
                    PostStatus.Deleted -> t("forum.statusDeleted")
                    PostStatus.Visible -> if (post.editedAt != null) t("forum.edited") else null
                },
            reactions =
                Sticker.entries.map { s ->
                    ReactionUi(s.key, s.glyph, post.reactions[s]?.takeIf { it > 0 }, post.myReaction == s)
                },
            replies = post.replyCount.takeIf { it > 0 }?.let { t("forum.replies", "count" to it) },
            canReply = post.parentId == null && post.status == PostStatus.Visible && !post.masked,
            mine = post.mine,
            editable = post.editable,
            canTranslate = hasWords && !sameLanguage(post.language, language) && translation == null,
            translation = translation?.text,
            translating = post.id in translating,
            verdict =
                post.verdict?.let { v ->
                    VerdictUi(
                        state =
                            when (v.state) {
                                VerdictState.Open -> t("forum.verdictOpen")
                                VerdictState.Confirmed -> t("forum.verdictConfirmed")
                                VerdictState.Debunked -> t("forum.verdictDebunked")
                                VerdictState.Disputed -> t("forum.verdictDisputed")
                            },
                        counts =
                            if (v.confirmed != null && v.debunked != null) {
                                t("forum.verdictCounts", "yes" to v.confirmed, "no" to v.debunked)
                            } else {
                                null
                            },
                        canVote = v.canVote,
                        mine = v.mine,
                    )
                },
            highlighted = post.id == highlighted,
        )
    }

    private fun sameLanguage(
        a: String,
        b: String,
    ) = a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    private fun age(at: Long?): String {
        at ?: return ""
        val minutes = ((clock.nowMillis() - at) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> t("forum.ageNow")
            minutes < 60 -> t("forum.ageMinutes", "n" to minutes)
            minutes < 60 * 24 -> t("forum.ageHours", "n" to minutes / 60)
            else -> t("forum.ageDays", "n" to minutes / (60 * 24))
        }
    }

    private fun chapterLabel(number: Double) = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    /** "2026-W43" → "Week 43". */
    private fun weekLabel(week: String) = week.substringAfter("-W", "").toIntOrNull()?.let { t("forum.week", "n" to it) } ?: week

    private fun problemLine(code: String): String =
        when (code) {
            "FORUM_TOO_LONG" -> t("errors.FORUM_TOO_LONG", "max" to state.value.composer.max)
            else -> AppError.Rejected(code, "").forumMessage()
        }

    private fun AppError.forumMessage(): String =
        when {
            this is AppError.Rejected && code == "FORUM_TOO_LONG" -> t("errors.FORUM_TOO_LONG", "max" to state.value.composer.max)
            else -> userMessage()
        }

    private companion object {
        const val BEST = 3
        const val POLL_MS = 2_000L
        const val POLLS = 60
    }
}
