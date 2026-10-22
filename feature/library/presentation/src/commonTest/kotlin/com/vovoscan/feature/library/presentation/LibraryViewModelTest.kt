package com.vovoscan.feature.library.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.LibrarySeries
import com.vovoscan.feature.library.domain.NewChapters
import com.vovoscan.feature.library.domain.TitleRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private class FakeLibrary : LibraryRepository {
        val entries = MutableStateFlow(emptyList<LibraryEntry>())

        override fun observe(): Flow<List<LibraryEntry>> = entries

        override fun isFollowed(
            sourceKey: String,
            titleRef: String,
        ) = entries.map { list -> list.any { it.sourceKey == sourceKey && it.titleRef == titleRef } }

        override suspend fun follow(title: TitleRef) {
            entries.value += LibraryEntry(title.sourceKey, title.id, title.name, title.coverUrl, null, 0, 1L, pendingSync = true)
        }

        override suspend fun unfollow(
            sourceKey: String,
            titleRef: String,
        ) {
            entries.value = entries.value.filterNot { it.sourceKey == sourceKey && it.titleRef == titleRef }
        }

        var refuse = false

        override fun isFavorite(
            sourceKey: String,
            titleRef: String,
        ) = entries.map { list -> list.any { it.sourceKey == sourceKey && it.titleRef == titleRef && it.favorite } }

        override suspend fun setFavorite(
            title: TitleRef,
            favorite: Boolean,
        ): AppResult<Unit> {
            if (refuse) return AppResult.Err(AppError.Rejected("TITLE_NOT_FOUND", "Title not found."))
            val others = entries.value.filterNot { it.sourceKey == title.sourceKey && it.titleRef == title.id }
            val current =
                entries.value.firstOrNull { it.sourceKey == title.sourceKey && it.titleRef == title.id }
                    ?: LibraryEntry(title.sourceKey, title.id, title.name, title.coverUrl, null, 0, 1L, pendingSync = true)
            entries.value = others + current.copy(favorite = favorite, favoritedAt = if (favorite) clock++ else null)
            return AppResult.Ok(Unit)
        }

        private var clock = 10L

        override suspend fun saveProgress(
            sourceKey: String,
            titleRef: String,
            chapterRef: String,
            page: Int,
        ) = Unit

        override suspend fun sync() = AppResult.Err(AppError.Network)

        override suspend fun clearLocal() = Unit
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun followingOfflineShowsWithPendingBadge() =
        runTest {
            val library = FakeLibrary()
            val vm = LibraryViewModel(library)
            val follow = FollowViewModel("gutenberg", "1342", library)
            follow.toggle("Pride and Prejudice", null)
            assertEquals("In your library ✓", follow.state.value.label)
            assertEquals(
                "Not started",
                vm.state.value.rows
                    .single()
                    .progress,
            )
            assertEquals("1 change waiting to sync.", vm.state.value.pendingNotice)
            assertEquals(Freshness.Offline, vm.state.value.freshness)
            assertNull(vm.state.value.error)
        }

    @Test
    fun titlesOfARemovedSourceAreFlagged() =
        runTest {
            val library = FakeLibrary()
            library.entries.value =
                listOf(
                    LibraryEntry("vovoscan", "1", "Kept", null, null, 0, 2L, false),
                    LibraryEntry("demo", "7", "From a plugin", null, null, 0, 1L, false),
                )
            val vm = LibraryViewModel(library)
            // Before the sources are known, nothing is flagged.
            vm.onIntent(LibraryIntent.SourcesChanged(emptyList()))
            assertTrue(
                vm.state.value.rows
                    .none { it.sourceRemoved },
            )
            vm.onIntent(LibraryIntent.SourcesChanged(listOf("vovoscan", "gutenberg")))
            val (kept, removed) = vm.state.value.rows
            assertEquals(false to null, kept.sourceRemoved to kept.sourceNotice)
            assertEquals(true to "Source removed", removed.sourceRemoved to removed.sourceNotice)
            // The plugin comes back: readable again.
            vm.onIntent(LibraryIntent.SourcesChanged(listOf("vovoscan", "demo")))
            assertTrue(
                vm.state.value.rows
                    .none { it.sourceRemoved },
            )
        }

    @Test
    fun theHeartFollowsTheTitleAndTheLibraryListsFavouritesFirst() =
        runTest {
            val library = FakeLibrary()
            library.entries.value =
                listOf(
                    LibraryEntry("vovoscan", "a", "Ink Road", null, "c1", 0, 3L, false, chapterCount = 5, newChapters = 3),
                    LibraryEntry("vovoscan", "b", "Tide Road", null, null, 0, 2L, false),
                )
            val vm = LibraryViewModel(library)
            assertEquals(
                listOf("Following"),
                vm.state.value.sections
                    .map { it.title },
            )
            assertEquals(
                "+3 new",
                vm.state.value.rows
                    .first()
                    .newBadge,
            )

            // A heart on a title not followed yet follows it.
            val follow = FollowViewModel("gutenberg", "1342", library)
            assertEquals("Add to favourites", follow.state.value.favoriteLabel)
            follow.toggleFavorite("Pride and Prejudice", null)
            assertTrue(follow.state.value.favorite && follow.state.value.followed)
            assertEquals("Favourite", follow.state.value.favoriteLabel)
            assertEquals("Remove from favourites", follow.state.value.favoriteAction)
            vm.onIntent(LibraryIntent.ToggleFavorite("vovoscan", "b"))

            val (favorites, following) = vm.state.value.sections
            assertEquals("Favourites", favorites.title)
            // Most recently favourited first.
            assertEquals(listOf("b", "1342"), favorites.rows.map { it.titleId })
            assertEquals(listOf("a"), following.rows.map { it.titleId })

            // Taking the heart back keeps the title followed.
            follow.toggleFavorite("Pride and Prejudice", null)
            assertTrue(follow.state.value.followed)
            assertEquals(false, follow.state.value.favorite)
        }

    @Test
    fun aRefusedHeartSaysSo() =
        runTest {
            val library = FakeLibrary().apply { refuse = true }
            val follow = FollowViewModel("vovoscan", "gone", library)
            follow.toggleFavorite("Gone", null)
            assertEquals(false, follow.state.value.favorite)
            assertEquals("Could not update your favourites.", follow.state.value.error)
        }

    @Test
    fun newChaptersOfASourceReadOnTheDeviceAreCountedFromItsCopy() =
        runTest {
            val library = FakeLibrary()
            library.entries.value = listOf(LibraryEntry("gutenberg", "1342", "Pride and Prejudice", null, "c2", 0, 1L, false))
            val asked = mutableListOf<String?>()
            val vm =
                LibraryViewModel(
                    library,
                    NewChapters { _, _, last ->
                        asked += last
                        4
                    },
                )
            assertEquals(
                "+4 new",
                vm.state.value.rows
                    .single()
                    .newBadge,
            )
            // Counted once per last chapter read.
            library.entries.value = library.entries.value.map { it.copy(updatedAt = 2L) }
            assertEquals(listOf<String?>("c2"), asked)
            // Nothing new: no badge.
            val none = LibraryViewModel(library, NewChapters { _, _, _ -> 0 })
            assertEquals(
                null,
                none.state.value.rows
                    .single()
                    .newBadge,
            )
        }

    @Test
    fun aSeriesIsOneRowWhereTheLatestProgressWins() =
        runTest {
            val library = FakeLibrary()
            library.entries.value =
                listOf(
                    LibraryEntry("vovoscan", "v1", "Ink Road", null, "c1", 2, 100L, pendingSync = false, favorite = true, favoritedAt = 5L),
                    LibraryEntry("gutenberg", "1342", "Pride and Prejudice", null, null, 0, 50L, pendingSync = false),
                    LibraryEntry("inkwell", "i1", "Ink Road", null, "c3", 7, 200L, pendingSync = false),
                )
            val asked = mutableListOf<Pair<String, String>>()
            val series =
                object : LibrarySeries {
                    override fun observe() = MutableStateFlow(mapOf("vovoscan/v1" to "s1", "inkwell/i1" to "s1"))

                    override suspend fun resolve(keys: List<Pair<String, String>>) {
                        asked += keys
                    }
                }
            val vm = LibraryViewModel(library, series = series)
            // The library's titles are asked about (the server knows them: they are followed).
            assertEquals(setOf("vovoscan" to "v1", "gutenberg" to "1342", "inkwell" to "i1"), asked.toSet())
            val rows = vm.state.value.rows
            assertEquals(listOf("inkwell/i1", "gutenberg/1342"), rows.map { it.key })
            assertEquals("Page 8", rows.first().progress)
            // The heart given at one source is the series'.
            assertTrue(rows.first().favorite)

            // Off: taken from every source of the series.
            vm.onIntent(LibraryIntent.ToggleFavorite("inkwell", "i1"))
            assertTrue(library.entries.value.none { it.favorite })

            // Unfollowing the row unfollows the series everywhere.
            vm.onIntent(LibraryIntent.Unfollow("inkwell", "i1"))
            assertEquals(
                listOf("gutenberg/1342"),
                vm.state.value.rows
                    .map { it.key },
            )
        }
}
