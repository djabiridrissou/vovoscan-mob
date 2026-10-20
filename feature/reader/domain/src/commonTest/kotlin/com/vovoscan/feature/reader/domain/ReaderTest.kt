package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderTest {
    @Test
    fun sensDeLectureSelonLeFormat() {
        assertEquals(ReadingMode.Vertical, ReadingMode.fromFormat("webtoon"))
        assertEquals(ReadingMode.PagedRightToLeft, ReadingMode.fromFormat("manga"))
    }

    @Test
    fun proportionsDeLaPage() {
        assertEquals(0.5f, ReaderPage(0, "u", 800, 1600).aspectRatio)
        assertEquals("Chapter 12.5", ReaderChapter("c", "t", "T", 12.5, null, ReadingMode.Vertical, null, null, emptyList()).label)
    }
}
