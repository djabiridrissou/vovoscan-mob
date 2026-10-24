package com.vovoscan.feature.forum.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.forum.domain.Anchor
import com.vovoscan.feature.forum.domain.Author
import com.vovoscan.feature.forum.domain.Draft
import com.vovoscan.feature.forum.domain.ForumAccess
import com.vovoscan.feature.forum.domain.ForumRepository
import com.vovoscan.feature.forum.domain.Margin
import com.vovoscan.feature.forum.domain.MutedReader
import com.vovoscan.feature.forum.domain.Order
import com.vovoscan.feature.forum.domain.Post
import com.vovoscan.feature.forum.domain.PostKind
import com.vovoscan.feature.forum.domain.PostStatus
import com.vovoscan.feature.forum.domain.PostTranslation
import com.vovoscan.feature.forum.domain.Replies
import com.vovoscan.feature.forum.domain.ReportReason
import com.vovoscan.feature.forum.domain.Salon
import com.vovoscan.feature.forum.domain.SalonWeek
import com.vovoscan.feature.forum.domain.Sticker
import com.vovoscan.feature.forum.domain.Theories
import com.vovoscan.feature.forum.domain.TranslationStatus
import com.vovoscan.feature.forum.domain.Verdict
import com.vovoscan.feature.forum.domain.VerdictState
import com.vovoscan.feature.forum.domain.WritingLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun post(
    id: String,
    kind: PostKind = PostKind.Note,
    body: String? = "Look at the lantern",
    masked: Boolean = false,
    pageIndex: Int? = null,
    anchor: Anchor? = null,
    sticker: Sticker? = null,
    author: String = "u1",
    language: String = "en",
    verdict: Verdict? = null,
    reactions: Map<Sticker, Int> = emptyMap(),
    myReaction: Sticker? = null,
) = Post(
    id = id,
    kind = kind,
    parentId = null,
    chapterNumber = 3.0,
    pageIndex = pageIndex,
    anchor = anchor,
    sticker = sticker,
    body = if (masked) null else body,
    language = language,
    spoilerUntil = if (masked) 5.0 else 3.0,
    salonWeek = null,
    badge = null,
    status = PostStatus.Visible,
    score = 0,
    replyCount = 0,
    createdAt = 1_000L,
    editedAt = null,
    author = Author(author, "Aya"),
    masked = masked,
    reactions = reactions,
    myReaction = myReaction,
    verdict = verdict,
    mine = false,
    editable = false,
)

class FakeForum : ForumRepository {
    var margin =
        Margin(
            roomId = "r1",
            chapterNumber = 3.0,
            reach = 3.0,
            readersHere = 128,
            pins =
                listOf(
                    post("p1", pageIndex = 0, anchor = Anchor.Point(0.5, 0.5), sticker = Sticker.Don),
                    post("p2", pageIndex = 2, anchor = Anchor.Frame(0.1, 0.1, 0.3, 0.3), masked = true),
                ),
            // Disjoint, as the server sends them: a pinned post is only in `pins`.
            notes = listOf(post("n3", body = "Le gardien ment.", language = "fr", author = "u2")),
            theories = emptyList(),
        )
    val cached = MutableStateFlow<Margin?>(null)
    val drafts = mutableListOf<Draft>()
    val reactions = mutableListOf<Pair<String, Sticker?>>()
    val votes = mutableListOf<Pair<String, Boolean?>>()
    val muted = mutableListOf<String>()
    var postError: AppError? = null
    var marginError: AppError? = null
    var translationPolls = 0

    override fun observeMargin(chapterId: String): Flow<Margin?> = cached

    override suspend fun margin(
        chapterId: String,
        order: Order,
    ): AppResult<Margin> = marginError?.let { AppResult.Err(it) } ?: AppResult.Ok(margin)

    override suspend fun post(
        chapterId: String,
        draft: Draft,
    ): AppResult<Post> {
        postError?.let { return AppResult.Err(it) }
        drafts += draft
        return AppResult.Ok(post("new", kind = draft.kind, body = draft.body))
    }

