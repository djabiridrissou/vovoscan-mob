package com.vovoscan.feature.reader.presentation

import com.vovoscan.feature.reader.domain.CoachKind
import com.vovoscan.feature.reader.domain.ReaderPreferences
import com.vovoscan.feature.reader.domain.ReaderSettingsRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Reader settings in memory; `series` keyed by "source/title". */
class FakeSettings(
    initial: ReaderPreferences = ReaderPreferences(),
) : ReaderSettingsRepository {
    val preferences = MutableStateFlow(initial)
    val series = MutableStateFlow<Map<String, ReadingMode>>(emptyMap())

    override fun observePreferences(): Flow<ReaderPreferences> = preferences

    override suspend fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) = preferences.update(transform)

    override fun observeSeriesMode(
        sourceKey: String,
        titleId: String,
    ): Flow<ReadingMode?> = series.map { it["$sourceKey/$titleId"] }

    override suspend fun setSeriesMode(
        sourceKey: String,
        titleId: String,
        mode: ReadingMode?,
    ) = series.update { if (mode == null) it - "$sourceKey/$titleId" else it + ("$sourceKey/$titleId" to mode) }
}

/** Every first-open coach already seen: tests about something else are not stopped by it. */
val ALL_COACHES: Set<CoachKind> = CoachKind.entries.toSet()

/** The continuous reader's tests: a manga read as one strip (its own default is pages right to left). */
val CONTINUOUS: ReadingMode = ReadingMode.LongStrip
