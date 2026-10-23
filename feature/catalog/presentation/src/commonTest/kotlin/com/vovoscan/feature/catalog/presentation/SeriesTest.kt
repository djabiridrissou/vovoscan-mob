package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.catalog.domain.Chapter
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.EnabledSources
import com.vovoscan.feature.catalog.domain.LibraryMarks
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.Series
import com.vovoscan.feature.catalog.domain.SeriesMember
import com.vovoscan.feature.catalog.domain.SeriesRepository
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server's series, answered for the titles asked about only. */
class FakeSeries(
    private val server: List<Series> = emptyList(),
) : SeriesRepository {
    val known = MutableStateFlow(emptyMap<String, Series>())
    val asked = mutableListOf<String>()

    override fun observe(): Flow<Map<String, Series>> = known

    override suspend fun resolve(titles: List<MarkedTitle>): AppResult<Unit> {
        asked += titles.map { it.key }
        known.update { now ->
            now +
                titles.mapNotNull { t -> server.firstOrNull { s -> s.members.any { it.key == t.key } }?.let { t.key to it } }
        }
        return AppResult.Ok(Unit)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesTest {
    private val dispatcher = StandardTestDispatcher()
    private val vovoscan = CatalogSource("vovoscan", "vovoscan")
    private val inkwell = CatalogSource("inkwell", "Inkwell")
    private val ours = SeriesMember("vovoscan", "v1", "Ink Road", 1)
    private val theirs = SeriesMember("inkwell", "i1", "Ink Road", 3)
    private val inkRoad = Series("s1", listOf(theirs, ours))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun catalog() =
        FakeCatalog().apply {
            remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "v1"), title("vovoscan", "v2")))
            remote["inkwell" to null] = listOf(listOf(title("inkwell", "i1"), title("inkwell", "i2")))
        }

    @Test
    fun aSeriesIsOneCardWithItsOtherSources() =
        runTest(dispatcher) {
            val series = FakeSeries(listOf(inkRoad))
            // i1 is followed: the server knows it from this account.
            val favorites = FakeFavorites().apply { marks.value = LibraryMarks(followed = setOf("inkwell/i1")) }
            val vm = CatalogViewModel(catalog(), favorites = favorites, seriesRepository = series)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            val cards = vm.state.value.visible
            // The primary (most chapters) stands for the series; the other card is gone.
            assertEquals(listOf("inkwell/i1"), cards.filter { it.key in setOf("vovoscan/v1", "inkwell/i1") }.map { it.key })
            assertEquals("+ 1 source", cards.single { it.key == "inkwell/i1" }.moreSources)
            assertEquals(4 - 1, cards.size)
        }

    @Test
    fun browsingNeverAsksAboutWhatADeviceSourceLists() =
        runTest(dispatcher) {
            val series = FakeSeries(listOf(inkRoad))
            val vm = CatalogViewModel(catalog(), seriesRepository = series)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            // Our own titles only: inkwell's listing stays on the device.
            assertTrue(series.asked.all { it.startsWith("vovoscan/") }, series.asked.toString())
            assertTrue("vovoscan/v1" in series.asked)

            // A typed search is already sent to the server: its results may be resolved.
            vm.onIntent(CatalogIntent.Query("i"))
            advanceUntilIdle()
            assertTrue("inkwell/i1" in series.asked)
        }

    @Test
    fun aSourceTurnedOffLeavesTheSeriesCardToTheOthers() =
        runTest(dispatcher) {
            val series = FakeSeries(listOf(inkRoad))
            val favorites = FakeFavorites().apply { marks.value = LibraryMarks(followed = setOf("inkwell/i1")) }
            val vm = CatalogViewModel(catalog(), favorites = favorites, seriesRepository = series)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan)))
            advanceUntilIdle()
            val v1 =
                vm.state.value.visible
                    .single { it.key == "vovoscan/v1" }
            assertEquals(null, v1.moreSources)
        }

    private fun ch(
        id: String,
        n: Double,
    ) = Chapter(id, n, null, 10, null)

    private fun detail(
        source: String,
        id: String,
        chapters: List<Chapter>,
    ) = TitleDetail(
        summary = TitleSummary(source, id, "Ink Road", null, ContentKind.Comic, ReadingFormat.Webtoon, "ongoing"),
        synopsis = "",
        authors = emptyList(),
        genres = emptyList(),
        chapters = chapters,
    )

    @Test
    fun theTitlePageMergesTheSeriesChaptersByNumber() =
        runTest(dispatcher) {
            val repository =
                FakeCatalog().apply {
                    detail.value = detail("vovoscan", "v1", listOf(ch("o1", 1.0)))
                    otherDetails.value =
                        mapOf("inkwell/i1" to detail("inkwell", "i1", listOf(ch("i3", 3.0), ch("i2", 2.0), ch("i1c", 1.0))))
                }
            val series = FakeSeries(listOf(inkRoad))
            val vm =
                TitleDetailViewModel(
                    "vovoscan",
                    "v1",
                    repository,
                    seriesRepository = series,
                    enabledSources = EnabledSources { flowOf(mapOf("vovoscan" to "vovoscan", "inkwell" to "Inkwell")) },
                )
            advanceUntilIdle()
            assertEquals(listOf("vovoscan/v1"), series.asked)
            assertTrue("inkwell/i1" in repository.titlesRefreshed)
            val rows = vm.state.value.chapters
            assertEquals(listOf("o1", "i2", "i3"), rows.map { it.id })
            assertEquals(listOf(null, "Inkwell", "Inkwell"), rows.map { it.sourceBadge })
            assertEquals(listOf("vovoscan", "inkwell", "inkwell"), rows.map { it.sourceKey })
            assertEquals(
                listOf("i1"),
                vm.state.value.alsoOn
                    .map { it.titleId },
            )
            assertEquals(
                "Inkwell ✦ 3 chapters",
                vm.state.value.alsoOn
                    .single()
                    .label,
            )

            // Pills only on this page's own chapters.
            vm.onIntent(TitleDetailIntent.Translate("i2"))
            advanceUntilIdle()
            assertEquals(null, vm.state.value.open)
        }

    @Test
    fun aMemberAtASourceTurnedOffIsLeftOut() =
        runTest(dispatcher) {
            val repository =
                FakeCatalog().apply {
                    detail.value = detail("vovoscan", "v1", listOf(ch("o1", 1.0)))
                    otherDetails.value = mapOf("inkwell/i1" to detail("inkwell", "i1", listOf(ch("i2", 2.0))))
                }
            val vm =
                TitleDetailViewModel(
                    "vovoscan",
                    "v1",
                    repository,
                    seriesRepository = FakeSeries(listOf(inkRoad)),
                    enabledSources = EnabledSources { flowOf(mapOf("vovoscan" to "vovoscan")) },
                )
            advanceUntilIdle()
            assertEquals(
                listOf("o1"),
                vm.state.value.chapters
                    .map { it.id },
            )
            assertTrue(
                vm.state.value.alsoOn
                    .isEmpty(),
            )
            assertTrue("inkwell/i1" !in repository.titlesRefreshed)
        }

    @Test
    fun aDeviceSourceTitlePageAsksOnlyOnceTheServerKnowsIt() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { detail.value = detail("inkwell", "i1", listOf(ch("i1c", 1.0))) }
            val series = FakeSeries(listOf(inkRoad))
            val favorites = FakeFavorites()
            TitleDetailViewModel("inkwell", "i1", repository, favorites = favorites, seriesRepository = series)
            advanceUntilIdle()
            assertTrue(series.asked.isEmpty())
            favorites.marks.value = LibraryMarks(followed = setOf("inkwell/i1"))
            advanceUntilIdle()
            assertEquals(listOf("inkwell/i1"), series.asked)
        }
}
