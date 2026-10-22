package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.ContentRating
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SearchPick
import com.vovoscan.feature.catalog.domain.SearchRepository
import com.vovoscan.feature.catalog.domain.SourceCategory
import com.vovoscan.feature.catalog.domain.SuggestReason
import com.vovoscan.feature.catalog.domain.SuggestedTitle
import com.vovoscan.feature.catalog.domain.Suggestions
import com.vovoscan.feature.catalog.domain.TitleDetail
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

/** What the server says, and what it was asked and taught. */
class FakeSearch : SearchRepository {
    var answer: (String, Int) -> Suggestions = { _, _ -> Suggestions() }
    val asked = mutableListOf<Triple<String, Int, ReadingFormat?>>()
    val picks = mutableListOf<SearchPick>()

    /** Suggestions asked with `has=episode`. */
    val askedEpisodes = mutableListOf<String>()
    var sentPending = 0
    var learn: AppResult<Boolean> = AppResult.Ok(true)
    var cleared: AppResult<Int> = AppResult.Ok(3)

    override suspend fun suggest(
        query: String,
        limit: Int,
        format: ReadingFormat?,
        episodes: Boolean,
    ): Suggestions {
        asked += Triple(query, limit, format)
        if (episodes) askedEpisodes += query
        return answer(query, limit)
    }

    override suspend fun recordPick(pick: SearchPick) {
        picks += pick
    }

    override suspend fun sendPending() {
        sentPending++
    }

    override suspend fun learning() = learn

    override suspend fun setLearning(on: Boolean): AppResult<Boolean> = (learn as? AppResult.Err) ?: AppResult.Ok(on).also { learn = it }

