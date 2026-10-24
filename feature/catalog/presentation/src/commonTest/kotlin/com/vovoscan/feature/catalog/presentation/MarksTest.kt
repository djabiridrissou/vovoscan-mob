package com.vovoscan.feature.catalog.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.Favorites
import com.vovoscan.feature.catalog.domain.LibraryMarks
import com.vovoscan.feature.catalog.domain.MarkedTitle
import com.vovoscan.feature.catalog.domain.RatingsRepository
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.TitleDetail
import com.vovoscan.feature.catalog.domain.TitleStats
import com.vovoscan.feature.catalog.domain.TitleSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeFavorites : Favorites {
    val marks = MutableStateFlow(LibraryMarks())
    var refuse = false
    val sent = mutableListOf<Pair<String, Boolean>>()

    override fun observe(): Flow<LibraryMarks> = marks

    override suspend fun set(
        title: MarkedTitle,
        name: String,
        coverUrl: String?,
        favorite: Boolean,
    ): AppResult<Unit> {
        sent += title.key to favorite
        if (refuse) return AppResult.Err(AppError.Rejected("TITLE_NOT_FOUND", "Title not found."))
        marks.update {
            it.copy(favorites = if (favorite) it.favorites + title.key else it.favorites - title.key, followed = it.followed + title.key)
        }
        return AppResult.Ok(Unit)
    }
}

class FakeRatings : RatingsRepository {
    val mine = MutableStateFlow(emptyMap<String, Double>())
    val stats = MutableStateFlow(emptyMap<String, TitleStats>())

    /** What the server would answer, by key. */
    val server = mutableMapOf<String, TitleStats>()
    val asked = mutableListOf<List<String>>()
    var refuse = false

    override fun observeRatings(): Flow<Map<String, Double>> = mine

    override fun observeStats(): Flow<Map<String, TitleStats>> = stats

    override suspend fun refreshStats(titles: List<MarkedTitle>): AppResult<Unit> {
        asked += titles.map { it.key }
        stats.update { it + titles.mapNotNull { t -> server[t.key]?.let { t.key to it } } }
        return AppResult.Ok(Unit)
    }

    override suspend fun rate(
        title: MarkedTitle,
        titleName: String,
        score: Double?,
    ): AppResult<Unit> {
        if (refuse) return AppResult.Err(AppError.Rejected("TITLE_NOT_FOUND", "Title not found."))
        mine.update { if (score == null) it - title.key else it + (title.key to score) }
        return AppResult.Ok(Unit)
    }

    override suspend fun sync() = AppResult.Ok(Unit)

