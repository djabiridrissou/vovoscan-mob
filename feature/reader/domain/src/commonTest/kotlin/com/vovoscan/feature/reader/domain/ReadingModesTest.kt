package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingModesTest {
    private val none = ReaderPreferences()

    @Test
    fun formatRule() {
        // Every comic: vertical pages (tall pages fit the width and scroll inside their page).
        assertEquals(ReadingMode.PagedVertical, ReadingMode.from("comic", "webtoon"))
        assertEquals(ReadingMode.PagedVertical, ComicFormat.Webtoon.defaultMode)
        assertEquals(ReadingMode.PagedVertical, ReadingMode.from("comic", "manga"))
        assertEquals(ReadingMode.PagedVertical, ComicFormat.Manga.defaultMode)
        assertEquals(ReadingMode.PagedVertical, ReadingMode.from("comic", "anything"))
        assertEquals(ReadingMode.Book, ReadingMode.from("book", "text"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("webtoon"))
    }

    @Test
    fun seriesWinsThenFormatDefaultThenRule() {
        val webtoon = ReadingMode.PagedVertical
        assertEquals(ReadingMode.PagedVertical, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, null, none))
        val prefs = none.copy(webtoonMode = ReadingMode.LongStripGaps, mangaMode = ReadingMode.PagedLeftToRight)
        assertEquals(ReadingMode.LongStripGaps, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, null, prefs))
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, ReadingMode.LongStrip, prefs))
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(webtoon, ComicFormat.Webtoon, ReadingMode.LongStrip, none))
        val manga = ReadingMode.PagedVertical
        assertEquals(ReadingMode.PagedLeftToRight, ReadingModes.resolve(manga, ComicFormat.Manga, null, prefs))
        assertEquals(ReadingMode.PagedVertical, ReadingModes.resolve(manga, ComicFormat.Manga, null, none))
        // Right to left only when chosen: for the series, or as the manga default.
        assertEquals(ReadingMode.PagedRightToLeft, ReadingModes.resolve(manga, ComicFormat.Manga, ReadingMode.PagedRightToLeft, prefs))
        val rtl = none.copy(mangaMode = ReadingMode.PagedRightToLeft)
        assertEquals(ReadingMode.PagedRightToLeft, ReadingModes.resolve(manga, ComicFormat.Manga, null, rtl))
    }

    @Test
    fun booksStayBooks() {
        val prefs = none.copy(mangaMode = ReadingMode.LongStrip)
        assertEquals(ReadingMode.Book, ReadingModes.resolve(ReadingMode.Book, ComicFormat.Manga, ReadingMode.PagedVertical, prefs))
        // A comic never takes the book mode, even if stored for its series: the format rule applies.
        assertEquals(
            ReadingMode.PagedVertical,
            ReadingModes.resolve(ReadingMode.PagedVertical, ComicFormat.Manga, ReadingMode.Book, none),
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