    override suspend fun clearHistory() = cleared
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val vovoscan = CatalogSource("vovoscan", "vovoscan")
    private val inkwell = CatalogSource("inkwell", "Inkwell Press")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun catalog() =
        FakeCatalog().apply {
            remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "kingdom"), title("vovoscan", "road")))
            remote["inkwell" to null] = listOf(listOf(title("inkwell", "kings"), title("inkwell", "other")))
            categories["vovoscan"] = listOf(SourceCategory("webtoon", "Webtoon", ReadingFormat.Webtoon))
            remote["vovoscan" to "webtoon"] = listOf(listOf(title("vovoscan", "kingdom", ReadingFormat.Webtoon)))
        }

    private val learned =
        Suggestions(
            recent = listOf("kingdom"),
            queries = listOf("king of ink"),
            titles =
                listOf(
                    SuggestedTitle("inkwell", "kings", "inkwell kings", ContentRating.Safe, reason = SuggestReason.Popular),
                    SuggestedTitle("inkwell", "typo", "Kingz", ContentRating.Safe, ReadingFormat.Webtoon, reason = SuggestReason.Popular),
                    SuggestedTitle("vovoscan", "kingdom", "vovoscan kingdom", ContentRating.Safe, reason = SuggestReason.Yours),
                    // Off the sources this account reads: never shown.
                    SuggestedTitle("elsewhere", "x", "Elsewhere", ContentRating.Safe, reason = SuggestReason.Popular),
                ),
        )

    @Test
    fun aTypedSearchShowsTheServersStripsAndTheSavedCoverWhileBrowsingAsksNothing() =
        runTest(dispatcher) {
            val catalog =
                FakeCatalog().apply {
                    remote["inkwell" to null] =
                        listOf(listOf(title("inkwell", "strip-a"), title("inkwell", "strip-b"), title("inkwell", "plain")))
                    // Learned by the server from imported pages (its backfill), unknown to the device.
                    serverFormats["inkwell/strip-a"] = ReadingFormat.Webtoon
                    // The listing saved a cover; the source's search answer sends none.
                    detail.value =
                        TitleDetail(
                            title("inkwell", "plain").copy(coverUrl = "https://inkwell.example/plain.webp"),
                            "",
                            emptyList(),
                            emptyList(),
                            emptyList(),
                        )
                }
            val search =
                FakeSearch().apply {
                    answer = { q, _ ->
                        if (q.isEmpty()) {
                            Suggestions()
                        } else {
                            Suggestions(
                                titles =
                                    listOf(
                                        SuggestedTitle(
                                            "inkwell",
                                            "strip-b",
                                            "inkwell strip-b",
                                            ContentRating.Safe,
                                            ReadingFormat.Webtoon,
                                            reason = SuggestReason.Popular,
                                        ),
                                    ),
                            )
                        }
                    }
                }
            val vm = CatalogViewModel(catalog, search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(inkwell)))
            advanceUntilIdle()
            assertEquals(emptyList(), catalog.formatsAsked)

            vm.onIntent(CatalogIntent.Query("inkwell"))
            advanceUntilIdle()
            val results =
                vm.state.value.results
                    .orEmpty()
                    .associateBy { it.id }
            assertEquals("WEBTOON", results.getValue("strip-a").formatLabel)
            assertEquals("WEBTOON", results.getValue("strip-b").formatLabel)
            assertEquals("MANGA", results.getValue("plain").formatLabel)
            assertEquals("https://inkwell.example/plain.webp", results.getValue("plain").coverUrl)
            // The results' formats were asked once, for this source's results only.
            assertEquals(listOf("inkwell"), catalog.formatsAsked.map { it.first })
            assertEquals(
                setOf("strip-a", "strip-b", "plain"),
                catalog.formatsAsked
                    .single()
                    .second
                    .toSet(),
            )
        }

    @Test
    fun focusShowsThisAccountsSearchesAndTypingShowsThreeSections() =
        runTest(dispatcher) {
            val search = FakeSearch().apply { answer = { q, _ -> if (q.isEmpty()) Suggestions(recent = listOf("kingdom")) else learned } }
            val vm = CatalogViewModel(catalog(), search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            assertEquals(1, search.sentPending)

            vm.onIntent(CatalogIntent.SearchFocused(true))
            advanceUntilIdle()
            assertTrue(vm.state.value.showSuggestions)
            assertEquals(
                listOf("Your searches"),
                vm.state.value.suggestions
                    .map { it.title },
            )

            vm.onIntent(CatalogIntent.Query("k"))
            vm.onIntent(CatalogIntent.Query("ki"))
            vm.onIntent(CatalogIntent.Query("kin"))
            advanceTimeBy(160)
            // One question for the last spelling only (150 ms debounce), 8 suggestions.
            assertEquals(listOf("", "kin"), search.asked.filter { it.second == SearchRepository.SUGGEST_LIMIT }.map { it.first })
            advanceUntilIdle()
            val sections = vm.state.value.suggestions
            assertEquals(listOf("Your searches", "Others searched", "Titles"), sections.map { it.title })
            val titles = sections.last().rows
            assertEquals(listOf("inkwell kings", "Kingz", "vovoscan kingdom"), titles.map { it.text })
            assertEquals("Popular ✦ Inkwell Press", titles[0].detail)
            assertEquals("You opened it ✦ vovoscan", titles[2].detail)

            vm.onIntent(CatalogIntent.SearchFocused(false))
            // The tap that took the focus still reaches the list.
            assertTrue(vm.state.value.showSuggestions)
            advanceUntilIdle()
            assertFalse(vm.state.value.showSuggestions)
        }

    @Test
    fun aRowTappedAfterTheFieldLostItsFocusIsTheOneOpenedAndTheListCloses() =
        runTest(dispatcher) {
            val search = FakeSearch().apply { answer = { _, _ -> learned } }
            val vm = CatalogViewModel(catalog(), search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SearchFocused(true))
            vm.onIntent(CatalogIntent.Query("kin"))
            advanceUntilIdle()

            // Touch down on "Kingz": the field loses the focus first, the row's tap lands a few frames later.
            vm.onIntent(CatalogIntent.SearchFocused(false))
            advanceTimeBy(100)
            assertTrue(vm.state.value.showSuggestions)
            vm.onIntent(CatalogIntent.TitleOpened("inkwell", "typo"))
            // Closed at once, so nothing is left over the title page.
            assertFalse(vm.state.value.showSuggestions)
            advanceUntilIdle()
            assertEquals(listOf("typo"), search.picks.map { it.titleRef })
            assertFalse(vm.state.value.showSuggestions)

            // Focus back within the grace: the list stays.
            vm.onIntent(CatalogIntent.SearchFocused(true))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SearchFocused(false))
            advanceTimeBy(100)
            vm.onIntent(CatalogIntent.SearchFocused(true))
            advanceUntilIdle()
            assertTrue(vm.state.value.showSuggestions)
        }

    @Test
    fun learnedTitlesLeadTheResultsOnceEachWithTheirBadge() =
        runTest(dispatcher) {
            val search = FakeSearch().apply { answer = { _, _ -> learned } }
            val vm = CatalogViewModel(catalog(), search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.Query("king"))
            advanceUntilIdle()
            val results = vm.state.value.results!!
            // Learned first in their order (a title no source returned for this spelling too), then the rest, once each.
            assertEquals(listOf("kings", "typo", "kingdom"), results.map { it.id })
            assertEquals(listOf(true, true, false), results.map { it.popular })
            assertEquals("WEBTOON", results[1].formatLabel)
            assertTrue(search.asked.any { it.first == "king" && it.second == SearchRepository.RESULTS_LIMIT })
            assertEquals("Popular", vm.state.value.popularLabel)
        }

    @Test
    fun openingAResultTeachesTheSearchBrowsingDoesNot() =
        runTest(dispatcher) {
            val search = FakeSearch().apply { answer = { _, _ -> learned } }
            val vm = CatalogViewModel(catalog(), search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            // Plain browsing: nothing.
            vm.onIntent(CatalogIntent.TitleOpened("inkwell", "kings"))
            advanceUntilIdle()
            assertTrue(search.picks.isEmpty())

            vm.onIntent(CatalogIntent.Query(" King "))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.TitleOpened("inkwell", "other"))
            vm.onIntent(CatalogIntent.TitleOpened("inkwell", "typo"))
            advanceUntilIdle()
            assertEquals(1, search.picks.size)
            val pick = search.picks.single()
            assertEquals("King", pick.query)
            assertEquals("inkwell" to "typo", pick.sourceKey to pick.titleRef)
            assertEquals("Kingz", pick.titleName)
            // The server's word on how it reads; a title it doesn't know has none (never a guess).
            assertEquals(ReadingFormat.Webtoon, pick.format)
        }

    @Test
    fun aSuggestedTitleOpenedFromUnderTheFieldIsAPick() =
        runTest(dispatcher) {
            val search = FakeSearch().apply { answer = { _, _ -> learned } }
            val vm = CatalogViewModel(catalog(), search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SearchFocused(true))
            vm.onIntent(CatalogIntent.Query("ki"))
            advanceTimeBy(200)
            search.answer = { _, _ -> learned }
            vm.onIntent(CatalogIntent.TitleOpened("inkwell", "kings"))
            advanceUntilIdle()
            assertEquals(listOf("ki"), search.picks.map { it.query })
            assertNull(search.picks.single().format)
        }

    @Test
    fun choosingASearchRunsItAtOnce() =
        runTest(dispatcher) {
            val catalog = catalog()
            val search = FakeSearch()
            val vm = CatalogViewModel(catalog, search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SearchFocused(true))
            vm.onIntent(CatalogIntent.UseSearch("road"))
            advanceTimeBy(1)
            assertEquals("road", vm.state.value.query)
            assertFalse(vm.state.value.searchFocused)
            advanceUntilIdle()
            assertEquals(
                listOf("road"),
                vm.state.value.results!!
                    .map { it.id },
            )
        }

    @Test
    fun theFormatFilterGoesWithEveryQuestion() =
        runTest(dispatcher) {
            val catalog = catalog().apply { filter.value = filter.value.copy(categoryName = "Webtoon") }
            val search = FakeSearch()
            val vm = CatalogViewModel(catalog, search = search)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.SearchFocused(true))
            vm.onIntent(CatalogIntent.Query("kingdom"))
            advanceUntilIdle()
            assertTrue(search.asked.isNotEmpty())
            assertTrue(search.asked.all { it.third == ReadingFormat.Webtoon })
            vm.onIntent(CatalogIntent.TitleOpened("vovoscan", "kingdom"))
            advanceUntilIdle()
            assertEquals(ReadingFormat.Webtoon, search.picks.single().format)
        }

    @Test
    fun theSearchCardStopsLearningAndClearsTheHistory() =
        runTest(dispatcher) {
            val search = FakeSearch()
            val vm = SearchPrivacyViewModel(search)
            advanceUntilIdle()
            assertEquals(true, vm.state.value.learning)
            assertEquals("Learning from your searches.", vm.state.value.status)
            assertEquals("Stop learning from my searches", vm.state.value.toggleLabel)
            vm.onIntent(SearchPrivacyIntent.Toggle)
            advanceUntilIdle()
            assertEquals(false, vm.state.value.learning)
            assertEquals("Learn from my searches", vm.state.value.toggleLabel)
            vm.onIntent(SearchPrivacyIntent.ClearHistory)
            advanceUntilIdle()
            assertEquals("Search history cleared.", vm.state.value.notice)

            // Offline: the choice stays unknown, nothing can be changed, the reason shows.
            val offline = SearchPrivacyViewModel(FakeSearch().apply { learn = AppResult.Err(AppError.Network) })
            advanceUntilIdle()
            assertNull(offline.state.value.learning)
            assertFalse(offline.state.value.canAct)
            assertTrue(offline.state.value.error != null)
        }
}
