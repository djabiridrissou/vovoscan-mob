package com.vovoscan.feature.reader.domain

import kotlinx.coroutines.flow.Flow

/** How far one tap scrolls a long strip, as a fraction of the screen height. */
enum class ScrollAmount(
    val fraction: Float,
) {
    Half(0.5f),
    ThreeQuarters(0.75f),
    Most(0.9f),
}

/**
 * The reader's settings, kept on this device (no backend). `mangaMode` / `webtoonMode`: the user's default per
 * format, null until chosen (then the format rule applies).
 */
data class ReaderPreferences(
    val mangaMode: ReadingMode? = null,
    val webtoonMode: ReadingMode? = null,
    val tapLayout: TapLayout = TapLayout.Default,
    val tapInvert: TapInvert = TapInvert.None,
    val scrollAmount: ScrollAmount = ScrollAmount.ThreeQuarters,
    val showTapZones: Boolean = true,
    val doubleTapZoom: Boolean = false,
    val keepAwake: Boolean = true,
    /** Android: the volume keys turn pages / scroll. */
    val volumeKeys: Boolean = false,
    val volumeKeysInverted: Boolean = false,
    /** The tap zones overlay was already shown once (on the reader's first opening on this device). */
    val tapZonesSeen: Boolean = false,
    /** Continuous strips: the panel being read at full light, the rest dimmed (reader/SPEC.md §1). */
    val focus: Boolean = false,
) {
    fun defaultFor(format: ComicFormat): ReadingMode? =
        when (format) {
            ComicFormat.Manga -> mangaMode
            ComicFormat.Webtoon -> webtoonMode
        }
}

object ReadingModes {
    /** Per-series override, then the user's default for the format, then the format rule. Books stay books. */
    fun resolve(
        chapterMode: ReadingMode,
        format: ComicFormat,
        seriesMode: ReadingMode?,
        preferences: ReaderPreferences,
    ): ReadingMode =
        when {
            chapterMode == ReadingMode.Book -> ReadingMode.Book
            seriesMode != null && seriesMode != ReadingMode.Book -> seriesMode
            else -> preferences.defaultFor(format)?.takeIf { it != ReadingMode.Book } ?: format.defaultMode
        }
}

/** Reader settings on the device: the preferences, and the mode chosen for a series (`sourceKey`, `titleId`). */
interface ReaderSettingsRepository {
    fun observePreferences(): Flow<ReaderPreferences>

    suspend fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences)

    fun observeSeriesMode(
        sourceKey: String,
        titleId: String,
    ): Flow<ReadingMode?>

    /** null: back to the default. */
    suspend fun setSeriesMode(
        sourceKey: String,
        titleId: String,
        mode: ReadingMode?,
    )

    /** Where a chapter was left on this device (null: never opened, or erased). */
    suspend fun position(
        sourceKey: String,
        chapterId: String,
    ): ReadingPosition? = null

    suspend fun savePosition(
        sourceKey: String,
        chapterId: String,
        position: ReadingPosition,
    ) {}
}

/**
 * A reading position (reader/SPEC.md §7): the page at the top of the screen and the share of it already above the
 * top (0..1), independent of the screen's width and the zoom.
 */
data class ReadingPosition(
    val page: Int,
    val offset: Float,
) {
    fun encode(): String = "$page:$offset"

    companion object {
        fun decode(value: String?): ReadingPosition? {
            val parts = value?.split(":") ?: return null
            val page = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val offset = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
            return if (page < 0) null else ReadingPosition(page, offset.coerceIn(0f, 1f))
        }
    }
}

/** A chapter's number and name, for the chapter transition ("Next: Chapter 4 · The last gate"). */
data class ChapterHeading(
    val number: Double,
    val name: String?,
) {
    val label: String get() = listOfNotNull(chapterLabel(number), name?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/**
 * Port: the number and name of a title's chapter, when the device knows them (the title page already seen).
 * Wired by the app to the catalog; the reader does not know that feature.
 */
fun interface ChapterHeadings {
    suspend fun heading(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): ChapterHeading?
}
