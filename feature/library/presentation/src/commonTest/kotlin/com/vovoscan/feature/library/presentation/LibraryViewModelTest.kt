package com.vovoscan.feature.library.presentation

import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
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
}
