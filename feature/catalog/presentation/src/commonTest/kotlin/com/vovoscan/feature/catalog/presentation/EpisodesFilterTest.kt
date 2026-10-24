package com.vovoscan.feature.catalog.presentation

import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.EpisodeFeature
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.SuggestedTitle
import com.vovoscan.feature.catalog.domain.Suggestions
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodesFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private val vovoscan = CatalogSource("vovoscan", "vovoscan")
    private val inkwell = CatalogSource("inkwell", "Inkwell")
    private val video = EpisodeFeature { flowOf(true) }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun watchable(
        source: String,
        id: String,
        count: Int,
    ) = title(source, id).copy(episodeCount = count)

    /** vovoscan lists v1 (2 episodes) and v2; Inkwell lists i1 and i2 on the device; the server knows i1 has one. */
    private fun catalog() =
        FakeCatalog().apply {
            remote["vovoscan" to null] = listOf(listOf(watchable("vovoscan", "v1", 2), title("vovoscan", "v2")))
            remote["vovoscan" to EPISODES] = listOf(listOf(watchable("vovoscan", "v1", 2)))
            remote["inkwell" to null] = listOf(listOf(title("inkwell", "i1"), title("inkwell", "i2")))
            remote["inkwell" to EPISODES] = listOf(listOf(watchable("inkwell", "i1", 1)))
        }

    @Test
    fun withoutTheVideoFeatureThereIsNoChipNorBadge() =
        runTest(dispatcher) {
            val repository = catalog()
            val vm = CatalogViewModel(repository)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            assertFalse(vm.state.value.showEpisodesFilter)
            assertTrue(
                vm.state.value.cards
                    .all { it.episodeBadge == null },
            )

            vm.onIntent(CatalogIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertFalse(repository.filter.value.episodesOnly)
            assertFalse(vm.state.value.episodesOnly)
            assertEquals(
                setOf("v1", "v2", "i1", "i2"),
                vm.state.value.visible
                    .mapTo(HashSet()) { it.id },
            )
        }

    @Test
    fun theChipKeepsTheTitlesWithAnEpisodeFromEverySourceAndIsRemembered() =
        runTest(dispatcher) {
            val repository = catalog()
            val vm = CatalogViewModel(repository, video = video)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            assertTrue(vm.state.value.showEpisodesFilter)
            assertEquals("▶ Episodes", vm.state.value.episodesLabel)
            assertEquals(
                "▶ 2",
                vm.state.value.cards
                    .single { it.id == "v1" }
                    .episodeBadge,
            )
            assertNull(
                vm.state.value.cards
                    .single { it.id == "v2" }
                    .episodeBadge,
            )

            vm.onIntent(CatalogIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertTrue(vm.state.value.episodesOnly)
            assertTrue(repository.filter.value.episodesOnly)
            // Both listings come from the server (a source read on the device: the titles imported from it).
            assertTrue(("vovoscan" to EPISODES) in repository.refreshed && ("inkwell" to EPISODES) in repository.refreshed)
            assertEquals(
                setOf("v1", "i1"),
                vm.state.value.visible
                    .mapTo(HashSet()) { it.id },
            )
            assertEquals(
                "▶ 1",
                vm.state.value.visible
                    .single { it.id == "i1" }
                    .episodeBadge,
            )

            // Remembered: a catalog opened again starts filtered.
            val again = CatalogViewModel(repository, video = video)
            again.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            assertTrue(again.state.value.episodesOnly)
            assertEquals(
                setOf("v1", "i1"),
                again.state.value.visible
                    .mapTo(HashSet()) { it.id },
            )

            vm.onIntent(CatalogIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertFalse(vm.state.value.episodesOnly)
            assertEquals(4, vm.state.value.visible.size)
        }

    @Test
    fun theFeatureTurningOffDropsTheFilterAndTheBadges() =
        runTest(dispatcher) {
            val repository = catalog()
            val on = MutableStateFlow(true)
            val vm = CatalogViewModel(repository, video = EpisodeFeature { on })
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertEquals(
                listOf("v1"),
                vm.state.value.visible
                    .map { it.id },
            )

            on.value = false
            advanceUntilIdle()
            assertFalse(vm.state.value.showEpisodesFilter)
            assertFalse(vm.state.value.episodesOnly)
            assertEquals(
                listOf("v1", "v2"),
                vm.state.value.visible
                    .map { it.id },
            )
            assertTrue(
                vm.state.value.cards
                    .all { it.episodeBadge == null },
            )
        }

    @Test
    fun aSearchUnderTheChipAsksForTitlesWithAnEpisode() =
        runTest(dispatcher) {
            val repository = catalog()
            val search =
                FakeSearch().apply {
                    answer = { _, _ ->
                        Suggestions(titles = listOf(SuggestedTitle("vovoscan", "v1", "vovoscan v1", episodeCount = 2)))
                    }
                }
            val vm = CatalogViewModel(repository, search = search, video = video)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.ToggleEpisodes)
            advanceUntilIdle()
            vm.onIntent(CatalogIntent.Query("v"))
            advanceUntilIdle()
            assertTrue("v" in search.askedEpisodes)
            assertTrue(repository.searched.all { it.third == EPISODES })
            assertEquals(
                listOf("v1"),
                vm.state.value.results
                    ?.map { it.id },
            )
            assertEquals(
                "▶ 2",
                vm.state.value.results
                    ?.single()
                    ?.episodeBadge,
            )
        }

    private fun detail(vararg languages: List<String>) =
        TitleDetail(
            summary = TitleSummary("vovoscan", "t1", "Ink Road", null, ContentKind.Comic, ReadingFormat.Webtoon, "ongoing", null, 2),
            synopsis = "",
            authors = emptyList(),
            genres = emptyList(),
            // Newest to oldest.
            chapters =
                languages.mapIndexed {
                    i,
                    l,
                    ->
                    Chapter("c${languages.size - i}", (languages.size - i).toDouble(), null, 10, null, l)
                },
        )

    @Test
    fun chapterRowsCarryTheirEpisodeLanguagesAndTheChipKeepsThem() =
        runTest(dispatcher) {
            val repository =
                FakeCatalog().apply {
                    // c3: five languages; c2: none; c1: the original only.
                    detail.value = detail(listOf("fr", "", "en", "ja", "ko"), emptyList(), listOf(""))
                }
            val vm = TitleDetailViewModel("vovoscan", "t1", repository, video = video)
            advanceUntilIdle()
            val rows =
                vm.state.value.chapters
                    .associateBy { it.id }
            // The original first, three badges then the rest counted.
            assertEquals(listOf("▶ Original", "▶ Français", "▶ English", "+2"), rows.getValue("c3").episodes)
            assertEquals("Watch as an episode in: Original, Français, English, 日本語, 한국어", rows.getValue("c3").episodesA11y)
            assertEquals(emptyList(), rows.getValue("c2").episodes)
            assertNull(rows.getValue("c2").episodesA11y)
            assertEquals(listOf("▶ Original"), rows.getValue("c1").episodes)
            assertTrue(vm.state.value.showEpisodesFilter)
            assertEquals("▶ Episodes (2)", vm.state.value.episodesChip)

            vm.onIntent(TitleDetailIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertTrue(vm.state.value.episodesOnly)
            assertEquals(
                listOf("c1", "c3"),
                vm.state.value.chapters
                    .map { it.id },
            )
            // Reading order and "Start reading" still see every chapter.
            assertEquals(listOf("c1", "c2", "c3"), vm.state.value.readingOrder)
            assertEquals("c2", vm.state.value.nextChapterId("c1"))

            vm.onIntent(TitleDetailIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertEquals(
                listOf("c1", "c2", "c3"),
                vm.state.value.chapters
                    .map { it.id },
            )
        }

    @Test
    fun noChipWithoutAChapterWithAnEpisodeNorWithoutTheFeature() =
        runTest(dispatcher) {
            val none = FakeCatalog().apply { detail.value = detail(emptyList(), emptyList()) }
            val vm = TitleDetailViewModel("vovoscan", "t1", none, video = video)
            advanceUntilIdle()
            assertFalse(vm.state.value.showEpisodesFilter)
            vm.onIntent(TitleDetailIntent.ToggleEpisodes)
            advanceUntilIdle()
            assertFalse(vm.state.value.episodesOnly)

            val withEpisodes = FakeCatalog().apply { detail.value = detail(listOf("")) }
            val off = TitleDetailViewModel("vovoscan", "t1", withEpisodes)
            advanceUntilIdle()
            assertFalse(off.state.value.showEpisodesFilter)
            assertTrue(
                off.state.value.chapters
                    .single()
                    .episodes
                    .isEmpty(),
            )
        }
}
