package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.Funding
import com.vovoscan.feature.reader.domain.NextChapters
import com.vovoscan.feature.reader.domain.NextChaptersRepository
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.Upcoming
import com.vovoscan.feature.reader.domain.UpcomingTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NextChapterViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The server in memory: chapters after "c1", each 12 credits; a prefetch queues the missing ones (cancellable), a
     * read moves a queued one to running, then to ready.
     */
    private class Server(
        count: Int = 6,
        var funding: Funding = Funding.Credits,
        var auto: Boolean = false,
    ) : NextChaptersRepository {
        val chapters = (2..count + 1).map { Upcoming("c$it", it.toDouble(), null, 20, true, null, 12) }.toMutableList()
        val prefetched = mutableListOf<Triple<Int, Int, Boolean>>()
        var refuseAuto = false
        var price = 12
        var reads = 0

        private fun dto(lang: String?) =
            NextChapters(
                lang,
                "s1",
                funding,
                if (funding ==
                    Funding.Credits
                ) {
                    500
                } else {
                    null
                },
                auto,
                chapters.toList(),
            )

        override suspend fun next(
            chapterId: String,
            lang: String?,
        ): AppResult<NextChapters> {
            reads++
            chapters.mapInPlace { up ->
                when (up.translation?.status) {
                    TranslationStatus.Queued -> up.copy(translation = UpcomingTranslation(TranslationStatus.Running, 42, null, false))
                    TranslationStatus.Running ->
                        up.copy(
                            translation = UpcomingTranslation(TranslationStatus.Ready, 100, null, false),
                            credits = 0,
                        )
                    else -> up
                }
            }
            return AppResult.Ok(dto(lang))
        }

        override suspend fun prefetch(
            chapterId: String,
            lang: String,
            count: Int,
            maxCredits: Int,
            auto: Boolean,
        ): AppResult<NextChapters> {
            val todo = chapters.take(count).filter { it.translation == null }
            if (todo.size * price > maxCredits) return AppResult.Err(AppError.Rejected("QUOTE_CHANGED", "The price changed."))
            prefetched += Triple(count, maxCredits, auto)
            chapters.mapInPlace { up ->
                if (up in todo) up.copy(translation = UpcomingTranslation(TranslationStatus.Queued, 0, null, true)) else up
            }
            return AppResult.Ok(dto(lang))
        }

        override suspend fun cancel(
            chapterId: String,
            lang: String,
        ): AppResult<NextChapters> {
            chapters.mapInPlace { up -> if (up.translation?.cancellable == true) up.copy(translation = null) else up }
            return AppResult.Ok(dto(lang))
        }

        override suspend fun setAuto(
            chapterId: String,
            lang: String,
            auto: Boolean,
        ): AppResult<NextChapters> {
            if (refuseAuto) return AppResult.Err(AppError.Network)
            this.auto = auto
            return AppResult.Ok(dto(lang))
        }
    }

    @Test
    fun inTheOriginalOrForASourcesChapterNothingChangesTheEndCard() =
        runTest(dispatcher) {
            val server = Server()
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.Reached(0.9f))
            advanceUntilIdle()
            assertNull(vm.state.value.endCard)
            assertNull(vm.state.value.bubble)
            assertEquals(0, server.reads)

            val source = NextChapterViewModel("inkwell", "u1", server)
            source.onIntent(NextChapterIntent.LanguageShown("fr"))
            advanceUntilIdle()
            assertFalse(source.state.value.available)
            assertEquals(0, server.reads)
        }

    @Test
    fun theEndCardPreparesTheNextChapterAtItsPriceThenFollowsItUntilReady() =
        runTest(dispatcher) {
            val server = Server()
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            advanceUntilIdle()
            val card = assertNotNull(vm.state.value.endCard)
            assertEquals("Chapter 2 isn't in Français yet.", card.note)
            assertEquals("Prepare it ✦ 12 credits", card.primary?.label)
            assertEquals(NextActionKind.Prepare, card.primary?.kind)
            // The original always reads.
            assertEquals(NextButton("Read it in the original", NextActionKind.Open, "c2", null), card.secondary)
            assertEquals(
                "Always prepare the next chapter of this series in Français",
                vm.state.value.autoSwitch
                    ?.label,
            )

            vm.onIntent(NextChapterIntent.PrepareNext)
            runCurrent()
            assertEquals(listOf(Triple(1, 12, false)), server.prefetched)
            assertEquals(
                "Preparing Chapter 2 in Français ✦ 0%",
                vm.state.value.endCard
                    ?.note,
            )

            // Read again every 3 s: running, then ready.
            advanceTimeBy(3_001)
            assertEquals(
                0.42f,
                vm.state.value.endCard
                    ?.progress,
            )
            advanceTimeBy(3_000)
            assertEquals(
                NextButton("Chapter 2 →", NextActionKind.Open, "c2", "fr"),
                vm.state.value.endCard
                    ?.primary,
            )
            val toast = assertNotNull(vm.state.value.toast)
            assertEquals("Chapter 2 is ready in Français.", toast.line)
            assertEquals(NextButton("Read it →", NextActionKind.Open, "c2", "fr"), toast.button)
            // Nothing left to follow: no more reads; the toast goes by itself.
            val reads = server.reads
            advanceTimeBy(9_000)
            assertEquals(reads, server.reads)
            assertNull(vm.state.value.toast)
        }

    @Test
    fun theBalloonOffersTheNextThenFiveThenTenOnlyPastFortyPercent() =
        runTest(dispatcher) {
            val server = Server(count = 12)
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            vm.onIntent(NextChapterIntent.Reached(0.3f))
            advanceUntilIdle()
            assertNull(vm.state.value.bubble)
            vm.onIntent(NextChapterIntent.Reached(0.45f))
            var bubble = assertNotNull(vm.state.value.bubble)
            assertEquals("Chapter 2 isn't in Français yet. Prepare it while you read?", bubble.offerLine)
            assertEquals("Prepare ✦ 12 credits", bubble.offer?.label)

            vm.onIntent(NextChapterIntent.AcceptOffer)
            runCurrent()
            bubble = assertNotNull(vm.state.value.bubble)
            assertEquals("Preparing Chapter 2 in Français ✦ 0%", bubble.busyLine)
            assertEquals("Cancel", bubble.cancelLabel)
            assertEquals("Prepare the next 5 too?", bubble.offerLine)
            assertEquals("Next 5 ✦ 48 credits", bubble.offer?.label)

            vm.onIntent(NextChapterIntent.AcceptOffer)
            runCurrent()
            assertEquals(listOf(Triple(1, 12, false), Triple(5, 48, false)), server.prefetched)
            assertEquals(
                "Preparing 5 chapters in Français…",
                vm.state.value.bubble
                    ?.busyLine,
            )
            assertEquals(
                "Next 10 ✦ 60 credits",
                vm.state.value.bubble
                    ?.offer
                    ?.label,
            )

            // Cancel takes back what has not started.
            vm.onIntent(NextChapterIntent.Cancel)
            runCurrent()
            assertNull(
                vm.state.value.bubble
                    ?.busyLine,
            )

            // The end card wins its place; hidden, the balloon stays hidden for this chapter.
            vm.onIntent(NextChapterIntent.AtEnd(true))
            assertNull(vm.state.value.bubble)
            vm.onIntent(NextChapterIntent.AtEnd(false))
            assertNotNull(vm.state.value.bubble)
            vm.onIntent(NextChapterIntent.HideBubble)
            assertNull(vm.state.value.bubble)
        }

    @Test
    fun theSeriesSettingPreparesTheNextChapterOnceAtSeventyPercent() =
        runTest(dispatcher) {
            val server = Server(auto = true)
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            vm.onIntent(NextChapterIntent.Reached(0.5f))
            advanceUntilIdle()
            assertTrue(server.prefetched.isEmpty())
            vm.onIntent(NextChapterIntent.Reached(0.75f))
            vm.onIntent(NextChapterIntent.Reached(0.9f))
            runCurrent()
            assertEquals(listOf(Triple(1, 12, true)), server.prefetched)
        }

    @Test
    fun withoutAKeyOrFundingTheWayOnIsTheKeysNeverASpend() =
        runTest(dispatcher) {
            val server = Server(funding = Funding.None, auto = true)
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            vm.onIntent(NextChapterIntent.Reached(0.8f))
            advanceUntilIdle()
            assertEquals(
                NextButton("Needs your own key", NextActionKind.AddKey),
                vm.state.value.endCard
                    ?.primary,
            )
            assertEquals(
                NextActionKind.AddKey,
                vm.state.value.bubble
                    ?.offer
                    ?.kind,
            )
            vm.onIntent(NextChapterIntent.PrepareNext)
            vm.onIntent(NextChapterIntent.AcceptOffer)
            advanceUntilIdle()
            assertTrue(server.prefetched.isEmpty())
        }

    @Test
    fun aPriceThatMovedIsShownAgainBeforeAnythingIsSpent() =
        runTest(dispatcher) {
            val server = Server()
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            advanceUntilIdle()
            server.price = 15
            server.chapters.mapInPlace { it.copy(credits = 15) }
            vm.onIntent(NextChapterIntent.PrepareNext)
            advanceUntilIdle()
            assertTrue(server.prefetched.isEmpty())
            assertEquals(
                "The price changed. Check it and confirm again.",
                vm.state.value.endCard
                    ?.error,
            )
            assertEquals(
                "Prepare it ✦ 15 credits",
                vm.state.value.endCard
                    ?.primary
                    ?.label,
            )
        }

    @Test
    fun theSwitchMovesAtOnceAndARefusalPutsItBack() =
        runTest(dispatcher) {
            val server = Server()
            val vm = NextChapterViewModel("vovoscan", "c1", server)
            vm.onIntent(NextChapterIntent.LanguageShown("fr"))
            advanceUntilIdle()
            vm.onIntent(NextChapterIntent.SetAuto(true))
            assertEquals(
                true,
                vm.state.value.autoSwitch
                    ?.on,
            )
            advanceUntilIdle()
            assertTrue(server.auto)
            server.refuseAuto = true
            vm.onIntent(NextChapterIntent.SetAuto(false))
            assertEquals(
                false,
                vm.state.value.autoSwitch
                    ?.on,
            )
            advanceUntilIdle()
            assertEquals(
                true,
                vm.state.value.autoSwitch
                    ?.on,
            )

            // Back to the original: the reader's own card stands.
            vm.onIntent(NextChapterIntent.LanguageShown(null))
            assertNull(vm.state.value.endCard)
            assertNull(vm.state.value.autoSwitch)
        }

    @Test
    fun theReaderSaysHowFarItIsAndWhenTheEndCardShows() {
        val pages = (0 until 4).map { ReaderPage(it, "https://api.test/p$it", 800, 1200) }
        val card = { forward: Boolean -> ReaderItem.Transition(TransitionCard(forward, "", "", null, null)) }
        val items = listOf(card(false)) + pages.map { ReaderItem.Page(it) } + card(true)
        val paged = ReaderUiState(mode = ReadingMode.PagedRightToLeft, pages = pages, items = items, currentIndex = 1, currentItem = 2)
        assertEquals(0.5f, paged.readShare)
        assertFalse(paged.onEndCard)
        assertTrue(paged.copy(currentItem = items.lastIndex, currentIndex = 3).onEndCard)
        // A strip: the share scrolled, the end once it is all read.
        val strip = paged.copy(mode = ReadingMode.LongStrip, progress = 0.42f)
        assertEquals(0.42f, strip.readShare)
        assertTrue(strip.copy(progress = 1f).onEndCard)
        assertEquals(0f, ReaderUiState().readShare)
    }
}

/** `replaceAll` is JVM-only: the same for every target. */
private inline fun <T> MutableList<T>.mapInPlace(transform: (T) -> T) {
    for (i in indices) this[i] = transform(this[i])
}
