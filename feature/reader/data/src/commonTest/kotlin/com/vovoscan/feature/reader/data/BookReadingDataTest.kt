package com.vovoscan.feature.reader.data

import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.domain.BookLineSpacing
import com.vovoscan.feature.reader.domain.BookMargins
import com.vovoscan.feature.reader.domain.BookPosition
import com.vovoscan.feature.reader.domain.BookTheme
import com.vovoscan.feature.reader.domain.BookTypography
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.TapLayout
import com.vovoscan.feature.reader.domain.TocEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class BookReadingDataTest {
    @Test
    fun nestedNavKeepsDepthsAndAnchors() {
        val sections = EpubParser().parse(Base64.decode(EpubTocFixture.nestedNav))
        assertEquals(
            listOf(
                ParsedTocEntry("Part One. The River", 0, "p1"),
                ParsedTocEntry("Chapter 1. The Lantern", 1, "c1"),
                ParsedTocEntry("Chapter 2. The Ferry", 1, "c2"),
            ),
            sections[0].toc,
        )
        // A part without a link of its own opens where its first chapter does; an entry outside the book is left out.
        assertEquals(
            listOf(ParsedTocEntry("Part Two. The Road", 0, "c3"), ParsedTocEntry("Chapter 3. The Far Bank", 1, "c3")),
            sections[1].toc,
        )
        // Titles still come from the first linked entry of each file (the landmarks nav is not the contents).
        assertEquals(listOf("Part One. The River", "Chapter 3. The Far Bank"), sections.map { it.title })
        // The ids the entries point at stay in the sanitized HTML.
        assertTrue("id=\"c2\"" in sections[0].html, sections[0].html)
    }

    @Test
    fun nestedNcxKeepsDepthsAndAnchors() {
        val sections = EpubParser().parse(Base64.decode(EpubTocFixture.nestedNcx))
        assertEquals(
            listOf(
                ParsedTocEntry("Part One. The River", 0, "p1"),
                ParsedTocEntry("Chapter 1. The Lantern", 1, "c1"),
                ParsedTocEntry("Chapter 2. The Ferry", 1, "c2"),
            ),
            sections[0].toc,
        )
        // Part Two points at an id its file doesn't hold: it opens the section's start.
        assertEquals(
            listOf(ParsedTocEntry("Part Two. The Road", 0, null), ParsedTocEntry("Chapter 3. The Far Bank", 1, "c3")),
            sections[1].toc,
        )
    }

    @Test
    fun flatContentsOfSeveralChaptersPerFile() {
        val nav = EpubParser().parse(Base64.decode(EpubFixture.severalChaptersPerFileNav))
        assertEquals(listOf(ParsedTocEntry("Chapter 1. The Lantern", 0, "c1"), ParsedTocEntry("Chapter 2. The Ferry", 0, "c2")), nav[0].toc)
        // A blank entry is no entry.
        assertEquals(emptyList(), nav[1].toc)
    }

    @Test
    fun theContentsAreSavedWithTheSectionsAndGoWithThem() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val local = SqlReaderLocalDataSource(database, io)
            local.save("rules", ReaderChapterDto("b1", "t1", "Letters", kind = "book", number = 1.0, format = "book"))
            local.saveBook("rules", "b1", EpubParser().parse(Base64.decode(EpubTocFixture.nestedNav)))
            val book = local.observeBook("rules", "b1").first()!!
            assertEquals(2, book.sections.size)
            assertEquals(
                listOf(
                    TocEntry("Part One. The River", 0, 0, "p1"),
                    TocEntry("Chapter 1. The Lantern", 1, 0, "c1"),
                    TocEntry("Chapter 2. The Ferry", 1, 0, "c2"),
                    TocEntry("Part Two. The Road", 0, 1, "c3"),
                    TocEntry("Chapter 3. The Far Bank", 1, 1, "c3"),
                ),
                book.toc,
            )
            // Saved again from a book without contents: none left from before.
            local.saveBook("rules", "b1", listOf(ParsedSection("Only", "<p>One.</p>")))
            assertEquals(emptyList(), local.observeBook("rules", "b1").first()!!.toc)

            local.saveBook("rules", "b1", EpubParser().parse(Base64.decode(EpubTocFixture.nestedNav)))
            val books = SqlBookReadingRepository(database, io, this)
            books.savePosition("rules", "b1", BookPosition(1, 0.4f))
            advanceUntilIdle()
            // The source removed: its books' contents and positions go too.
            local.clearSource("rules")
            assertEquals(
                0,
                database.bookQueries
                    .toc("rules/b1")
                    .executeAsList()
                    .size,
            )
            assertNull(books.position("rules", "b1"))
        }

    @Test
    fun typographyIsKeptOnTheDeviceBesideTheComicSettings() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val books = SqlBookReadingRepository(database, io, this)
            assertEquals(BookTypography(), books.observeTypography().first())
            val chosen =
                BookTypography(
                    textPercent = 130,
                    lineSpacing = BookLineSpacing.Relaxed,
                    margins = BookMargins.Wide,
                    font = BookFont.Serif,
                    justify = true,
                    theme = BookTheme.Sepia,
                )
            books.updateTypography { chosen }
            val comics = SqlReaderSettingsRepository(database, io)
            comics.updatePreferences { it.copy(tapLayout = TapLayout.Edge) }
            // Relaunched: both read back, neither overwrote the other.
            assertEquals(chosen, SqlBookReadingRepository(database, io, this).observeTypography().first())
            assertEquals(ReaderPreferences(tapLayout = TapLayout.Edge), comics.observePreferences().first())
        }

    @Test
    fun unknownTypographyValuesFallBackToTheDefaults() {
        val decoded =
            SqlBookReadingRepository.decode(
                mapOf("book.textPercent" to "900", "book.font" to "Comic", "book.justify" to "maybe", "book.theme" to "Light"),
            )
        assertEquals(BookTypography(textPercent = BookTypography.MAX_PERCENT, theme = BookTheme.Light), decoded)
    }

    @Test
    fun positionsKeepTheLastWriteAndAreClearedWithTheAccount() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val books = SqlBookReadingRepository(database, io, this)
            assertNull(books.position("rules", "b1"))
            // Calls made in a row are written in that order.
            books.savePosition("rules", "b1", BookPosition(2, 0.25f))
            books.savePosition("rules", "b1", BookPosition(3, 0.5f))
            books.savePosition("rules", "b2", BookPosition(0, 1.5f))
            advanceUntilIdle()
            assertEquals(BookPosition(3, 0.5f), books.position("rules", "b1"))
            assertEquals(BookPosition(0, 1f), books.position("rules", "b2"))
            SqlReaderLocalDataSource(database, io).clear()
            assertNull(books.position("rules", "b1"))
        }
}
