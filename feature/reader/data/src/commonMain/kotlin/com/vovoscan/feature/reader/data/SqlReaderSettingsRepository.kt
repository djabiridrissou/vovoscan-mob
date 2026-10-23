package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.HistoryMerge
import com.vovoscan.feature.reader.domain.HistorySync
import com.vovoscan.feature.reader.domain.LastRead
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingPosition
import com.vovoscan.feature.reader.domain.ScrollAmount
import com.vovoscan.feature.reader.domain.SeriesKey
import com.vovoscan.feature.reader.domain.SeriesModeMerge
import com.vovoscan.feature.reader.domain.SeriesModeSync
import com.vovoscan.feature.reader.domain.TapInvert
import com.vovoscan.feature.reader.domain.TapLayout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reader settings in the reader's database: a small key-value table (`reader_setting`) and the mode chosen per
 * series (`series_mode`). An unknown or missing value falls back to the default.
 */
class SqlReaderSettingsRepository(
    private val database: ReaderDatabase,
    private val io: CoroutineDispatcher,
    /** The account's copy of the series modes (the device's is its cache). */
    private val seriesSync: SeriesModeSync = SeriesModeSync.None,
    /** The account's reading history (the device's is its cache). */
    private val historySync: HistorySync = HistorySync.None,
) : ReaderSettingsRepository {
    private val queries get() = database.readerQueries
    private val writes = Mutex()
    private val seriesLock = Mutex()
    private val historyLock = Mutex()

    override fun observePreferences(): Flow<ReaderPreferences> =
        queries
            .settings()
            .asFlow()
            .mapToList(io)
            .map { rows -> decode(rows.associate { it.name to it.setting_value }) }
            .distinctUntilChanged()

    override suspend fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) =
        writes.withLock {
            withContext(io) {
                queries.transaction {
                    val current = decode(queries.settings().executeAsList().associate { it.name to it.setting_value })
                    encode(transform(current)).forEach { (name, value) -> queries.putSetting(name, value) }
                }
            }
        }

    override fun observeSeriesMode(
        sourceKey: String,
        titleId: String,
    ): Flow<ReadingMode?> =
        queries
            .seriesMode(sourceKey, titleId)
            .asFlow()
            .mapToOneOrNull(io)
            .map { it?.let(::modeOf) }
            .distinctUntilChanged()

    override suspend fun setSeriesMode(
        sourceKey: String,
        titleId: String,
        mode: ReadingMode?,
    ) {
        val series = SeriesKey(sourceKey, titleId)
        seriesLock.withLock {
            withContext(io) {
                queries.transaction {
                    writeSeries(series, mode)
                    queries.putSetting(PENDING_PREFIX + series.wire, mode?.name ?: NONE)
                }
            }
            if (seriesSync.push(series, mode)) withContext(io) { queries.deleteSetting(PENDING_PREFIX + series.wire) }
        }
    }

    override suspend fun syncSeriesModes() {
        seriesLock.withLock {
            val account = seriesSync.pull() ?: return
            val (device, pending, migrated) =
                withContext(io) {
                    Triple(
                        queries
                            .allSeriesModes()
                            .executeAsList()
                            .mapNotNull { row ->
                                modeOf(row.mode)?.let { SeriesKey(row.source_key, row.title_id) to it }
                            }.toMap(),
                        queries
                            .pendingSeriesModes()
                            .executeAsList()
                            .mapNotNull { row ->
                                SeriesKey.of(row.name.removePrefix(PENDING_PREFIX))?.let { it to modeOf(row.setting_value) }
                            }.toMap(),
                        queries.setting(MIGRATED).executeAsOneOrNull() == "true",
                    )
                }
            val plan = SeriesModeMerge.plan(account, device, pending, migrated)
            withContext(io) { queries.transaction { plan.local.forEach { (series, mode) -> writeSeries(series, mode) } } }
            val sent = plan.push.filter { (series, mode) -> seriesSync.push(series, mode) }.keys
            withContext(io) {
                queries.transaction {
                    sent.forEach { queries.deleteSetting(PENDING_PREFIX + it.wire) }
                    // Once every earlier choice is on the account, the account is the truth for the others.
                    if (!migrated && sent.containsAll(plan.push.keys - pending.keys)) queries.putSetting(MIGRATED, "true")
                }
            }
        }
    }

    private fun writeSeries(
        series: SeriesKey,
        mode: ReadingMode?,
    ) {
        if (mode == null) {
            queries.deleteSeriesMode(series.sourceKey, series.titleId)
        } else {
            queries.putSeriesMode(series.sourceKey, series.titleId, mode.name)
        }
    }

    override suspend fun position(
        sourceKey: String,
        chapterId: String,
    ): ReadingPosition? =
        withContext(io) { ReadingPosition.decode(queries.setting(positionKey(sourceKey, chapterId)).executeAsOneOrNull()) }

    override suspend fun savePosition(
        sourceKey: String,
        chapterId: String,
        position: ReadingPosition,
    ) {
        withContext(io) { queries.putSetting(positionKey(sourceKey, chapterId), position.encode()) }
    }

    override fun observeHistory(): Flow<List<LastRead>> =
        queries
            .history()
            .asFlow()
            .mapToList(io)
            .map { rows ->
                rows.mapNotNull { LastRead.decode(it.name.removePrefix(HISTORY_PREFIX), it.setting_value) }.sortedByDescending { it.readAt }
            }.distinctUntilChanged()

    override suspend fun recordRead(read: LastRead) {
        withContext(io) {
            queries.transaction {
                queries.putSetting(HISTORY_PREFIX + read.key, read.encode())
                queries.putSetting(HISTORY_PENDING_PREFIX + read.key, read.encode())
            }
        }
        // Sent at once when it can; else it waits for the next sync. A newer read of the title replaces it meanwhile.
        if (historySync.push(read)) forgetPending(read)
    }

    override suspend fun syncHistory() {
        historyLock.withLock {
            val pending =
                withContext(io) {
                    queries.pendingHistory().executeAsList().mapNotNull {
                        LastRead.decode(it.name.removePrefix(HISTORY_PENDING_PREFIX), it.setting_value)
                    }
                }
            pending.forEach { if (historySync.push(it)) forgetPending(it) }
            val since = withContext(io) { queries.setting(HISTORY_SINCE).executeAsOneOrNull()?.toLongOrNull() }
            val places = historySync.pull(since) ?: return
            withContext(io) {
                queries.transaction {
                    val local =
                        queries.history().executeAsList().mapNotNull {
                            LastRead.decode(it.name.removePrefix(HISTORY_PREFIX), it.setting_value)
                        }
                    val changes = HistoryMerge.apply(local.associateBy { it.key }, places)
                    changes.put.forEach { queries.putSetting(HISTORY_PREFIX + it.key, it.encode()) }
                    changes.remove.forEach { queries.deleteSetting(HISTORY_PREFIX + it) }
                    places.maxOfOrNull { it.updatedAt }?.let { newest ->
                        queries.putSetting(HISTORY_SINCE, maxOf(newest, since ?: 0L).toString())
                    }
                }
            }
        }
    }

    // Only the read that was sent: a newer one recorded meanwhile stays pending.
    private suspend fun forgetPending(read: LastRead) =
        withContext(io) {
            queries.transaction {
                if (queries.setting(HISTORY_PENDING_PREFIX + read.key).executeAsOneOrNull() == read.encode()) {
                    queries.deleteSetting(HISTORY_PENDING_PREFIX + read.key)
                }
            }
        }

    override suspend fun recordLanguage(code: String) {
        withContext(io) { queries.putSetting(READ_LANGUAGE, code) }
    }

    override suspend fun lastLanguage(): String? = withContext(io) { queries.setting(READ_LANGUAGE).executeAsOneOrNull() }

    internal companion object {
        const val HISTORY_PREFIX = "last/"
        const val HISTORY_PENDING_PREFIX = "historyPending/"
        const val HISTORY_SINCE = "historySince"
        const val READ_LANGUAGE = "readLanguage"
        const val PENDING_PREFIX = "seriesPending/"
        const val MIGRATED = "seriesMigrated"

        private const val NONE = "-"

        fun decode(values: Map<String, String>): ReaderPreferences {
            val defaults = ReaderPreferences()

            fun flag(
                name: String,
                fallback: Boolean,
            ) = values[name]?.toBooleanStrictOrNull() ?: fallback
            return ReaderPreferences(
                mangaMode = values["mangaMode"]?.let(::modeOf),
                webtoonMode = values["webtoonMode"]?.let(::modeOf),
                tapLayout = values["tapLayout"]?.let { v -> TapLayout.entries.firstOrNull { it.name == v } } ?: defaults.tapLayout,
                tapInvert = values["tapInvert"]?.let { v -> TapInvert.entries.firstOrNull { it.name == v } } ?: defaults.tapInvert,
                scrollAmount =
                    values["scrollAmount"]?.let { v -> ScrollAmount.entries.firstOrNull { it.name == v } } ?: defaults.scrollAmount,
                showTapZones = flag("showTapZones", defaults.showTapZones),
                doubleTapZoom = flag("doubleTapZoom", defaults.doubleTapZoom),
                keepAwake = flag("keepAwake", defaults.keepAwake),
                volumeKeys = flag("volumeKeys", defaults.volumeKeys),
                volumeKeysInverted = flag("volumeKeysInverted", defaults.volumeKeysInverted),
                tapZonesSeen = flag("tapZonesSeen", defaults.tapZonesSeen),
                focus = flag("focus", defaults.focus),
                coachSeen =
                    values["coachSeen"]
                        ?.split(",")
                        ?.mapNotNull(CoachKind::fromWire)
                        ?.toSet()
                        ?: defaults.coachSeen,
                doublePages = flag("doublePages", defaults.doublePages),
            )
        }

        fun encode(preferences: ReaderPreferences): Map<String, String> =
            mapOf(
                "mangaMode" to (preferences.mangaMode?.name ?: NONE),
                "webtoonMode" to (preferences.webtoonMode?.name ?: NONE),
                "tapLayout" to preferences.tapLayout.name,
                "tapInvert" to preferences.tapInvert.name,
                "scrollAmount" to preferences.scrollAmount.name,
                "showTapZones" to preferences.showTapZones.toString(),
                "doubleTapZoom" to preferences.doubleTapZoom.toString(),
                "keepAwake" to preferences.keepAwake.toString(),
                "volumeKeys" to preferences.volumeKeys.toString(),
                "volumeKeysInverted" to preferences.volumeKeysInverted.toString(),
                "tapZonesSeen" to preferences.tapZonesSeen.toString(),
                "focus" to preferences.focus.toString(),
                "coachSeen" to preferences.coachSeen.joinToString(",") { it.wire },
                "doublePages" to preferences.doublePages.toString(),
            )

        /** A comic mode by name; a book or anything unknown is no choice. */
        fun modeOf(name: String): ReadingMode? = ReadingMode.comicModes.firstOrNull { it.name == name }
    }
}

/** `reader_setting` row of a chapter's position; erased with the series modes (`ReaderLocalDataSource`). */
internal fun positionKey(
    sourceKey: String,
    chapterId: String,
) = "pos/$sourceKey/$chapterId"
