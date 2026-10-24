package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.ContentRating
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.catalog.domain.CatalogFilter
import com.vovoscan.feature.catalog.domain.ListedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.TitleDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            // Page 1 of each, mixed (each source in its own order).
            val firstRound = ids(vm)
            assertEquals(setOf("v1", "g1", "v2", "g2", "v3"), firstRound.toSet())
            assertEquals(listOf("v1", "v2", "v3"), firstRound.filter { it.startsWith("v") })
            assertEquals(listOf("g1", "g2"), firstRound.filter { it.startsWith("g") })
            assertEquals("All sources", vm.state.value.sourceFilterLabel)
            assertTrue(vm.state.value.allSources && vm.state.value.showSourceFilter)
            val gutenbergCard =
                vm.state.value.cards
                    .first { it.id == "g1" }
            assertEquals("Project Gutenberg", gutenbergCard.sourceName)
            assertEquals("BOOK", gutenbergCard.formatLabel)
            assertEquals(Freshness.Fresh, vm.state.value.freshness)
            assertTrue(vm.state.value.canLoadMore)

            // Loading more: page 2 of every source, appended as a new round; nothing above moves.
            vm.onIntent(CatalogIntent.LoadMore)
            advanceUntilIdle()
            assertEquals(firstRound, ids(vm).take(5))
            assertEquals(setOf("v4", "g3", "g4"), ids(vm).drop(5).toSet())
            assertFalse(vm.state.value.canLoadMore)

            // Pull to refresh starts again from page 1, in a new mix of the same titles.
            vm.onIntent(CatalogIntent.Refresh)
            advanceUntilIdle()
            assertEquals(firstRound.toSet(), ids(vm).toSet())
        }

    @Test
    fun safeModeShowsOnlyTitlesRatedForEveryone() =
        runTest(dispatcher) {
            val repository =
                FakeCatalog().apply {
                    remote["gutenberg" to null] =
                        listOf(
                            listOf(
                                title("gutenberg", "safe").copy(contentRating = ContentRating.Safe),
                                title("gutenberg", "suggestive").copy(contentRating = ContentRating.Suggestive),
                                title("gutenberg", "unrated"),
                            ),
                        )
                }
            val adult = MutableStateFlow(false)
            val vm = CatalogViewModel(repository, content = { adult })
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(gutenberg)))
            advanceUntilIdle()
            assertEquals(listOf("safe"), ids(vm))
            assertEquals("Safe mode hides titles not rated for all audiences.", vm.state.value.safeNote)
            // Search follows the same rule.
            vm.onIntent(CatalogIntent.Query("gutenberg"))
            advanceUntilIdle()
            assertEquals(listOf("safe"), ids(vm))
            // Turned off in Settings: everything, the note gone.
            adult.value = true
            advanceUntilIdle()
            assertEquals(setOf("safe", "suggestive", "unrated"), ids(vm).toSet())
            vm.onIntent(CatalogIntent.Query(""))
            advanceUntilIdle()
            assertEquals(3, ids(vm).size)
            assertNull(vm.state.value.safeNote)
        }

    @Test
    fun safeModeLeavesTheInternalSourceToTheServer() =
        runTest(dispatcher) {
            val repository =
                FakeCatalog().apply {
                    remote["vovoscan" to null] =
                        listOf(
                            listOf(
                                title("vovoscan", "unrated"),
                                title("vovoscan", "suggestive").copy(contentRating = ContentRating.Suggestive),
                            ),
                        )
                    remote["gutenberg" to null] = listOf(listOf(title("gutenberg", "plugin-unrated")))
                }
            val vm = CatalogViewModel(repository, content = { MutableStateFlow(false) })
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, gutenberg)))
            advanceUntilIdle()
            // The API filtered its own titles: an unrated one stays, a rated one still follows the rule.
            assertEquals(listOf("unrated"), ids(vm))
            vm.onIntent(CatalogIntent.Query("unrated"))
            advanceUntilIdle()
            assertEquals(listOf("unrated"), ids(vm))
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
            assertEquals(setOf("v4", "g1"), ids(vm).toSet())
            assertNull(vm.state.value.categoryNote)

            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(listOf("v1"), ids(vm))
            assertEquals("Project Gutenberg has no “Webtoon” category, so it's left out.", vm.state.value.categoryNote)
            assertEquals(1, vm.state.value.sourcesRead)

            // One source selected: the formats first (its "book" stands for Book), then its own categories.
            vm.onIntent(CatalogIntent.SelectSource("gutenberg"))
            advanceUntilIdle()
            assertEquals(
                listOf("Manga", "Webtoon", "book", "Fiction"),
                vm.state.value.categories
                    .map { it.name },
            )
            // A format is offered whatever the sources: the choice stays.
            assertEquals("Webtoon", vm.state.value.category)

            vm.onIntent(CatalogIntent.SelectCategory(null))
            advanceUntilIdle()
            assertNull(repository.filter.value.categoryName)
            assertEquals("All categories", vm.state.value.categoryFilterLabel)
        }

    @Test
    fun theFormatsComeFirstWhateverSourcesAreOnThenEachSourcesOwnCategories() =
        runTest(dispatcher) {
            // Our own source switched off: a book source with genres comes first, then a comic source with none.
            val shelf = CatalogSource("shelf", "Shelf")
            val inkwell = CatalogSource("inkwell", "Inkwell")
            val repository =
                FakeCatalog().apply {
                    categories["shelf"] =
                        listOf(SourceCategory("f", "Fiction"), SourceCategory("p", "Poetry"), SourceCategory("d", "Drama"))
                    remote["shelf" to null] = listOf(listOf(title("shelf", "s1", ReadingFormat.Text)))
                    remote["inkwell" to null] =
                        listOf(listOf(title("inkwell", "i1", ReadingFormat.Webtoon), title("inkwell", "i2", ReadingFormat.Manga)))
                }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(shelf, inkwell)))
            advanceUntilIdle()

            val groups = vm.state.value.categoryGroups
            assertEquals(listOf(null, "Shelf"), groups.map { it.title })
            assertEquals(listOf("Manga", "Webtoon", "Book"), groups[0].choices.map { it.name })
            assertEquals(listOf("Fiction", "Poetry", "Drama"), groups[1].choices.map { it.name })
            assertEquals(
                listOf("Manga", "Webtoon", "Book", "Fiction", "Poetry", "Drama"),
                vm.state.value.categories
                    .map { it.name },
            )

            // A format files every source's titles by their format, plugin comics included.
            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(listOf("i1"), ids(vm))
            assertEquals(
                listOf(false, true, false),
                vm.state.value.categoryGroups[0]
                    .choices
                    .map { it.selected },
            )
            assertNull(vm.state.value.categoryNote)

            // A source's own category: that source only, the other one is left out.
            vm.onIntent(CatalogIntent.SelectCategory("Poetry"))
            advanceUntilIdle()
            assertTrue(("shelf" to "p") in repository.refreshed)
            assertEquals("Inkwell has no “Poetry” category, so it's left out.", vm.state.value.categoryNote)
        }

    @Test
    fun ourOwnCategoriesStandForTheFormatsWhicheverSourceComesFirst() =
        runTest(dispatcher) {
            val vm = CatalogViewModel(twoSources())
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(gutenberg, vovoscan)))
            advanceUntilIdle()
            val groups = vm.state.value.categoryGroups
            // Gutenberg's "book" is the Book format (one choice), its Fiction under its name.
            assertEquals(listOf(null, "Project Gutenberg"), groups.map { it.title })
            assertEquals(listOf("Manga", "Webtoon", "book"), groups[0].choices.map { it.name })
            assertEquals(listOf("Fiction"), groups[1].choices.map { it.name })
        }

    @Test
    fun ourFormatCategoriesFileASourcesTitlesByTheirFormat() =
        runTest(dispatcher) {
            val inkwell = CatalogSource("inkwell", "Inkwell")
            val repository =
                FakeCatalog().apply {
                    remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    remote["vovoscan" to "manga"] = listOf(listOf(title("vovoscan", "v2", ReadingFormat.Manga)))
                    remote["vovoscan" to "webtoon"] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    // A source with no categories, whose catalog names no format (manga by default): one title is a strip
                    // the server learned from imported pages.
                    remote["inkwell" to null] =
                        listOf(listOf(title("inkwell", "i1", ReadingFormat.Manga), title("inkwell", "i2", ReadingFormat.Manga)))
                    serverFormats["inkwell/i2"] = ReadingFormat.Webtoon
                    categories["vovoscan"] =
                        listOf(
                            SourceCategory("manga", "Manga", ReadingFormat.Manga),
                            SourceCategory("webtoon", "Webtoon", ReadingFormat.Webtoon),
                            SourceCategory("staff", "Staff picks"),
                        )
                }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            // Browsing everything never tells the server what a source on the device lists.
            assertEquals(emptyList(), repository.formatsAsked)

            vm.onIntent(CatalogIntent.SelectCategory("Manga"))
            advanceUntilIdle()
            assertEquals(setOf("v2", "i1"), ids(vm).toSet())
            assertNull(vm.state.value.categoryNote)
            assertEquals(2, vm.state.value.sourcesRead)

            // Only the titles of the source filed by format were asked, once each.
            assertEquals(listOf("inkwell" to listOf("i1", "i2")), repository.formatsAsked)

            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(setOf("v1", "i2"), ids(vm).toSet())
            assertEquals(1, repository.formatsAsked.size)

            // A curated category stands for no format: a source without it is left out, as before.
            vm.onIntent(CatalogIntent.SelectCategory("Staff picks"))
            advanceUntilIdle()
            assertEquals("Inkwell has no “Staff picks” category, so it's left out.", vm.state.value.categoryNote)

            vm.onIntent(CatalogIntent.SelectCategory("Manga"))
            vm.onIntent(CatalogIntent.Query("inkwell"))
            advanceUntilIdle()
            assertEquals(
                listOf("i1"),
                vm.state.value.results
                    ?.map { it.id },
            )
        }

    @Test
    fun pullToRefreshAsksAgainForTheFormatsTheServerDidNotKnow() =
        runTest(dispatcher) {
            val inkwell = CatalogSource("inkwell", "Inkwell")
            val repository =
                FakeCatalog().apply {
                    remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    remote["vovoscan" to "webtoon"] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    remote["inkwell" to null] =
                        listOf(listOf(title("inkwell", "i1", ReadingFormat.Manga), title("inkwell", "i2", ReadingFormat.Manga)))
                    serverFormats["inkwell/i2"] = ReadingFormat.Webtoon
                    categories["vovoscan"] = listOf(SourceCategory("webtoon", "Webtoon", ReadingFormat.Webtoon))
                }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(setOf("v1", "i2"), ids(vm).toSet())

            // The server learns a few more titles on each call: pull to refresh asks again for the one it didn't know.
            repository.serverFormats["inkwell/i1"] = ReadingFormat.Webtoon
            vm.onIntent(CatalogIntent.Refresh)
            advanceUntilIdle()
            assertEquals("inkwell" to listOf("i1"), repository.formatsAsked.last())
            assertEquals(setOf("v1", "i1", "i2"), ids(vm).toSet())
        }

    @Test
    fun theWebtoonFilterAsksEveryThirdPartySourceAndItsWordShowsInEveryCategory() =
        runTest(dispatcher) {
            val inkwell = CatalogSource("inkwell", "Inkwell")
            val quill = CatalogSource("quill", "Quill")
            val repository =
                FakeCatalog().apply {
                    remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    remote["vovoscan" to "webtoon"] = listOf(listOf(title("vovoscan", "v1", ReadingFormat.Webtoon)))
                    remote["inkwell" to null] =
                        listOf(listOf(title("inkwell", "i1", ReadingFormat.Manga), title("inkwell", "i2", ReadingFormat.Manga)))
                    remote["quill" to null] =
                        listOf(listOf(title("quill", "q1", ReadingFormat.Manga), title("quill", "q2", ReadingFormat.Manga)))
                    serverFormats["inkwell/i2"] = ReadingFormat.Webtoon
                    serverFormats["quill/q1"] = ReadingFormat.Webtoon
                    categories["vovoscan"] = listOf(SourceCategory("webtoon", "Webtoon", ReadingFormat.Webtoon))
                }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell, quill)))
            advanceUntilIdle()
            // Browsing every category tells the server nothing.
            assertTrue(repository.formatsAsked.isEmpty())

            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            assertEquals(
                setOf("inkwell" to listOf("i1", "i2"), "quill" to listOf("q1", "q2")),
                repository.formatsAsked.toSet(),
            )
            assertEquals(setOf("v1", "i2", "q1"), ids(vm).toSet())

            // Back to every category: the learned strips keep their badge.
            vm.onIntent(CatalogIntent.SelectCategory(null))
            advanceUntilIdle()
            val labels =
                vm.state.value.cards
                    .associate { it.id to it.formatLabel }
            assertEquals("WEBTOON", labels["i2"])
            assertEquals("WEBTOON", labels["q1"])
            assertEquals("MANGA", labels["i1"])

            // Refresh under the filter asks both sources again for what the server didn't know.
            repository.serverFormats["quill/q2"] = ReadingFormat.Webtoon
            vm.onIntent(CatalogIntent.SelectCategory("Webtoon"))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.Refresh)
            advanceUntilIdle()
            assertTrue("inkwell" to listOf("i1") in repository.formatsAsked)
            assertTrue("quill" to listOf("q2") in repository.formatsAsked)
            assertEquals(setOf("v1", "i2", "q1", "q2"), ids(vm).toSet())
        }

    @Test
    fun aSearchResultFollowsTheFormatItsTitlePageLearned() =
        runTest(dispatcher) {
            val inkwell = CatalogSource("inkwell", "Inkwell")
            val strip = title("inkwell", "i1", ReadingFormat.Manga)
            val repository = FakeCatalog().apply { remote["inkwell" to null] = listOf(listOf(strip)) }
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.Query("i1"))
            advanceUntilIdle()
            assertEquals(
                listOf("MANGA"),
                vm.state.value.results
                    ?.map { it.formatLabel },
            )

            // Its page opened from the results: its genres say it is a strip, saved as such.
            repository.detail.value =
                TitleDetail(strip.copy(format = ReadingFormat.Webtoon), "", emptyList(), listOf("Webtoons"), emptyList())
            advanceUntilIdle()
            assertEquals(
                listOf("WEBTOON"),
                vm.state.value.results
                    ?.map { it.formatLabel },
            )
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
                setOf("v1", "g1"),
                vm.state.value.results
                    ?.map { it.id }
                    ?.toSet(),
            )
            assertEquals(2, repository.searched.size)

            // A space around the query (iOS autocorrect adds one) is not sent.
            repository.searched.clear()
            vm.onIntent(CatalogIntent.Query("g1 "))
            advanceUntilIdle()
            assertEquals(listOf("g1", "g1"), repository.searched.map { it.second })
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
