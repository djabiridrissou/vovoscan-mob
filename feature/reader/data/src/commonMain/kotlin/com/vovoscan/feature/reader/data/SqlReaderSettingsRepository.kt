package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingPosition
import com.vovoscan.feature.reader.domain.ScrollAmount
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
) : ReaderSettingsRepository {
    private val queries get() = database.readerQueries
    private val writes = Mutex()

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
        withContext(io) {
            if (mode == null) queries.deleteSeriesMode(sourceKey, titleId) else queries.putSeriesMode(sourceKey, titleId, mode.name)
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

    internal companion object {
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
