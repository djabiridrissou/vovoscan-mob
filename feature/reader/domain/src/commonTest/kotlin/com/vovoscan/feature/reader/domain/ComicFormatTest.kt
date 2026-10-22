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
        assertEquals(ReadingMode.PagedRightToLeft, ComicFormat.Manga.pagedMode)
        assertEquals(ReadingMode.PagedLeftToRight, ComicFormat.Comic.pagedMode)
    }

    @Test
    fun pagesTallerThanTwoAndAHalfWidthsAreAStrip() {
        fun page(
            w: Int,
            h: Int,
        ) = ReaderPage(0, "u", w, h)
        assertNull(ComicFormat.fromPages(listOf(page(0, 0))))
        assertNull(ComicFormat.fromPages(listOf(page(1000, 1500), page(1000, 1500))))
        assertEquals(ComicFormat.Webtoon, ComicFormat.fromPages(listOf(page(800, 14_000), page(800, 12_000), page(1000, 1500))))
        // Half the pages are not "mostly".
        assertNull(ComicFormat.fromPages(listOf(page(800, 14_000), page(1000, 1500))))
    }

    @Test
    fun aStripIsAlwaysContinuousUnlessTheSeriesSaysOtherwise() {
        val paged = ReaderPreferences(mangaMode = ReadingMode.PagedRightToLeft, webtoonMode = ReadingMode.PagedVertical)
        // Every format reads continuously by default.
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Comic, null, ReaderPreferences()))
        // A manga follows the reader's own default...
        assertEquals(ReadingMode.PagedRightToLeft, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Manga, null, paged))
        // ...but a strip is never shrunk into a page, even when the webtoon default is paged.
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, null, paged, strip = true))
        val gaps = ReaderPreferences(webtoonMode = ReadingMode.LongStripGaps)
        assertEquals(ReadingMode.LongStripGaps, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, null, gaps, strip = true))
        // A choice made for the series wins.
        assertEquals(
            ReadingMode.PagedVertical,
            ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Webtoon, ReadingMode.PagedVertical, paged, strip = true),
        )
        // A western comic never takes the manga's right-to-left default.
        assertEquals(ReadingMode.LongStrip, ReadingModes.resolve(ReadingMode.LongStrip, ComicFormat.Comic, null, paged))
    }
}
