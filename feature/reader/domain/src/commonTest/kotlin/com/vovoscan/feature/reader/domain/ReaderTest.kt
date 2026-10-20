package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderTest {
    @Test
    fun readingMode() {
        assertEquals(ReadingMode.Vertical, ReadingMode.from("comic", "webtoon"))
        assertEquals(ReadingMode.PagedRightToLeft, ReadingMode.from("comic", "manga"))
        assertEquals(ReadingMode.Book, ReadingMode.from("book", "text"))
    }

    @Test
    fun labels() {
        assertEquals(0.5f, ReaderPage(0, "u", 800, 1600).aspectRatio)
        val chapter = ReaderChapter("vovoscan", "c", "t", "T", 12.5, null, ReadingMode.Vertical, null, null, emptyList(), null)
        assertEquals("Chapter 12.5", chapter.label)
        assertEquals("Full text", chapter.copy(mode = ReadingMode.Book, name = "Full text").label)
    }
}
