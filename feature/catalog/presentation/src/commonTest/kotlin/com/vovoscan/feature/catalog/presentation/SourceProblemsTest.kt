package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.SOURCE_RULES
import com.vovoscan.core.common.StaleReason
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A source that fails (down, unreadable) is not the device being offline: the screens say which. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceProblemsTest {
    private val dispatcher = StandardTestDispatcher()
    private val book = TitleSummary("gutenberg", "1342", "Pride and Prejudice", null, ContentKind.Book, ReadingFormat.Text, "completed")
    private val down = AppError.SourceUnavailable("Project Gutenberg")
    private val unreadable = AppError.Rejected(SOURCE_RULES, "No node matched .booklink")

    /** The device keeps one title of Project Gutenberg; the source answers [result]. */
    private class Gutenberg(
        var result: AppResult<Unit>,
        saved: TitleSummary?,
    ) : CatalogRepository {
        val titles = MutableStateFlow(listOfNotNull(saved))
        val detail =
            MutableStateFlow(
                saved?.let {
                    TitleDetail(it, "A novel.", listOf("Jane Austen"), emptyList(), listOf(Chapter("1342", 1.0, null, 0, null)))
                },
            )
        var searched: SearchResult = SearchResult(emptyList(), offline = false)

        override fun observeTitles(sourceKey: String): Flow<List<TitleSummary>> = titles

        override suspend fun refreshTitles(sourceKey: String): AppResult<Boolean> =
            when (val r = result) {
                is AppResult.Ok -> AppResult.Ok(false)
                is AppResult.Err -> r
            }

        override suspend fun loadMoreTitles(sourceKey: String) = AppResult.Ok(false)

        override suspend fun search(
            sourceKey: String,
            query: String,
        ) = searched

        override fun observeTitle(
            sourceKey: String,
            id: String,
        ): Flow<TitleDetail?> = detail

        override suspend fun refreshTitle(
            sourceKey: String,
            id: String,
        ) = result

        override suspend fun clearLocal() = Unit

        override suspend fun clearSource(sourceKey: String) = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun catalog(repository: Gutenberg) = CatalogViewModel(repository).also { it.onIntent(CatalogIntent.SelectSource("gutenberg")) }

    @Test
    fun aSourceThatDoesNotAnswerIsNamedWithRetry() =
        runTest(dispatcher) {
            val repository = Gutenberg(AppResult.Err(down), book)
            val vm = catalog(repository)
            advanceUntilIdle()
            assertEquals("Project Gutenberg isn't answering right now. Showing what's saved.", vm.state.value.offlineNotice)
            assertTrue(vm.state.value.canRetry)
            assertNull(vm.state.value.error)

            // Retry once it answers again: the banner goes.
            repository.result = AppResult.Ok(Unit)
            vm.onIntent(CatalogIntent.Refresh)
            advanceUntilIdle()
            assertNull(vm.state.value.offlineNotice)
            assertFalse(vm.state.value.canRetry)
        }

    @Test
    fun withNothingSavedTheBannerDoesNotPromiseSavedTitles() =
        runTest(dispatcher) {
            val vm = catalog(Gutenberg(AppResult.Err(down), null))
            advanceUntilIdle()
            assertEquals("Project Gutenberg isn't answering right now.", vm.state.value.offlineNotice)
            // Not "No titles yet": the source failed, the banner says so.
            assertFalse(vm.state.value.empty)
        }

    @Test
    fun realNoNetworkKeepsTheOfflineNotice() =
        runTest(dispatcher) {
            val vm = catalog(Gutenberg(AppResult.Err(AppError.Network), book))
            advanceUntilIdle()
            assertEquals("Offline: showing what's saved on this device.", vm.state.value.offlineNotice)
            assertFalse(vm.state.value.canRetry)
        }

    @Test
    fun ruleErrorsSayTheSourceMayNeedAnUpdate() =
        runTest(dispatcher) {
            val vm = catalog(Gutenberg(AppResult.Err(unreadable), book))
            advanceUntilIdle()
            assertEquals("This source couldn't be read. It may need an update.", vm.state.value.offlineNotice)
            assertFalse(vm.state.value.canRetry)
            assertNull(vm.state.value.error)
        }

    @Test
    fun searchFallsBackToSavedTitlesWhenTheSourceIsDown() =
        runTest(dispatcher) {
            val repository = Gutenberg(AppResult.Ok(Unit), book)
            repository.searched = SearchResult(listOf(book), offline = true, unavailableSource = "Project Gutenberg")
            val vm = catalog(repository)
            vm.onIntent(CatalogIntent.Query("pride"))
            advanceUntilIdle()
            assertEquals("Project Gutenberg isn't answering right now. Searching what's saved.", vm.state.value.offlineNotice)
            assertEquals(
                1,
                vm.state.value.results
                    ?.size,
            )
        }

    @Test
    fun titlePageShowsTheSourceProblem() =
        runTest(dispatcher) {
            val repository = Gutenberg(AppResult.Err(down), book)
            val vm = TitleDetailViewModel("gutenberg", "1342", repository)
            advanceUntilIdle()
            assertEquals("Project Gutenberg isn't answering right now. Showing what's saved.", vm.state.value.notice)
            assertTrue(vm.state.value.canRetry)
            assertNull(vm.state.value.error)

            repository.result = AppResult.Err(unreadable)
            vm.refresh()
            advanceUntilIdle()
            assertEquals("This source couldn't be read. It may need an update.", vm.state.value.notice)
            assertFalse(vm.state.value.canRetry)

            repository.result = AppResult.Err(AppError.Network)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(StaleReason.Offline, vm.state.value.stale)
            assertEquals("Offline: showing what's saved on this device.", vm.state.value.notice)
        }

    @Test
    fun aSavedCopyArrivingAfterTheFailureIsShownAsSaved() =
        runTest(dispatcher) {
            val repository = Gutenberg(AppResult.Err(AppError.Network), null)
            val vm = TitleDetailViewModel("gutenberg", "1342", repository)
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            // The local copy shows up late (database read after the failed refresh).
            repository.detail.value = TitleDetail(book, "A novel.", emptyList(), emptyList(), emptyList())
            advanceUntilIdle()
            assertFalse(vm.state.value.unavailable)
            assertNull(vm.state.value.error)
            assertEquals("Offline: showing what's saved on this device.", vm.state.value.notice)
        }

    @Test
    fun aTitleNeverOpenedSaysTheSourceIsDownWithRetry() =
        runTest(dispatcher) {
            val vm = TitleDetailViewModel("gutenberg", "84", Gutenberg(AppResult.Err(down), null))
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertEquals("Project Gutenberg isn't answering right now.", vm.state.value.error)
            assertNull(vm.state.value.notice)
            assertTrue(vm.state.value.canRetry)
        }
}
