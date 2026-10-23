package com.vovoscan.feature.reader.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A table-of-contents entry of a book (EPUB 3 nav or EPUB 2 NCX), in reading order. [depth]: 0 for the top level,
 * as nested in the book. [section]: the section it opens. [anchor]: the element it points at inside that section
 * (a Gutenberg file holds several chapters), null for the section's start.
 */
data class TocEntry(
    val title: String,
    val depth: Int,
    val section: Int,
    val anchor: String?,
)

/** The book page's font: system fonts only, so they work offline in the web view (which has no network). */
enum class BookFont {
    /** New York / Georgia on iOS, Noto Serif on Android. */
    Serif,

    /** San Francisco on iOS, Roboto on Android. */
    Sans,
}

/** Space between lines, as a multiple of the text size. */
enum class BookLineSpacing(
    val factor: Float,
) {
    Compact(1.45f),
    Normal(1.7f),
    Relaxed(2f),
}

/** Space on each side of the text, in CSS px (dp / pt). */
enum class BookMargins(
    val px: Int,
) {
    Narrow(12),
    Normal(20),
    Wide(36),
}

/** The book page's colours. The app itself stays dark: only the page changes. */
enum class BookTheme {
    Dark,
    Sepia,
    Light,
}

/**
 * How a book page is set, kept on this device. The text size follows the device's text size (iOS Dynamic Type,
 * Android font scale) and [textPercent] scales it further (100: as the device says).
 */
data class BookTypography(
    val textPercent: Int = 100,
    val lineSpacing: BookLineSpacing = BookLineSpacing.Normal,
    val margins: BookMargins = BookMargins.Normal,
    val font: BookFont = BookFont.Sans,
    val justify: Boolean = false,
    val theme: BookTheme = BookTheme.Dark,
) {
    val canShrink: Boolean get() = textPercent > MIN_PERCENT

    val canGrow: Boolean get() = textPercent < MAX_PERCENT

    fun smaller(): BookTypography = copy(textPercent = (textPercent - STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT))

    fun bigger(): BookTypography = copy(textPercent = (textPercent + STEP_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT))

    /**
     * The body text size in CSS px: [BASE_PX] at the device's default text size, times the device's scale
     * ([systemScale], 1 by default; clamped to what the platforms offer), times the user's own.
     */
    fun textPx(systemScale: Float): Float {
        val system = if (systemScale.isNaN() || systemScale <= 0f) 1f else systemScale.coerceIn(MIN_SYSTEM_SCALE, MAX_SYSTEM_SCALE)
        return (BASE_PX * system * textPercent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f).coerceIn(MIN_PX, MAX_PX)
    }

    companion object {
        /** The size the reader used before it could change (18 px). */
        const val BASE_PX = 18f
        const val MIN_PERCENT = 70
        const val MAX_PERCENT = 200
        const val STEP_PERCENT = 10
        const val MIN_SYSTEM_SCALE = 0.5f
        const val MAX_SYSTEM_SCALE = 3.5f
        private const val MIN_PX = 10f
        private const val MAX_PX = 120f
    }
}

/**
 * Where the reader was inside a book (one unit): the section and how far down it, as the fraction of the section's
 * height at the top of the screen (0 its top). A fraction, not pixels: it still points at about the same text once
 * the typography changes the section's height. Kept on this device only; the library's progress counts sections.
 */
data class BookPosition(
    val section: Int,
    val fraction: Float,
)

/** Book reading on this device: the page's typography, and the position inside the section last read. */
interface BookReadingRepository {
    fun observeTypography(): Flow<BookTypography>

    suspend fun updateTypography(transform: (BookTypography) -> BookTypography)

    suspend fun position(
        sourceKey: String,
        chapterId: String,
    ): BookPosition?

    /** Non-suspending: written in the app's scope, so the last position is kept when the reader closes. */
    fun savePosition(
        sourceKey: String,
        chapterId: String,
        position: BookPosition,
    )

    /** In memory: for readers that keep nothing (tests, previews). */
    class InMemory(
        initial: BookTypography = BookTypography(),
    ) : BookReadingRepository {
        val typography = MutableStateFlow(initial)
        val positions = mutableMapOf<String, BookPosition>()

        override fun observeTypography(): Flow<BookTypography> = typography

        override suspend fun updateTypography(transform: (BookTypography) -> BookTypography) = typography.update(transform)

        override suspend fun position(
            sourceKey: String,
            chapterId: String,
        ): BookPosition? = positions["$sourceKey/$chapterId"]

        override fun savePosition(
            sourceKey: String,
            chapterId: String,
            position: BookPosition,
        ) {
            positions["$sourceKey/$chapterId"] = position
        }
    }
}
