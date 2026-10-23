package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ChapterImports
import com.vovoscan.core.common.ImportStatus
import com.vovoscan.core.common.ImportedChapter
import com.vovoscan.core.common.SourceUnit
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.PixelBox
import com.vovoscan.feature.reader.domain.TranslatedPage
import com.vovoscan.feature.reader.domain.TranslatedText
import com.vovoscan.feature.reader.domain.TranslationQuote
import com.vovoscan.feature.reader.domain.TranslationQuotes
import com.vovoscan.feature.reader.domain.TranslationStatus
import com.vovoscan.feature.reader.domain.TranslationsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class TranslationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val page =
        TranslatedPage(
            0,
            1000,
            2000,
            listOf(TranslatedText("t1", PixelBox(100.0, 100.0, 200.0, 100.0), "Salut", "#ffffff", "#000000", true, null)),
        )

    private class Imports(
        val result: AppResult<ImportedChapter> = AppResult.Ok(ImportedChapter("title-1", "server-1", ImportStatus.Ready, 18)),
        val existing: ImportedChapter? = null,
    ) : ChapterImports {
        val asked = mutableListOf<SourceUnit>()
        var finds = 0

        override suspend fun find(unit: SourceUnit): ImportedChapter? {
            finds += 1
            return existing
        }

        override suspend fun import(unit: SourceUnit): AppResult<ImportedChapter> {
            asked += unit
            return result
        }

        override suspend fun status(chapterId: String): AppResult<ImportedChapter> = result
    }

    @Test
    fun aSourcesChapterIsBroughtInQuotedThenOpenedOnItsCopy() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            val imports = Imports()
            val vm =
                TranslationViewModel(
                    "inkwell",
                    "u3",
                    repo,
                    TranslationQuotes { id, _ -> if (id == "server-1") TranslationQuote(12, 15, false, false, true) else null },
                    titleId = "t9",
                    imports = imports,
                )
            advanceUntilIdle()
            assertTrue(vm.state.value.available)
            // Nothing leaves the device before a language is asked.
            assertTrue(imports.asked.isEmpty())
            assertEquals(0, repo.languageCalls)

            vm.onIntent(TranslationIntent.Choose("fr"))
            assertEquals(
                "Getting the pages from the source…",
                vm.state.value.dock
                    ?.title,
            )
            advanceUntilIdle()
            assertEquals(listOf(SourceUnit("inkwell", "t9", "u3")), imports.asked)
            // Quoted on the copy, nothing paid yet.
            assertEquals(
                "Translate",
                vm.state.value.dock
                    ?.confirmLabel,
            )
            assertEquals(0, repo.requests)

            vm.onIntent(TranslationIntent.ConfirmOffer)
            advanceUntilIdle()
            assertEquals(1, repo.requests)
            assertEquals(TranslatedCopy("title-1", "server-1", "fr", 18), vm.state.value.openCopy)
            // The lettering is the copy's: this reader keeps the original.
            assertTrue(
                vm.state.value.lettering
                    .isEmpty(),
            )
            assertNull(vm.state.value.dock)
            vm.onIntent(TranslationIntent.CopyOpened)
            assertNull(vm.state.value.openCopy)
        }

    @Test
    fun aCopyThatCannotBeMadeSaysWhy() =
        runTest(dispatcher) {
            val imports = Imports(AppResult.Ok(ImportedChapter("title-1", "server-1", ImportStatus.Failed, 0, "INVALID_IMAGE")))
            val vm = TranslationViewModel("inkwell", "u3", FakeTranslations(), titleId = "t9", imports = imports)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("fr"))
            advanceUntilIdle()
            assertEquals(
                "The source sent a page that can’t be read.",
                vm.state.value.dock
                    ?.line,
            )
            assertTrue(
                vm.state.value.dock
                    ?.failed == true,
            )
        }

    @Test
    fun theCopyOpensInTheLanguageAskedForAtTheSameShare() =
        runTest(dispatcher) {
            val repo = FakeTranslations(languages = listOf(ChapterTranslation("fr", TranslationStatus.Ready, null)))
            val vm = TranslationViewModel("vovoscan", "server-1", repo, initialLanguage = "fr")
            advanceUntilIdle()
            assertEquals("Français", vm.state.value.buttonLabel)
            assertEquals(
                1,
                vm.state.value
                    .letteringFor(0)
                    .size,
            )
            val copy = TranslatedCopy("title-1", "server-1", "fr", 18)
            assertEquals(9, copy.startPage(page = 5, pages = 10))
            assertEquals(17, copy.startPage(page = 9, pages = 9))
            assertEquals(0, copy.startPage(page = 3, pages = 1))
        }

    @Test
    fun aReadyLanguageShowsAtOnceAndIsListedFirst() =
        runTest(dispatcher) {
            val repo = FakeTranslations(languages = listOf(ChapterTranslation("fr", TranslationStatus.Ready, null)))
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            val options = vm.state.value.options
            assertNull(options[0].code)
            assertEquals("fr", options[1].code)
            assertEquals("Ready ✦ free", options[1].statusLabel)
            vm.onIntent(TranslationIntent.Choose("fr"))
            advanceUntilIdle()
            assertEquals(0, repo.requests)
            assertEquals("Français", vm.state.value.buttonLabel)
            val lettering = vm.state.value.letteringFor(0)
            assertEquals(1, lettering.size)
            assertTrue(vm.state.value.translated)
            vm.onIntent(TranslationIntent.Choose(null))
            advanceUntilIdle()
            assertFalse(vm.state.value.translated)
            assertEquals("Original", vm.state.value.buttonLabel)
        }

    @Test
    fun aNewLanguageIsAskedForAndFollowedUntilReady() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            repo.requestResult = AppResult.Ok(ChapterTranslation("ee", TranslationStatus.Queued, null))
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceTimeBy(100)
            assertTrue(vm.state.value.working)
            repo.languages = listOf(ChapterTranslation("ee", TranslationStatus.Running, 40))
            advanceTimeBy(4_100)
            assertEquals("Translating ✦ 40%", vm.state.value.progressLabel)
            repo.languages = listOf(ChapterTranslation("ee", TranslationStatus.Ready, null))
            advanceUntilIdle()
            assertFalse(vm.state.value.working)
            assertTrue(vm.state.value.translated)
        }

    @Test
    fun anEmptyWalletSaysHowToGoOn() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            repo.requestResult = AppResult.Err(AppError.Rejected("INSUFFICIENT_CREDITS", "No credits."))
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            assertEquals(TranslationText.NO_CREDITS, vm.state.value.error)
            assertFalse(vm.state.value.translated)
        }

    @Test
    fun aNewLanguageIsQuotedBeforeAnythingIsPaid() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            repo.requestResult = AppResult.Ok(ChapterTranslation("yo", TranslationStatus.Queued, null))
            val quotes = TranslationQuotes { _, _ -> TranslationQuote(12, 20, false, false, true) }
            val vm = TranslationViewModel("vovoscan", "c1", repo, quotes)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            val offer = assertNotNull(vm.state.value.offer)
            assertEquals("Translate into Yorùbá?", offer.title)
            assertEquals("About 12 credits from your wallet. It's made once, then free for every reader.", offer.line)
            assertTrue(offer.canTranslate)
            assertEquals(0, repo.requests)
            vm.onIntent(TranslationIntent.ConfirmOffer)
            advanceTimeBy(100)
            assertEquals(1, repo.requests)
            assertNull(vm.state.value.offer)
            assertTrue(vm.state.value.working)
        }

    @Test
    fun ownKeysAreFreeAndAWalletThatCantPayLeadsToTheKeys() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            var quote = TranslationQuote(0, 0, true, false, true)
            val vm = TranslationViewModel("vovoscan", "c1", repo, { _, _ -> quote })
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceUntilIdle()
            assertEquals(
                "Your own key pays for it: no credits used. It's made once, then free for every reader.",
                vm.state.value.offer
                    ?.line,
            )
            assertNull(
                vm.state.value.offer
                    ?.addKeyLabel,
            )
            vm.onIntent(TranslationIntent.DismissOffer)
            assertNull(vm.state.value.offer)
            quote = TranslationQuote(40, 60, false, false, false)
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceUntilIdle()
            val offer = assertNotNull(vm.state.value.offer)
            assertFalse(offer.canTranslate)
            assertEquals("Add a key", offer.addKeyLabel)
            vm.onIntent(TranslationIntent.ConfirmOffer)
            advanceUntilIdle()
            assertEquals(0, repo.requests)
        }

    @Test
    fun aLanguageAlreadyMadeSomewhereSwitchesWithoutAsking() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            val vm = TranslationViewModel("vovoscan", "c1", repo, { _, _ -> TranslationQuote(0, 0, false, true, true) })
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("fr"))
            advanceUntilIdle()
            assertNull(vm.state.value.offer)
            assertTrue(vm.state.value.translated)
        }

    @Test
    fun searchFindsByOwnNameEnglishNameOrCode() =
        runTest(dispatcher) {
            val vm = TranslationViewModel("vovoscan", "c1", FakeTranslations())
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Query("ewe"))
            val ewe = vm.state.value.options
            assertEquals(listOf("ee"), ewe.map { it.code })
            vm.onIntent(TranslationIntent.Query("Français"))
            val french = vm.state.value.options
            assertEquals(listOf("fr"), french.map { it.code })
        }

    @Test
    fun aNewLanguageNeverTakesThePageAwayAndSwapsInPlaceWhenReady() =
        runTest(dispatcher) {
            val repo = FakeTranslations(languages = listOf(ChapterTranslation("fr", TranslationStatus.Ready, null)))
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("fr"))
            advanceUntilIdle()
            val french = vm.state.value.letteringFor(0)
            repo.requestResult = AppResult.Ok(ChapterTranslation("ee", TranslationStatus.Queued, null))
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceTimeBy(100)
            // Still the French lettering under a docked panel; the button names what the page shows.
            assertEquals(french, vm.state.value.letteringFor(0))
            assertEquals("Français", vm.state.value.buttonLabel)
            val making = assertNotNull(vm.state.value.dock)
            assertEquals("Translating into Eʋegbe", making.title)
            assertTrue(making.busy)
            assertEquals("Cancel", making.closeLabel)
            repo.languages = listOf(ChapterTranslation("ee", TranslationStatus.Running, 40))
            advanceTimeBy(4_100)
            val running = assertNotNull(vm.state.value.dock)
            assertEquals(0.4f, running.progress)
            assertEquals("Translating ✦ 40% ✦ Keep reading: the bubbles change by themselves when it's ready.", running.line)
            // Cancel: back to French, nothing else changes.
            vm.onIntent(TranslationIntent.CloseDock)
            assertNull(vm.state.value.dock)
            assertEquals("fr", vm.state.value.selected)
            assertEquals(french, vm.state.value.letteringFor(0))
            // Picked again, it is found ready and swaps in.
            repo.languages = listOf(ChapterTranslation("ee", TranslationStatus.Ready, null))
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceUntilIdle()
            assertNull(vm.state.value.dock)
            assertEquals("Eʋegbe", vm.state.value.buttonLabel)
            assertEquals("Read it in another language", vm.state.value.endCardLabel)
        }

    @Test
    fun aFailureStaysInTheDockWithTryAgain() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            repo.requestResult = AppResult.Err(AppError.Rejected("INSUFFICIENT_CREDITS", "No credits."))
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.dock)
            assertTrue(dock.failed)
            assertEquals("Try again", dock.retryLabel)
            assertEquals("Add a key", dock.addKeyLabel)
            assertEquals("Original", vm.state.value.buttonLabel)
            repo.requestResult = AppResult.Ok(ChapterTranslation("yo", TranslationStatus.Ready, null))
            vm.onIntent(TranslationIntent.Retry)
            advanceUntilIdle()
            assertNull(vm.state.value.dock)
            assertTrue(vm.state.value.translated)
        }

    @Test
    fun madeLanguagesComeFirstThenThoseBeingMade() =
        runTest(dispatcher) {
            val repo =
                FakeTranslations(
                    languages =
                        listOf(
                            ChapterTranslation("yo", TranslationStatus.Running, 30),
                            ChapterTranslation("fr", TranslationStatus.Ready, null),
                        ),
                )
            val vm = TranslationViewModel("vovoscan", "c1", repo)
            advanceUntilIdle()
            val codes =
                vm.state.value.options
                    .map { it.code }
            assertEquals(listOf(null, "fr", "yo"), codes.take(3))
            assertTrue(
                vm.state.value.options[1]
                    .ready,
            )
        }

    @Test
    fun aSourcesChapterAlreadyOnTheServerListsItsLanguagesOnceThePickerOpens() =
        runTest(dispatcher) {
            val repo = FakeTranslations(languages = listOf(ChapterTranslation("fr", TranslationStatus.Ready, null)))
            val imports = Imports(existing = ImportedChapter("title-1", "server-1", ImportStatus.Ready, 18))
            val vm = TranslationViewModel("inkwell", "u3", repo, titleId = "t9", imports = imports)
            advanceUntilIdle()
            // Reading alone tells the server nothing.
            assertEquals(0, imports.finds)
            vm.onIntent(TranslationIntent.PickerOpened)
            advanceUntilIdle()
            assertEquals(1, imports.finds)
            assertEquals(
                "fr",
                vm.state.value.options[1]
                    .code,
            )
            // Opening the picker again asks nothing more; nothing was imported.
            vm.onIntent(TranslationIntent.PickerOpened)
            advanceUntilIdle()
            assertEquals(1, imports.finds)
            assertTrue(imports.asked.isEmpty())
            // A made language opens on the copy at once, free.
            vm.onIntent(TranslationIntent.Choose("fr"))
            advanceUntilIdle()
            assertEquals(0, repo.requests)
            assertEquals(TranslatedCopy("title-1", "server-1", "fr", 18), vm.state.value.openCopy)
        }

    @Test
    fun aWalletShortOfTheCostSaysWhatIsNeededAndHeldAndLeadsToTheKeys() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            var changes = 0
            val quotes =
                object : TranslationQuotes {
                    override suspend fun quote(
                        chapterId: String,
                        lang: String,
                    ) = TranslationQuote(12, 15, false, false, fundable = false, balance = 3)

                    override fun changed() {
                        changes += 1
                    }
                }
            val vm = TranslationViewModel("vovoscan", "c1", repo, quotes)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.dock)
            assertEquals(
                "About 15 credits; you have 3 credits. Add your own Anthropic key (no credits needed), or ask the owner for a top-up.",
                dock.line,
            )
            assertNull(dock.confirmLabel)
            assertEquals("Add a key", dock.addKeyLabel)
            assertEquals(0, repo.requests)
            assertEquals(0, changes)
        }

    @Test
    fun anUnfundedQuoteShowsItsPriceAndTheKeyThatMakesItFree() =
        runTest(dispatcher) {
            val quotes =
                TranslationQuotes {
                    _,
                    _,
                    ->
                    TranslationQuote(12, 15, false, false, fundable = false, balance = 0, unfunded = true)
                }
            val vm = TranslationViewModel("vovoscan", "c1", FakeTranslations(), quotes)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.dock)
            assertEquals("About 15 credits. Add your own Anthropic key in Settings › Keys, or ask the owner for credits.", dock.line)
            assertEquals("Add a key", dock.addKeyLabel)
        }

    @Test
    fun anUnfundableQuoteWithNoPriceShowsTheBalanceNeverZeroCredits() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            val quotes = TranslationQuotes { _, _ -> TranslationQuote(0, 0, false, false, fundable = false, balance = 0) }
            val vm = TranslationViewModel("vovoscan", "c1", repo, quotes)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("yo"))
            advanceUntilIdle()
            val dock = assertNotNull(vm.state.value.dock)
            assertEquals(
                "You have 0 credits: not enough for this one. Add your own Anthropic key (no credits needed), or ask the owner for a top-up.",
                dock.line,
            )
            assertEquals("Add a key", dock.addKeyLabel)
        }

    @Test
    fun aRunAskedForAndFinishedReadsTheWalletAgain() =
        runTest(dispatcher) {
            val repo = FakeTranslations()
            repo.requestResult = AppResult.Ok(ChapterTranslation("ee", TranslationStatus.Queued, null))
            var changes = 0
            val quotes =
                object : TranslationQuotes {
                    override suspend fun quote(
                        chapterId: String,
                        lang: String,
                    ) = TranslationQuote(5, 5, false, false, true, balance = 40)

                    override fun changed() {
                        changes += 1
                    }
                }
            val vm = TranslationViewModel("vovoscan", "c1", repo, quotes)
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.Choose("ee"))
            advanceUntilIdle()
            vm.onIntent(TranslationIntent.ConfirmOffer)
            advanceTimeBy(100)
            assertEquals(1, changes)
            repo.languages = listOf(ChapterTranslation("ee", TranslationStatus.Ready, null))
            advanceUntilIdle()
            assertEquals(2, changes)
        }

    private inner class FakeTranslations(
        var languages: List<ChapterTranslation> = emptyList(),
    ) : TranslationsRepository {
        var requests = 0
        var requestResult: AppResult<ChapterTranslation> = AppResult.Ok(ChapterTranslation("fr", TranslationStatus.Ready, null))

        var languageCalls = 0

        override suspend fun languages(chapterId: String): AppResult<List<ChapterTranslation>> {
            languageCalls += 1
            return AppResult.Ok(languages)
        }

        override suspend fun request(
            chapterId: String,
            lang: String,
        ): AppResult<ChapterTranslation> {
            requests += 1
            return requestResult
        }

        override suspend fun pages(
            chapterId: String,
            lang: String,
        ): AppResult<List<TranslatedPage>> = AppResult.Ok(listOf(page))
    }
}
