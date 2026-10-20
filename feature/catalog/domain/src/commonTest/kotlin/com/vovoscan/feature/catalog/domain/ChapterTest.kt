package com.vovoscan.feature.catalog.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTest {
    @Test
    fun numeroSansDecimaleInutile() {
        assertEquals("98", Chapter("c", 98.0, null, 19, "2026-10-01").label)
        assertEquals("98.5", Chapter("c", 98.5, null, 19, null).label)
    }

    @Test
    fun formatsEtTypes() {
        assertEquals(ReadingFormat.Webtoon, ReadingFormat.fromApi("webtoon"))
        assertEquals(ReadingFormat.Text, ReadingFormat.fromApi("text"))
        assertEquals(ContentKind.Book, ContentKind.fromApi("book"))
    }
}
