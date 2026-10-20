package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTest {
    @Test
    fun numeroSansDecimaleInutile() {
        assertEquals("98", Chapter("c", 98.0, null, 19, "2026-10-01").label)
        assertEquals("98.5", Chapter("c", 98.5, null, 19, "2026-10-01").label)
    }

    @Test
    fun formatDeLecture() {
        assertEquals(ReadingFormat.Webtoon, ReadingFormat.fromApi("webtoon"))
        assertEquals(ReadingFormat.Manga, ReadingFormat.fromApi("manga"))
    }
}