    override suspend fun salon(
        chapterId: String,
        week: String?,
    ) = AppResult.Ok(
        Salon(
            "r1",
            week ?: "2026-W43",
            week == null,
            listOf(SalonWeek("2026-W43", 2), SalonWeek("2026-W42", 5)),
            3.0,
            listOf(post("s1", kind = PostKind.Salon)),
        ),
    )

    override suspend fun theories(
        chapterId: String,
        order: Order,
    ) = AppResult.Ok(
        Theories(
            "r1",
            3.0,
            listOf(post("t1", kind = PostKind.Theory, verdict = Verdict(VerdictState.Confirmed, 12, 3, null, canVote = true))),
        ),
    )

    override suspend fun replies(postId: String) = AppResult.Ok(Replies(post(postId), listOf(post("r1", body = "Agreed"))))

    override suspend fun reply(
        postId: String,
        language: String,
        body: String,
        spoiler: Boolean,
    ) = AppResult.Ok(post("r2", body = body))

    override suspend fun edit(
        postId: String,
        body: String,
    ) = AppResult.Ok(post(postId, body = body))

    override suspend fun delete(postId: String) = AppResult.Ok(Unit)

    override suspend fun reveal(postId: String) =
        AppResult.Ok(post(postId, body = "The lantern goes out", pageIndex = 2, anchor = Anchor.Frame(0.1, 0.1, 0.3, 0.3)))

    override suspend fun react(
        postId: String,
        sticker: Sticker?,
    ): AppResult<Post> {
        reactions += postId to sticker
        return AppResult.Ok(
            post(
                postId,
                sticker = Sticker.Don,
                pageIndex = 0,
                anchor = Anchor.Point(0.5, 0.5),
                reactions = mapOf(Sticker.Kira to 4),
                myReaction = sticker,
            ),
        )
    }

    override suspend fun vote(
        postId: String,
        happened: Boolean?,
    ): AppResult<Post> {
        votes += postId to happened
        return AppResult.Ok(post(postId, kind = PostKind.Theory, verdict = Verdict(VerdictState.Confirmed, 13, 3, happened, true)))
    }

    override suspend fun report(
        postId: String,
        reason: ReportReason,
        note: String?,
    ) = AppResult.Ok(Unit)

    override suspend fun translate(
        postId: String,
        language: String,
    ) = AppResult.Ok(PostTranslation("tr1", postId, language, TranslationStatus.Queued, null, "fr", null))

    override suspend fun translation(id: String): AppResult<PostTranslation> {
        translationPolls++
        val ready = translationPolls >= 2
        return AppResult.Ok(
            PostTranslation(
                id,
                "n3",
                "en",
                if (ready) TranslationStatus.Ready else TranslationStatus.Queued,
                if (ready) "The keeper lies." else null,
                "fr",
                null,
            ),
        )
    }

    override suspend fun mutes() = AppResult.Ok(emptyList<MutedReader>())

    override suspend fun mute(
        userId: String,
        muted: Boolean,
    ): AppResult<Unit> {
        this.muted += userId
        return AppResult.Ok(Unit)
    }

