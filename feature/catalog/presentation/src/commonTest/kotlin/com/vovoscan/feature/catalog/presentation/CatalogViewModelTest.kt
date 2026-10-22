package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.ListedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val vovoscan = CatalogSource("vovoscan", "vovoscan")
    private val gutenberg = CatalogSource("gutenberg", "Project Gutenberg")
    private val both = listOf(vovoscan, gutenberg)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** vovoscan: 3 webtoons (page 1), 1 book (page 2); Gutenberg: 2 books a page, 2 pages. */
    private fun twoSources() =
        FakeCatalog().apply {
            remote["vovoscan" to null] =
                listOf(
                    listOf(
                        title("vovoscan", "v1", ReadingFormat.Webtoon),
                        title("vovoscan", "v2", ReadingFormat.Webtoon),
                        title("vovoscan", "v3", ReadingFormat.Webtoon),
                    ),
                    listOf(title("vovoscan", "v4", ReadingFormat.Text)),
                )
            remote["gutenberg" to null] =
                listOf(
                    listOf(title("gutenberg", "g1", ReadingFormat.Text), title("gutenberg", "g2", ReadingFormat.Text)),
                    listOf(title("gutenberg", "g3", ReadingFormat.Text), title("gutenberg", "g4", ReadingFormat.Text)),
                )
            categories["vovoscan"] =
                listOf(SourceCategory("manga", "Manga"), SourceCategory("webtoon", "Webtoon"), SourceCategory("book", "Book"))
            categories["gutenberg"] = listOf(SourceCategory("fiction", "Fiction"), SourceCategory("8", "book"))
            remote["vovoscan" to "webtoon"] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
            remote["vovoscan" to "book"] = listOf(listOf(title("vovoscan", "v4", ReadingFormat.Text)))
            remote["gutenberg" to "8"] = listOf(listOf(title("gutenberg", "g1", ReadingFormat.Text)))
        }

    private fun ids(vm: CatalogViewModel) =
        vm.state.value.visible
            .map { it.id }

    @Test
    fun allSourcesInOneGridInterleavedFairly() =
        runTest(dispatcher) {
            val vm = CatalogViewModel(twoSources())
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            // Page 1 of each, one title at a time, in the reader's order of sources.
            assertEquals(listOf("v1", "g1", "v2", "g2", "v3"), ids(vm))
            assertEquals("All sources", vm.state.value.sourceFilterLabel)
            assertTrue(vm.state.value.allSources && vm.state.value.showSourceFilter)
            assertEquals(
                "Project Gutenberg",
                vm.state.value.cards[1]
                    .sourceName,
            )
            assertEquals(
                "BOOK",
                vm.state.value.cards[1]
                    .formatLabel,
            )
            assertEquals(Freshness.Fresh, vm.state.value.freshness)
            assertTrue(vm.state.value.canLoadMore)

            // Loading more: page 2 of every source, appended as a new round; nothing above moves.
            vm.onIntent(CatalogIntent.LoadMore)
            advanceUntilIdle()
            assertEquals(listOf("v1", "g1", "v2", "g2", "v3", "v4", "g3", "g4"), ids(vm))
            assertFalse(vm.state.value.canLoadMore)
        }

    @Test
    fun pickingSourcesNarrowsTheGridAndIsRemembered() =
        runTest(dispatcher) {
            val repository = twoSources()
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            // From "All", a source alone.
            vm.onIntent(CatalogIntent.ToggleSource("gutenberg"))
            advanceUntilIdle()
            assertEquals(listOf("g1", "g2"), ids(vm))
            assertEquals("Project Gutenberg", vm.state.value.sourceFilterLabel)
            assertEquals(
                listOf(false, true),
                vm.state.value.sources
                    .map { it.selected },
            )
            assertEquals(CatalogFilter(listOf("gutenberg")), repository.filter.value)

            // Both chosen is "All" again.
            vm.onIntent(CatalogIntent.ToggleSource("vovoscan"))
            advanceUntilIdle()
            assertEquals(CatalogFilter(), repository.filter.value)
            assertEquals("All sources", vm.state.value.sourceFilterLabel)

            // A new screen starts from the remembered filter.
            vm.onIntent(CatalogIntent.SelectSource("vovoscan"))
            advanceUntilIdle()
            val again = CatalogViewModel(repository)
            again.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            assertEquals(listOf("v1", "v2", "v3"), ids(again))
            assertEquals("vovoscan", again.state.value.sourceFilterLabel)
        }

    @Test
    fun aRememberedSourceThatIsHiddenFallsBackToAll() =
        runTest(dispatcher) {
            val repository = twoSources().apply { filter.value = CatalogFilter(listOf("gone")) }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            assertTrue(vm.state.value.allSources)
            assertEquals(5, vm.state.value.cards.size)
        }

    @Test
    fun categoriesAreTheUnionByNameAndLeaveOutSourcesWithout() =
        runTest(dispatcher) {
            val repository = twoSources()
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            // "Book" and Gutenberg's "book" are one choice; order of first appearance.
            assertEquals(
                listOf("Manga", "Webtoon", "Book", "Fiction"),
                vm.state.value.categories
                    .map { it.name },
            )

            vm.onIntent(CatalogIntent.SelectCategory("book"))
            advanceUntilIdle()
            assertEquals("Book", vm.state.value.category)
            // Each source asked with its own id for that name.
            assertTrue(("vovoscan" to "book") in repository.refreshed && ("gutenberg" to "8") in repository.refreshed)
            assertEquals(listOf("v4", "g1"), ids(vm))
            assertNull(vm.state.value.categoryNote)

            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(listOf("v1"), ids(vm))
            assertEquals("Project Gutenberg has no “Webtoon” category, so it's left out.", vm.state.value.categoryNote)
            assertEquals(1, vm.state.value.sourcesRead)

            // One source selected: its own categories only.
            vm.onIntent(CatalogIntent.SelectSource("gutenberg"))
            advanceUntilIdle()
            assertEquals(
                listOf("Fiction", "book"),
                vm.state.value.categories
                    .map { it.name },
            )
            assertNull(vm.state.value.category)
            assertEquals("All categories", vm.state.value.categoryFilterLabel)

            vm.onIntent(CatalogIntent.SelectCategory(null))
            advanceUntilIdle()
            assertNull(repository.filter.value.categoryName)
        }

    @Test
    fun anEmptyCatalogSettlesInsteadOfLoadingForever() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { remote["vovoscan" to null] = listOf(emptyList()) }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan)))
            advanceUntilIdle()
            assertEquals(Freshness.Fresh, vm.state.value.freshness)
            assertTrue(vm.state.value.empty)
            assertEquals("No titles yet. Check back soon.", vm.state.value.emptyMessage)
        }

    @Test
    fun searchGoesToEverySourceRead() =
        runTest(dispatcher) {
            val repository = twoSources()
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.Query("g"))
            vm.onIntent(CatalogIntent.Query("1"))
            advanceUntilIdle()
            assertEquals(
                listOf("v1", "g1"),
                vm.state.value.results
                    ?.map { it.id },
            )
            assertEquals(2, repository.searched.size)
            vm.onIntent(CatalogIntent.Query(""))
            assertNull(vm.state.value.results)
        }

    @Test
    fun aSourceThatFailsDoesNotBreakTheOthers() =
        runTest(dispatcher) {
            val repository = twoSources().apply { failures["gutenberg"] = AppError.SourceUnavailable("Project Gutenberg") }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            assertEquals(listOf("v1", "v2", "v3"), ids(vm))
            assertEquals("Project Gutenberg isn't answering right now.", vm.state.value.offlineNotice)
            assertTrue(vm.state.value.canRetry)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.empty)

            // Search: the others answer, the failing one searches what is saved.
            vm.onIntent(CatalogIntent.Query("v1"))
            advanceUntilIdle()
            assertEquals(
                listOf("v1"),
                vm.state.value.results
                    ?.map { it.id },
            )
            assertEquals("Project Gutenberg isn't answering right now. Searching what's saved.", vm.state.value.offlineNotice)
            vm.onIntent(CatalogIntent.Query(""))

            repository.failures.clear()
            vm.onIntent(CatalogIntent.Refresh)
            advanceUntilIdle()
            assertNull(vm.state.value.offlineNotice)
            assertEquals(5, vm.state.value.cards.size)
        }

    @Test
    fun offlineTheSavedCopiesStayVisible() =
        runTest(dispatcher) {
            val repository =
                twoSources().apply {
                    saved.value = mapOf(("vovoscan" to null) to listOf(ListedTitle(title("vovoscan", "v1", ReadingFormat.Webtoon), 1)))
                    failures["vovoscan"] = AppError.Network
                    failures["gutenberg"] = AppError.Network
                }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(both))
            advanceUntilIdle()
            assertEquals(
                "WEBTOON",
                vm.state.value.cards
                    .single()
                    .formatLabel,
            )
            assertEquals(Freshness.Offline, vm.state.value.freshness)
            assertEquals("Offline: showing what's saved on this device.", vm.state.value.offlineNotice)
            assertFalse(vm.state.value.canRetry)

            vm.onIntent(CatalogIntent.Query("v"))
            advanceUntilIdle()
            assertEquals("Offline: searching titles saved on this device.", vm.state.value.offlineNotice)
            assertNotNull(vm.state.value.results)
        }

    @Test
    fun oneVisibleSourceNeedsNoFilter() =
        runTest(dispatcher) {
            val vm = CatalogViewModel(twoSources())
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan)))
            advanceUntilIdle()
            assertFalse(vm.state.value.showSourceFilter)
            assertEquals(listOf("v1", "v2", "v3"), ids(vm))
        }
}
