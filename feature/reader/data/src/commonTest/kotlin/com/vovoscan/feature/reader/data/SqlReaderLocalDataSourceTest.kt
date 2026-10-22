package com.vovoscan.feature.reader.data

import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReaderChapter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlReaderLocalDataSourceTest {
    private fun chapter(
        pages: Int,
        signature: String = "a",
    ) = ReaderChapterDto(
        id = "c1",
        titleId = "t1",
        titleName = "Paper Tide",
        number = 1.0,
        format = "webtoon",
        pages = (0 until pages).map { ReaderPageDto(it, "https://img.test/$it?sig=$signature") },
    )

    /**
     * A chapter saved for the first time (or saved again) is seen with all its pages at once: never the new chapter
     * with the pages it had before (none). The reader clamped its resume page to that empty list (page 1 / 7).
     */
    @Test
    fun aSavedChapterIsSeenWithItsPages() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val local = SqlReaderLocalDataSource(ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema)), dispatcher)
            val seen = mutableListOf<ReaderChapter?>()
            val job = local.observe("rules", "c1").onEach { seen += it }.launchIn(this)
            advanceUntilIdle()
            local.save("rules", chapter(7))
            advanceUntilIdle()
            local.save("rules", chapter(7, signature = "b"))
            advanceUntilIdle()
            job.cancel()
            assertEquals(null, seen.first())
            assertTrue(seen.drop(1).isNotEmpty())
            assertTrue(seen.drop(1).all { it != null && it.pages.size == 7 }, "seen: ${seen.map { it?.pages?.size }}")
            assertEquals(
                "https://img.test/0?sig=b",
                seen
                    .last()!!
                    .pages
                    .first()
                    .url,
            )
        }
}
