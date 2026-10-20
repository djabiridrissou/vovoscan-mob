package com.vovoscan.feature.reader.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val chapter =
        ReaderChapter(
            id = "c1",
            titleId = "t1",
            titleName = "Paper Tide",
            number = 1.0,
            name = null,
            mode = ReadingMode.PagedRightToLeft,
            previousChapterId = null,
            nextChapterId = "c2",
            pages = (0 until 5).map { ReaderPage(it, "https://api.test/p$it", 1000, 1500) },
        )

    private class FakeReader(
        initial: ReaderChapter?,
        private val refresh: AppResult<Unit>,
    ) : ReaderRepository {
        val stored = MutableStateFlow(initial)

        override fun observe(chapterId: String): Flow<ReaderChapter?> = stored

        override suspend fun refresh(chapterId: String) = refresh

        override suspend fun clearLocal() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun repriseALaPageEnregistree() =
        runTest(dispatcher) {
            val vm = ReaderViewModel("c1", startPage = 3, FakeReader(chapter, AppResult.Ok(Unit))) { _, _, _ -> }
            advanceUntilIdle()
            assertEquals("4 / 5", vm.state.value.positionLabel)
            assertTrue(vm.state.value.rightToLeft)
            assertEquals("Next chapter →", vm.state.value.nextLabel)
        }

    @Test
    fun laProgressionSEnregistreApresUnePause() =
        runTest(dispatcher) {
            val saved = mutableListOf<Int>()
            val vm = ReaderViewModel("c1", 0, FakeReader(chapter, AppResult.Ok(Unit))) { _, _, page -> saved += page }
            advanceUntilIdle()
            vm.onIntent(ReaderIntent.PageVisible(1))
            advanceTimeBy(200)
            vm.onIntent(ReaderIntent.PageVisible(2))
            advanceUntilIdle()
            assertEquals(listOf(2), saved)
        }

    @Test
    fun jamaisOuvertEtHorsLigne() =
        runTest(dispatcher) {
            val vm = ReaderViewModel("c1", 0, FakeReader(null, AppResult.Err(AppError.Network))) { _, _, _ -> }
            advanceUntilIdle()
            assertTrue(vm.state.value.unavailable)
            assertEquals(Freshness.Offline, vm.state.value.freshness)
        }
}