    override suspend fun clearLocal() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ForumViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        forum: FakeForum,
        on: Boolean = true,
    ) = ForumViewModel("c3", forum, ForumAccess { flowOf(on) }, WritingLanguage { "en" }) { 1_000L + 5 * 60_000L }

    @Test
    fun withoutTheFlagNothingShowsAndNothingIsAsked() =
        runTest(dispatcher) {
            val forum = FakeForum().apply { marginError = AppError.Network }
            val vm = vm(forum, on = false)
            advanceUntilIdle()
            assertFalse(vm.state.value.visible)
            assertEquals(0, vm.state.value.count)
        }

    @Test
    fun theMarginShowsPinsMaskedSpoilersAndTheEndCard() =
        runTest(dispatcher) {
            val vm = vm(FakeForum())
            advanceUntilIdle()
            val s = vm.state.value
            assertTrue(s.visible)
            assertEquals(3, s.count)
            // The chapter's list holds every post, pins first.
            assertEquals(listOf("p1", "p2", "n3"), s.notes.map { it.id })
            assertNull(s.empty)
            assertEquals("BOOM", s.pins[0]!!.single().label)
            // Pins show on the pages only while the margin is open.
            assertTrue(s.pinsOn(0).isEmpty())
            vm.onIntent(MarginIntent.Toggle)
            assertEquals(
                PinShape.Frame,
                vm.state.value
                    .pinsOn(2)
                    .single()
                    .shape,
            )
            // A spoiler for this reader: "?" on the page, no words in the list.
            assertEquals("?", s.pins[2]!!.single().label)
            val masked = s.notes.single { it.id == "p2" }
            assertNull(masked.body)
            assertEquals("A spoiler from chapter 5. Show it anyway?", masked.maskedLine)
            assertEquals("5 min", s.notes.first().age)
            assertEquals("128 readers got here", s.endCard!!.readers)
            // The best notes under the last page never include a masked one.
            assertEquals(listOf("p1", "n3"), s.endCard!!.best.map { it.id })
            assertTrue(s.notes.single { it.id == "n3" }.canTranslate)
            assertFalse(s.notes.single { it.id == "p1" }.canTranslate)

            vm.onIntent(MarginIntent.Reveal("p2"))
            advanceUntilIdle()
            assertEquals(
                "The lantern goes out",
                vm.state.value.notes
                    .single { it.id == "p2" }
                    .body,
            )
        }

    @Test
    fun aNotePinnedWithAStrokeAndASticker() =
        runTest(dispatcher) {
            val forum = FakeForum()
            val vm = vm(forum)
            advanceUntilIdle()
            vm.onIntent(MarginIntent.ChooseTool(MarkTool.Draw))
            assertEquals("Draw on the panel.", vm.state.value.composer.toolHint)
            vm.onIntent(MarginIntent.PlaceStroke(1, (0..200).flatMap { listOf(it / 200.0, 0.5) }))
            assertEquals("Pinned on page 2.", vm.state.value.composer.toolHint)
            // A stroke alone is a note.
            assertTrue(vm.state.value.composer.canSend)
            vm.onIntent(MarginIntent.PickSticker("gogogo"))
            vm.onIntent(MarginIntent.Send)
            advanceUntilIdle()
            val draft = forum.drafts.single()
            assertEquals(PostKind.Note, draft.kind)
            assertEquals("en", draft.language)
            assertNull(draft.body)
            assertEquals(Sticker.Gogogo, draft.sticker)
            assertEquals(1, draft.pageIndex)
            assertTrue((draft.anchor as Anchor.Stroke).points.size <= 64)
            assertEquals(ComposerUi(), vm.state.value.composer)
        }

    @Test
    fun theComposerSaysWhyBeforeSending() =
        runTest(dispatcher) {
            val forum = FakeForum()
            val vm = vm(forum)
            advanceUntilIdle()
            vm.onIntent(MarginIntent.Type("a".repeat(501)))
            assertFalse(vm.state.value.composer.canSend)
            assertEquals("501 / 500", vm.state.value.composer.counter)
            vm.onIntent(MarginIntent.Send)
            assertEquals("Keep it under 500 characters.", vm.state.value.composer.problem)
            assertTrue(forum.drafts.isEmpty())

            forum.postError = AppError.Rejected("FORUM_SILENCED", "Silenced.")
            vm.onIntent(MarginIntent.Type("Fine"))
            vm.onIntent(MarginIntent.Send)
            advanceUntilIdle()
            assertEquals("You can’t post for now.", vm.state.value.composer.problem)
        }

    @Test
    fun theoriesTakeAVerdictFromReadersFurtherOn() =
        runTest(dispatcher) {
            val forum = FakeForum()
            val vm = vm(forum)
            advanceUntilIdle()
            vm.onIntent(MarginIntent.SelectTab(MarginTab.Theories))
            advanceUntilIdle()
            assertEquals(PostKind.Theory, vm.state.value.composer.kind)
            val theory =
                vm.state.value.theories
                    .single()
            assertEquals("It happened", theory.verdict!!.state)
            assertEquals("12 say it happened ✦ 3 say it didn’t", theory.verdict!!.counts)
            vm.onIntent(MarginIntent.Vote("t1", happened = true))
            advanceUntilIdle()
            assertEquals(listOf<Pair<String, Boolean?>>("t1" to true), forum.votes)
            assertEquals(
                true,
                vm.state.value.theories
                    .single()
                    .verdict!!
                    .mine,
            )
            // The same again takes it back.
            vm.onIntent(MarginIntent.Vote("t1", happened = true))
            advanceUntilIdle()
            assertEquals("t1" to null, forum.votes.last())
        }

    @Test
    fun theSalonByWeekAndOnlyTheCurrentOneTakesPosts() =
        runTest(dispatcher) {
            val vm = vm(FakeForum())
            advanceUntilIdle()
            vm.onIntent(MarginIntent.SelectTab(MarginTab.Salon))
            advanceUntilIdle()
            assertEquals(
                "Week 43",
                vm.state.value.salon!!
                    .week,
            )
            assertEquals(
                listOf(WeekUi("2026-W43", "Week 43 ✦ 2"), WeekUi("2026-W42", "Week 42 ✦ 5")),
                vm.state.value.salon!!
                    .weeks,
            )
            assertTrue(vm.state.value.canWrite)
            vm.onIntent(MarginIntent.SelectWeek("2026-W42"))
            advanceUntilIdle()
            assertFalse(vm.state.value.canWrite)
        }

    @Test
    fun reactionsTranslationsReportsAndMutes() =
        runTest(dispatcher) {
            val forum = FakeForum()
            val vm = vm(forum)
            advanceUntilIdle()
            vm.onIntent(MarginIntent.React("p1", "kira"))
            advanceUntilIdle()
            val kira =
                vm.state.value.notes
                    .single { it.id == "p1" }
                    .reactions
                    .single { it.key == "kira" }
            assertEquals(4, kira.count)
            assertTrue(kira.mine)

            vm.onIntent(MarginIntent.Translate("n3"))
            advanceUntilIdle()
            assertEquals(
                "The keeper lies.",
                vm.state.value.notes
                    .single { it.id == "n3" }
                    .translation,
            )

            vm.onIntent(MarginIntent.Report("n3"))
            assertEquals("n3", vm.state.value.reporting)
            vm.onIntent(MarginIntent.SendReport(ReportReason.Spoiler))
            advanceUntilIdle()
            assertNull(vm.state.value.reporting)
            assertEquals("Thanks. We’ll look at it.", vm.state.value.toast)

            vm.onIntent(MarginIntent.Mute("u2"))
            advanceUntilIdle()
            assertEquals(listOf("u2"), forum.muted)
            assertTrue(
                vm.state.value.notes
                    .none { it.authorId == "u2" },
            )
        }

    @Test
    fun offlineTheSavedMarginStays() =
        runTest(dispatcher) {
            val forum = FakeForum()
            forum.cached.value = forum.margin
            forum.marginError = AppError.Network
            val vm = vm(forum)
            advanceUntilIdle()
            assertTrue(vm.state.value.offline)
            assertNull(vm.state.value.error)
            assertEquals(3, vm.state.value.count)
            assertFalse(vm.state.value.canWrite)
        }

    @Test
    fun theServerWithoutTheFlagHidesTheMargin() =
        runTest(dispatcher) {
            val forum = FakeForum().apply { marginError = AppError.Rejected("FEATURE_UNAVAILABLE", "") }
            val vm = vm(forum)
            advanceUntilIdle()
            assertFalse(vm.state.value.visible)
        }
}