    override suspend fun clearLocal() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class MarksTest {
    private val dispatcher = StandardTestDispatcher()
    private val vovoscan = CatalogSource("vovoscan", "vovoscan")
    private val inkwell = CatalogSource("inkwell", "Inkwell")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun catalog() =
        FakeCatalog().apply {
            remote["vovoscan" to null] = listOf(listOf(title("vovoscan", "v1")))
            remote["inkwell" to null] = listOf(listOf(title("inkwell", "i1"), title("inkwell", "i2")))
        }

    @Test
    fun cardsCarryTheHeartAndEveryonesNumbers() =
        runTest(dispatcher) {
            val favorites = FakeFavorites()
            val ratings = FakeRatings().apply { stats.value = mapOf("vovoscan/v1" to TitleStats(4.333, 12, 1_234)) }
            val vm = CatalogViewModel(catalog(), favorites = favorites, ratings = ratings)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            val v1 =
                vm.state.value.visible
                    .single { it.id == "v1" }
            assertEquals("★ 4.3 (12)", v1.ratingLine)
            assertEquals("♥ 1.2k", v1.favoriteLine)
            assertEquals("Add to favourites", v1.favoriteAction)

            vm.onIntent(CatalogIntent.ToggleFavorite("inkwell", "i1"))
            advanceUntilIdle()
            val i1 =
                vm.state.value.visible
                    .single { it.id == "i1" }
            assertTrue(i1.favorite)
            assertEquals("Remove from favourites", i1.favoriteAction)
            assertEquals(listOf("inkwell/i1" to true), favorites.sent)

            favorites.refuse = true
            vm.onIntent(CatalogIntent.ToggleFavorite("inkwell", "i1"))
            advanceUntilIdle()
            assertEquals("Could not update your favourites.", vm.state.value.favoriteError)
        }

    @Test
    fun browsingASourceReadOnTheDeviceNeverSaysWhatItListsButTheLibraryAndATypedSearchDo() =
        runTest(dispatcher) {
            val favorites = FakeFavorites().apply { marks.value = LibraryMarks(followed = setOf("inkwell/i2")) }
            val ratings = FakeRatings().apply { server["inkwell/i2"] = TitleStats(3.5, 4, null) }
            val vm = CatalogViewModel(catalog(), favorites = favorites, ratings = ratings)
            vm.onIntent(CatalogIntent.SourcesChanged(listOf(vovoscan, inkwell)))
            advanceUntilIdle()
            // Only the followed title of the device source; never our own (its listing carries the numbers), never i1.
            assertEquals(listOf("inkwell/i2"), ratings.asked.flatten())
            assertEquals(
                "★ 3.5 (4)",
                vm.state.value.visible
                    .single { it.id == "i2" }
                    .ratingLine,
            )
            assertNull(
                vm.state.value.visible
                    .single { it.id == "i2" }
                    .favoriteLine,
            )

            // A typed search is sent to the server already: its results' numbers are asked.
            vm.onIntent(CatalogIntent.Query("i1"))
            advanceUntilIdle()
            assertEquals(listOf("inkwell/i2", "inkwell/i1"), ratings.asked.flatten())
        }

    private fun detail(source: String) =
        TitleDetail(
            summary = TitleSummary(source, "t1", "Ink Road", null, ContentKind.Comic, ReadingFormat.Webtoon, "ongoing"),
            synopsis = "",
            authors = emptyList(),
            genres = emptyList(),
            chapters = emptyList(),
        )

    @Test
    fun theStarsRateInHalfStepsAndClear() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { detail.value = detail("vovoscan") }
            val ratings = FakeRatings().apply { server["vovoscan/t1"] = TitleStats(4.26, 3, 7) }
            val vm = TitleDetailViewModel("vovoscan", "t1", repository, ratings = ratings)
            advanceUntilIdle()
            // Our catalog's title: its numbers are asked on opening.
            assertEquals(listOf(listOf("vovoscan/t1")), ratings.asked)
            assertEquals("★ 4.3 (3)", vm.state.value.ratingLine)
            assertEquals("♥ 7", vm.state.value.favoriteLine)
            assertEquals("Rated 4.3 out of 5 by 3 readers", vm.state.value.ratedBy)
            assertEquals("Not rated", vm.state.value.ratingValue)
            assertEquals("Tap a star to rate", vm.state.value.rateHint)

            // The left half of the fifth star: 4.5.
            vm.onIntent(TitleDetailIntent.RateAt(0.85))
            advanceUntilIdle()
            assertEquals(4.5, vm.state.value.myRating)
            assertEquals(listOf(1.0, 1.0, 1.0, 1.0, 0.5), vm.state.value.starFills)
            assertEquals("4.5 out of 5 stars", vm.state.value.ratingValue)
            assertNull(vm.state.value.rateHint)
            assertEquals(listOf(1.0, 0.5, 0.0, 0.0, 0.0), vm.state.value.previewFills(0.25))

            vm.onIntent(TitleDetailIntent.StepRating(up = true))
            advanceUntilIdle()
            assertEquals(5.0, vm.state.value.myRating)
            vm.onIntent(TitleDetailIntent.ClearRating)
            advanceUntilIdle()
            assertNull(vm.state.value.myRating)

            ratings.refuse = true
            vm.onIntent(TitleDetailIntent.RateAt(0.1))
            advanceUntilIdle()
            assertNull(vm.state.value.myRating)
            assertEquals("Could not save your rating.", vm.state.value.ratingError)
        }

    @Test
    fun aTitleOfASourceReadOnTheDeviceIsAskedOnlyOnceTheServerKnowsIt() =
        runTest(dispatcher) {
            val repository = FakeCatalog().apply { detail.value = detail("inkwell") }
            val ratings = FakeRatings()
            val favorites = FakeFavorites()
            val vm = TitleDetailViewModel("inkwell", "t1", repository, ratings = ratings, favorites = favorites)
            advanceUntilIdle()
            assertTrue(ratings.asked.isEmpty())
            // Rated (the rating names it to the server): its numbers may be asked now.
            vm.onIntent(TitleDetailIntent.RateAt(1.0))
            advanceUntilIdle()
            assertEquals(listOf(listOf("inkwell/t1")), ratings.asked)
        }
}

class MarkTextTest {
    @Test
    fun compactCounts() {
        assertEquals("950", MarkText.compactCount(950))
        assertEquals("1.2k", MarkText.compactCount(1_299))
        assertEquals("2k", MarkText.compactCount(2_000))
        assertEquals("46k", MarkText.compactCount(46_780))
        assertEquals("2M", MarkText.compactCount(2_000_000))
        assertEquals("1.5M", MarkText.compactCount(1_540_000))
    }

    @Test
    fun nothingBelowThreeAccounts() {
        assertNull(MarkText.ratingLine(TitleStats(null, null, null)))
        assertNull(MarkText.favoriteLine(TitleStats(4.0, 3, null)))
        assertNull(MarkText.ratingLine(null))
    }
}
