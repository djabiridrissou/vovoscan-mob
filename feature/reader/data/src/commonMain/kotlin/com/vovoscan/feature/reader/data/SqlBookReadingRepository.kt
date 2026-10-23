package com.vovoscan.feature.reader.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.vovoscan.feature.reader.data.db.ReaderDatabase
import com.vovoscan.feature.reader.domain.BookFont
import com.vovoscan.feature.reader.domain.BookLineSpacing
import com.vovoscan.feature.reader.domain.BookMargins
import com.vovoscan.feature.reader.domain.BookPosition
import com.vovoscan.feature.reader.domain.BookReadingRepository
import com.vovoscan.feature.reader.domain.BookTheme
import com.vovoscan.feature.reader.domain.BookTypography
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Book reading on this device, in the reader's database: the typography in `reader_setting` (keys `book.…`, next to
 * the comic settings; an unknown or missing value falls back to the default) and the position inside a book in
 * `book_position`. Positions are written in the app's [scope], in the order they were asked for: the reader's last
 * one is still written once it has closed.
 */
class SqlBookReadingRepository(
    private val database: ReaderDatabase,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : BookReadingRepository {
    private val settings get() = database.readerQueries
    private val books get() = database.bookQueries
    private val writes = Mutex()
    private val positions = Mutex()

    override fun observeTypography(): Flow<BookTypography> =
        settings
            .settings()
            .asFlow()
            .mapToList(io)
            .map { rows -> decode(rows.associate { it.name to it.setting_value }) }
            .distinctUntilChanged()

    override suspend fun updateTypography(transform: (BookTypography) -> BookTypography) =
        writes.withLock {
            withContext(io) {
                settings.transaction {
                    val current = decode(settings.settings().executeAsList().associate { it.name to it.setting_value })
                    encode(transform(current)).forEach { (name, value) -> settings.putSetting(name, value) }
                }
            }
        }

    override suspend fun position(
        sourceKey: String,
        chapterId: String,
    ): BookPosition? =
        withContext(io) {
            books.position(chapterKey(sourceKey, chapterId)).executeAsOneOrNull()?.let {
                BookPosition(it.section_index.toInt(), it.fraction.toFloat().coerceIn(0f, 1f))
            }
        }

    override fun savePosition(
        sourceKey: String,
        chapterId: String,
        position: BookPosition,
    ) {
        // Undispatched: the lock is asked for now, so writes keep the order of the calls (the lock is fair).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            positions.withLock {
                withContext(io) {
                    books.putPosition(
                        chapterKey(sourceKey, chapterId),
                        position.section.toLong(),
                        position.fraction.coerceIn(0f, 1f).toDouble(),
                    )
                }
            }
        }
    }

    internal companion object {
        private const val PREFIX = "book."

        fun decode(values: Map<String, String>): BookTypography {
            val defaults = BookTypography()

            fun <T : Enum<T>> choice(
                name: String,
                entries: List<T>,
                fallback: T,
            ): T = values[PREFIX + name]?.let { v -> entries.firstOrNull { it.name == v } } ?: fallback
            return BookTypography(
                textPercent =
                    values[PREFIX + "textPercent"]
                        ?.toIntOrNull()
                        ?.coerceIn(BookTypography.MIN_PERCENT, BookTypography.MAX_PERCENT)
                        ?: defaults.textPercent,
                lineSpacing = choice("lineSpacing", BookLineSpacing.entries, defaults.lineSpacing),
                margins = choice("margins", BookMargins.entries, defaults.margins),
                font = choice("font", BookFont.entries, defaults.font),
                justify = values[PREFIX + "justify"]?.toBooleanStrictOrNull() ?: defaults.justify,
                theme = choice("theme", BookTheme.entries, defaults.theme),
            )
        }

        fun encode(typography: BookTypography): Map<String, String> =
            mapOf(
                PREFIX + "textPercent" to typography.textPercent.toString(),
                PREFIX + "lineSpacing" to typography.lineSpacing.name,
                PREFIX + "margins" to typography.margins.name,
                PREFIX + "font" to typography.font.name,
                PREFIX + "justify" to typography.justify.toString(),
                PREFIX + "theme" to typography.theme.name,
            )
    }
}
