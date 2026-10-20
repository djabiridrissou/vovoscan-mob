package com.vovoscan.feature.sources.presentation

import com.vovoscan.feature.sources.domain.SourceUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun enableReorderAndKeepOneSourceVisible() =
        runTest {
            val fake = FakeSources()
            val vm = SourcesViewModel(fake, fake)
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
            assertEquals(listOf("gutenberg", "vovoscan"), vm.state.value.sourceKeys)
        }

    @Test
    fun rowsShowOriginVersionUpdatesAndRepositories() =
        runTest {
            val fake = FakeSources()
            fake.items.value += FakeSources.thirdParty(update = SourceUpdate("1.1.0", null, emptyList()), settings = emptyMap())
            val vm = SourcesViewModel(fake, fake)
            val row =
                vm.state.value.rows
                    .last()
            assertEquals(Triple("Third-party", "v1.0.0", "D"), Triple(row.originLabel, row.versionLabel, row.initial))
            assertTrue(row.updateAvailable && row.needsSettings && row.thirdParty)
            assertEquals(
                "Official",
                vm.state.value.rows
                    .first()
                    .originLabel,
            )
            assertEquals("1 update available", vm.state.value.updatesLabel)
            assertEquals(
                "2 sources",
                vm.state.value.repositories
                    .single()
                    .sourceCountLabel,
            )

            // At start: the 12-hour rule; pull to refresh and opening the screen force a check.
            assertEquals(listOf(false), fake.checks)
            vm.onIntent(SourcesIntent.Refresh)
            vm.onIntent(SourcesIntent.CheckUpdates)
            assertEquals(listOf(false, true, true), fake.checks)

            vm.onIntent(SourcesIntent.RemoveRepository("org.demo"))
            assertTrue(
                vm.state.value.repositories
                    .isEmpty(),
            )
            assertEquals("Repository removed.", vm.state.value.notice)
        }
}
