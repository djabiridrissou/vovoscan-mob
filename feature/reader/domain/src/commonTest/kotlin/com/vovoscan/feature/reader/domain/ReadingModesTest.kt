package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingModesTest {
    private val none = ReaderPreferences()

    @Test
    fun formatRule() {
        assertEquals(ReadingMode.LongStrip, ReadingMode.from("comic", "webtoon"))
        assertEquals(ReadingMode.LongStrip, ReadingMode.from("comic", "manga"))
        assertEquals(ReadingMode.LongStrip, ReadingMode.from("comic", "anything"))
        assertEquals(ReadingMode.Book, ReadingMode.from("book", "text"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("webtoon"))
    }

    @Test
    fun seriesWinsThenFormatDefaultThenRule() {
        val webtoon = ReadingMode.LongStrip
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, null, none))
        val prefs = none.copy(webtoonMode = ReadingMode.LongStripGaps, mangaMode = ReadingMode.PagedLeftToRight)
        assertEquals(ReadingMode.LongStripGaps, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, null, prefs))
        // A webtoon never turns pages, even chosen for the series; its gap is a choice.
        assertEquals(ReadingMode.LongStripGaps, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, ReadingMode.PagedVertical, prefs))
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, ReadingMode.LongStrip, prefs))
        assertEquals(
            ReadingMode.PagedVertical,
            ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Manga, ReadingMode.PagedVertical, prefs),
        )
        assertEquals(ReadingMode.PagedLeftToRight, ReadingModes.resolve(ReadingMode.PagedRightToLeft, ComicFormat.Manga, null, prefs))
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Manga, null, none))
    }

    @Test
    fun booksStayBooks() {
        val prefs = none.copy(mangaMode = ReadingMode.LongStrip)
        assertEquals(ReadingMode.Book, ReadingModes.resolve(ReadingMode.Book, ComicFormat.Manga, ReadingMode.PagedVertical, prefs))
        // A comic never takes the book mode, even if stored for its series: the format rule applies.
        assertEquals(
            ReadingMode.LongStrip,
            ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Manga, ReadingMode.Book, none),
        )
    }

    @Test
    fun scrollAmountsAndHeadings() {
        assertEquals(listOf(0.5f, 0.75f, 0.9f), ScrollAmount.entries.map { it.fraction })
        assertEquals(ScrollAmount.ThreeQuarters, ReaderPreferences().scrollAmount)
        assertEquals("Chapter 4 · The last gate", ChapterHeading(4.0, "The last gate").label)
        assertEquals("Chapter 4.5", ChapterHeading(4.5, null).label)
    }
}
