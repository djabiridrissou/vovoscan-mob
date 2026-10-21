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
        val state = TitleDetailUiState(sourceKey = "vovoscan", chapters = chapters)
        assertEquals("c2", state.nextChapterId("c1"))
        assertEquals("c3", state.nextChapterId("c2"))
        assertNull(state.nextChapterId("c3"))
        assertNull(state.nextChapterId("unknown"))
        assertEquals("Chapter 2", state.chapterLabel("c2"))
        assertEquals("", state.chapterLabel("unknown"))
    }
}
