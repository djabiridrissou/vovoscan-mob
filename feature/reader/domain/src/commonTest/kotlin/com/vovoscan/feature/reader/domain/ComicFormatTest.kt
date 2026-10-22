package com.vovoscan.feature.reader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicFormatTest {
    @Test
    fun theSourcesOwnWordsAndLanguagesAreRecognised() {
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("webtoon"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("Long Strip"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("Web Comic"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("manhwa"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("Manhua"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("ko"))
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("zh"))
        assertEquals(ComicFormat.Manga, ComicFormat.from("manga"))
        assertEquals(ComicFormat.Manga, ComicFormat.from("ja"))
        assertEquals(ComicFormat.Manga, ComicFormat.from(""))
        assertEquals(ComicFormat.Comic, ComicFormat.from("comic"))
        assertEquals(ComicFormat.Comic, ComicFormat.from("BD"))
        assertEquals(ComicFormat.Comic, ComicFormat.from("en"))
        assertEquals(ComicFormat.Comic, ComicFormat.from("fr"))
        // A webtoon hint beats the others: a manhwa tagged "manga" still scrolls.
        assertEquals(ComicFormat.Webtoon, ComicFormat.from("manga, manhwa"))
        assertEquals(ReadingMode.PagedRightToLeft, ComicFormat.Manga.pagedMode)
        assertEquals(ReadingMode.PagedLeftToRight, ComicFormat.Comic.pagedMode)
    }

    @Test
    fun aMedianPageTwoPointTwoWidthsTallIsAStrip() {
        fun page(
            w: Int,
            h: Int,
        ) = ReaderPage(0, "u", w, h)
        assertNull(ComicFormat.fromPages(listOf(page(0, 0))))
        assertNull(ComicFormat.fromPages(listOf(page(1000, 1500), page(1000, 1500))))
        assertEquals(ComicFormat.Webtoon, ComicFormat.fromPages(listOf(page(800, 14_000), page(800, 12_000), page(1000, 1500))))
        // The median, not a count: one strip and one page average out above 2.2.
        assertEquals(ComicFormat.Webtoon, ComicFormat.fromPages(listOf(page(800, 14_000), page(1000, 1500))))
        // Exactly 2.2 is a strip; just under is not.
        assertEquals(ComicFormat.Webtoon, ComicFormat.fromPages(listOf(page(1000, 2200))))
        assertNull(ComicFormat.fromPages(listOf(page(1000, 2190), page(1000, 2190), page(800, 14_000))))
    }

    @Test
    fun aStripIsAlwaysContinuousUnlessTheSeriesSaysOtherwise() {
        val paged = ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft, webtoonMode = ReadingMode.PagedVertical)
        // Each format in its own style by default: a western comic turns its pages left to right.
        assertEquals(ReadingMode.PagedLeftToRight, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Comic, null, ReaderPreferences()))
        // A manga follows the reader's own default...
        assertEquals(ReadingMode.PagedRightToLeft, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Manga, null, paged))
        // ...but a strip is never shrunk into a page, even when the webtoon default is paged.
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, null, paged, strip = true))
        val gaps = ReaderPreferences(webtoonMode = ReadingMode.LongStripGaps)
        assertEquals(ReadingMode.LongStripGaps, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, null, gaps, strip = true))
        // Not even a choice made for the series turns a strip into pages.
        assertEquals(
            ReadingMode.LongStrip,
            ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, ReadingMode.PagedVertical, paged, strip = true),
        )
        // A western comic never takes the manga's right-to-left default.
        assertEquals(ReadingMode.PagedLeftToRight, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Comic, null, paged))
    }
}
