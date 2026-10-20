package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchResult
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeCatalog(
        var refresh: AppResult<Boolean>,
    ) : CatalogRepository {
        val titles = MutableStateFlow(listOf(TitleSummary("t1", "Ink Road", null, ReadingFormat.Webtoon, "ongoing")))

        override fun observeTitles(): Flow<List<TitleSummary>> = titles

        override suspend fun refreshTitles() = refresh

        override suspend fun loadMoreTitles() = AppResult.Ok(false)

        override suspend fun search(query: String) = SearchResult(titles.value, offline = true)

        override fun observeTitle(id: String): Flow<TitleDetail?> = flowOf(null)

        override suspend fun refreshTitle(id: String) = AppResult.Ok(Unit)

        override suspend fun clearLocal() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun horsLigneLaCopieLocaleResteAffichee() =
        runTest(dispatcher) {
            val vm = CatalogViewModel(FakeCatalog(AppResult.Err(AppError.Network)))
            advanceUntilIdle()
            assertEquals(
                "WEBTOON",
                vm.state.value.cards
                    .single()
                    .formatLabel,
            )
            assertEquals(Freshness.Offline, vm.state.value.freshness)
            assertNotNull(vm.state.value.offlineNotice)
            assertNull(vm.state.value.error)
        }

    @Test
    fun rechercheAttendLaFinDeLaFrappe() =
        runTest(dispatcher) {
            val vm = CatalogViewModel(FakeCatalog(AppResult.Ok(false)))
            vm.onIntent(CatalogIntent.Query("in"))
            vm.onIntent(CatalogIntent.Query("ink"))
            advanceUntilIdle()
            assertEquals(
                1,
                vm.state.value.results
                    ?.size,
            )
            assertEquals("Offline: searching titles saved on this device.", vm.state.value.offlineNotice)
            vm.onIntent(CatalogIntent.Query(""))
            assertNull(vm.state.value.results)
        }
}
