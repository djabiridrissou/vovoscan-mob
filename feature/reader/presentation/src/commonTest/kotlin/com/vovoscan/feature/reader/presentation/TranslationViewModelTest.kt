package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.reader.domain.ChapterTranslation
import com.vovoscan.feature.reader.domain.PixelBox
import com.vovoscan.feature.reader.domain.TranslatedPage
import com.vovoscan.feature.reader.domain.TranslatedText
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

    @Test
    fun onlyOurCatalogOffersLanguages() =
        runTest(dispatcher) {
            val vm = TranslationViewModel("gutenberg", "c1", FakeTranslations())
            advanceUntilIdle()
            assertFalse(vm.state.value.available)
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
            assertEquals("Ready", options[1].statusLabel)
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

    private inner class FakeTranslations(
        var languages: List<ChapterTranslation> = emptyList(),
    ) : TranslationsRepository {
        var requests = 0
        var requestResult: AppResult<ChapterTranslation> = AppResult.Ok(ChapterTranslation("fr", TranslationStatus.Ready, null))

        override suspend fun languages(chapterId: String): AppResult<List<ChapterTranslation>> = AppResult.Ok(languages)

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
