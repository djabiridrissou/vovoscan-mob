package com.vovoscan.feature.catalog.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleDetailUiStateTest {
    // Newest first, as the title page lists them.
    private val chapters = listOf(ChapterRow("c3", "Chapter 3", ""), ChapterRow("c2", "Chapter 2", ""), ChapterRow("c1", "Chapter 1", ""))

    @Test
    fun episodesAreOfferedOnlyForComicsOfOurCatalog() {
        assertTrue(TitleDetailUiState(sourceKey = "vovoscan", chapters = chapters).offersEpisodes)
        assertFalse(TitleDetailUiState(sourceKey = "vovoscan", isBook = true, chapters = chapters).offersEpisodes)
        assertFalse(TitleDetailUiState(sourceKey = "gutenberg", chapters = chapters).offersEpisodes)
        assertFalse(TitleDetailUiState(sourceKey = "vovoscan").offersEpisodes)
    }

    @Test
    fun theNextChapterIsTheOneAfterInReadingOrder() {
        // Whatever order the list shows, the next chapter follows the reading order.
        val state =
            TitleDetailUiState(sourceKey = "vovoscan", chapters = chapters, newestFirst = true, readingOrder = listOf("c1", "c2", "c3"))
        assertEquals("c2", state.nextChapterId("c1"))
        assertEquals("c3", state.nextChapterId("c2"))
        assertNull(state.nextChapterId("c3"))
        assertNull(state.nextChapterId("unknown"))
        assertEquals("Chapter 2", state.chapterLabel("c2"))
        assertEquals("", state.chapterLabel("unknown"))
    }

    @Test
    fun aChapterNameThatRepeatsTheNumberIsShownOnce() {
        assertEquals("Chapter 2", chapterLabel("2", "Chapter 2"))
        assertEquals("Chapter 2", chapterLabel("2", "ch. 2"))
        assertEquals("Chapter 2", chapterLabel("2", " 2 "))
        assertEquals("Chapter 2", chapterLabel("2", null))
        assertEquals("Chapter 2 · The Well", chapterLabel("2", "The Well"))
        assertEquals("Chapter 2 · Chapter 20", chapterLabel("2", "Chapter 20"))
    }
}
