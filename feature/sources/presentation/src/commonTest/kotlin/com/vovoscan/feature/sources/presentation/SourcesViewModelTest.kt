package com.vovoscan.feature.sources.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.sources.domain.SourceEngine
import com.vovoscan.feature.sources.domain.SourceItem
import com.vovoscan.feature.sources.domain.SourcesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {
    private class FakeSources : SourcesRepository {
        val items =
            MutableStateFlow(
                listOf(
                    SourceItem("vovoscan", "vovoscan", SourceEngine.Internal, "1", listOf("comic", "book"), listOf("en"), true, 0, false),
                    SourceItem("gutenberg", "Project Gutenberg", SourceEngine.OnDevice, "1", listOf("book"), listOf("en"), false, 1, false),
                ),
            )

        override fun observe() = items

        override suspend fun refresh() = AppResult.Ok(Unit)

        override suspend fun save(choices: List<Pair<String, Boolean>>): AppResult<Unit> {
            val byKey = items.value.associateBy { it.key }
            items.value = choices.mapIndexed { i, (k, e) -> byKey.getValue(k).copy(enabled = e, position = i) }
            return AppResult.Ok(Unit)
        }

        override suspend fun clearLocal() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun activerRangerEtGarderUneSourceVisible() =
        runTest {
            val vm = SourcesViewModel(FakeSources())
            assertEquals(
                "Manga · Books",
                vm.state.value.rows
                    .first()
                    .kindsLabel,
            )
            assertEquals(
                "Read on your device",
                vm.state.value.rows
                    .last()
                    .engineLabel,
            )
            vm.onIntent(SourcesIntent.Toggle("gutenberg"))
            vm.onIntent(SourcesIntent.MoveUp("gutenberg"))
            assertEquals(
                listOf("gutenberg", "vovoscan"),
                vm.state.value.chips
                    .map { it.key },
            )
            vm.onIntent(SourcesIntent.Toggle("vovoscan"))
            vm.onIntent(SourcesIntent.Toggle("gutenberg"))
            assertEquals("Keep at least one source visible.", vm.state.value.error)
            assertEquals(
                listOf("gutenberg"),
                vm.state.value.chips
                    .map { it.key },
            )
        }
}
