package com.vovoscan.feature.reader.data

import com.vovoscan.core.testing.inMemoryDriver
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.LastRead
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.SeriesKey
import com.vovoscan.feature.reader.domain.SeriesModeSync
import com.vovoscan.feature.reader.domain.TapInvert
import com.vovoscan.feature.reader.domain.TapLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlReaderSettingsRepositoryTest {
    @Test
    fun defaultsUntilChangedThenKept() =
        runTest {
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val io = StandardTestDispatcher(testScheduler)
            val settings = SqlReaderSettingsRepository(database, io)
            assertEquals(ReaderPreferences(), settings.observePreferences().first())
            settings.updatePreferences {
                it.copy(
                    webtoonMode = ReadingMode.LongStripGaps,
                    tapLayout = TapLayout.Kindle,
                    tapInvert = TapInvert.Both,
                    scrollAmount = ScrollAmount.Most,
                    doubleTapZoom = true,
                    keepAwake = false,
                    volumeKeys = true,
                )
            }
            // A second repository on the same database (the app relaunched) reads the same values.
            val again = SqlReaderSettingsRepository(database, io).observePreferences().first()
            assertEquals(ReadingMode.LongStripGaps, again.webtoonMode)
            assertNull(again.mangaMode)
            assertEquals(TapLayout.Kindle, again.tapLayout)
            assertEquals(TapInvert.Both, again.tapInvert)
            assertEquals(ScrollAmount.Most, again.scrollAmount)
            assertEquals(true, again.doubleTapZoom)
            assertEquals(false, again.keepAwake)
            assertEquals(true, again.volumeKeys)
            assertEquals(true, again.showTapZones)
        }

    @Test
    fun seriesModeIsPerSourceAndTitleAndGoesWithTheAccount() =
        runTest {
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val io = StandardTestDispatcher(testScheduler)
            val settings = SqlReaderSettingsRepository(database, io)
            val local = SqlReaderLocalDataSource(database, io)
            settings.setSeriesMode("vovoscan", "t1", ReadingMode.PagedVertical)
            settings.setSeriesMode("gutenberg", "t1", ReadingMode.LongStrip)
            assertEquals(ReadingMode.PagedVertical, settings.observeSeriesMode("vovoscan", "t1").first())
            assertNull(settings.observeSeriesMode("vovoscan", "t2").first())
            local.clearSource("gutenberg")
            assertNull(settings.observeSeriesMode("gutenberg", "t1").first())
            settings.setSeriesMode("vovoscan", "t1", null)
            assertNull(settings.observeSeriesMode("vovoscan", "t1").first())
            settings.setSeriesMode("vovoscan", "t1", ReadingMode.LongStrip)
            settings.updatePreferences { it.copy(tapLayout = TapLayout.Edge) }
            local.clear()
            // Sign-out: the series choices go, the device's preferences stay.
            assertNull(settings.observeSeriesMode("vovoscan", "t1").first())
            assertEquals(TapLayout.Edge, settings.observePreferences().first().tapLayout)
        }

    @Test
    fun unknownValuesFallBackToDefaults() {
        val decoded =
            SqlReaderSettingsRepository.decode(
                mapOf(
                    "tapLayout" to "Spiral",
                    "mangaMode" to "Book",
                    "keepAwake" to "maybe",
                    "webtoonMode" to "-",
                ),
            )
        assertEquals(ReaderPreferences(), decoded)
        assertEquals(ReaderPreferences(), SqlReaderSettingsRepository.decode(SqlReaderSettingsRepository.encode(ReaderPreferences())))
    }

    @Test
    fun theLastChapterOfEachTitleIsKeptNewestFirstAndGoesWithTheSource() =
        runTest {
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val io = StandardTestDispatcher(testScheduler)
            val settings = SqlReaderSettingsRepository(database, io)
            val local = SqlReaderLocalDataSource(database, io)
            settings.recordRead(LastRead("inkwell", "a/b:c", "ch:1", 3, readAt = 100))
            settings.recordRead(LastRead("vovoscan", "t1", "c1", 0, readAt = 200))
            settings.recordRead(LastRead("vovoscan", "t1", "c2", 7, readAt = 300))
            // Ids with "/" and ":" come back whole; one row per title, the latest.
            assertEquals(
                listOf(LastRead("vovoscan", "t1", "c2", 7, 300), LastRead("inkwell", "a/b:c", "ch:1", 3, 100)),
                settings.observeHistory().first(),
            )
            // The language last read in (the series page's badges) is not a preference either.
            settings.recordLanguage("fr")
            assertEquals("fr", settings.lastLanguage())
            // History rows are not preferences.
            assertEquals(ReaderPreferences(), settings.observePreferences().first())
            local.clearSource("inkwell")
            assertEquals(listOf("t1"), settings.observeHistory().first().map { it.titleId })
            local.clear()
            assertEquals(emptyList(), settings.observeHistory().first())
            assertNull(settings.lastLanguage())
        }

    @Test
    fun aDamagedHistoryRowIsIgnored() {
        assertNull(LastRead.decode("vovoscan/t1", "x:1:c"))
        assertNull(LastRead.decode("vovoscan/t1", "1:-2:c"))
        assertNull(LastRead.decode("vovoscan/t1", "1:2:"))
        assertNull(LastRead.decode("vovoscan", "1:2:c"))
        assertEquals(LastRead("s", "t", "c", 2, 1), LastRead.decode("s/t", "1:2:c"))
    }

    /** The account in memory: what it holds, what reached it; offline refuses everything. */
    private class FakeAccount(
        val modes: MutableMap<SeriesKey, ReadingMode> = mutableMapOf(),
    ) : SeriesModeSync {
        var online = true
        val pushed = mutableListOf<Pair<SeriesKey, ReadingMode?>>()

        override suspend fun pull(): Map<SeriesKey, ReadingMode>? = if (online) modes.toMap() else null

        override suspend fun push(
            series: SeriesKey,
            mode: ReadingMode?,
        ): Boolean {
            if (!online) return false
            pushed += series to mode
            if (mode == null) modes.remove(series) else modes[series] = mode
            return true
        }
    }

    @Test
    fun seriesModesFollowTheAccountAndAChoiceMadeOfflineGoesOutLater() =
        runTest {
            val database = ReaderDatabase(inMemoryDriver(ReaderDatabase.Schema))
            val io = StandardTestDispatcher(testScheduler)
            val t1 = SeriesKey("vovoscan", "t1")
            val t2 = SeriesKey("inkwell", "ink:road")
            // A choice from before the account kept them: sent once at the first sync.
            SqlReaderSettingsRepository(database, io).setSeriesMode(t2.sourceKey, t2.titleId, ReadingMode.PagedRightToLeft)
            val account = FakeAccount(mutableMapOf(t1 to ReadingMode.PagedVertical))
            val settings = SqlReaderSettingsRepository(database, io, account)
            settings.syncSeriesModes()
            assertEquals(ReadingMode.PagedVertical, settings.observeSeriesMode(t1.sourceKey, t1.titleId).first())
            assertEquals(ReadingMode.PagedRightToLeft, account.modes[t2])

            // Offline: the choice shows at once and waits; the account's older value does not undo it.
            account.online = false
            settings.setSeriesMode(t1.sourceKey, t1.titleId, ReadingMode.LongStrip)
            assertEquals(ReadingMode.LongStrip, settings.observeSeriesMode(t1.sourceKey, t1.titleId).first())
            account.online = true
            settings.syncSeriesModes()
            assertEquals(ReadingMode.LongStrip, account.modes[t1])
            assertEquals(ReadingMode.LongStrip, settings.observeSeriesMode(t1.sourceKey, t1.titleId).first())

            // Reset on another device: this one goes back to the default too.
            account.modes.remove(t2)
            settings.syncSeriesModes()
            assertNull(settings.observeSeriesMode(t2.sourceKey, t2.titleId).first())
            // Nothing is sent twice.
            assertEquals(
                listOf<Pair<SeriesKey, ReadingMode?>>(t2 to ReadingMode.PagedRightToLeft, t1 to ReadingMode.LongStrip),
                account.pushed,
            )
            // The bookkeeping never reaches the preferences.
            assertEquals(ReaderPreferences(), settings.observePreferences().first())
        }
}
