package com.vovoscan.feature.reader.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** How far one tap scrolls a long strip, or a tall page inside its page, as a fraction of the screen height. */
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
    /** The kinds whose "How to read" coach was already closed on this device (`CoachKind`). */
    val coachSeen: Set<CoachKind> = emptySet(),
) {
    fun defaultFor(format: ComicFormat): ReadingMode? =
        when (format) {
            ComicFormat.Manga -> mangaMode
            ComicFormat.Webtoon -> webtoonMode
            // No setting of its own yet: a manga's default (right to left) would read it backwards.
            ComicFormat.Comic -> null
        }
}

/** What the first-open coach explains: each way of reading has its own, shown once. */
enum class CoachKind {
    Strip,
    RightToLeft,
    LeftToRight,
    Vertical,
    Book,
    ;

    companion object {
        fun of(mode: ReadingMode): CoachKind =
            when (mode) {
                ReadingMode.LongStrip, ReadingMode.LongStripGaps -> Strip
                ReadingMode.PagedRightToLeft -> RightToLeft
                ReadingMode.PagedLeftToRight -> LeftToRight
                ReadingMode.PagedVertical -> Vertical
                ReadingMode.Book -> Book
            }
    }
}

object ReadingModes {
    /**
     * Per-series override, then the user's default for the format, then the format rule. Books stay books. A webtoon
     * (declared, or pages shaped as a strip: [strip]) is always read continuously, whatever was chosen: a strip is
     * never shrunk into a page (reader/SPEC.md §1b); only the gap between its pages is a choice.
     */
    fun resolve(
        chapterMode: ReadingMode,
        format: ComicFormat,
        seriesMode: ReadingMode?,
        preferences: ReaderPreferences,
        strip: Boolean = false,
    ): ReadingMode {
        if (chapterMode == ReadingMode.Book) return ReadingMode.Book
        val webtoon = strip || format == ComicFormat.Webtoon
        if (webtoon) return seriesMode?.takeIf { it.isStrip } ?: preferences.webtoonMode?.takeIf { it.isStrip } ?: ReadingMode.LongStrip
        if (seriesMode != null && seriesMode != ReadingMode.Book) return seriesMode
        return preferences.defaultFor(format)?.takeIf { it != ReadingMode.Book } ?: format.defaultMode
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

    /** The chapter each title was left at on this device, newest first (any title, followed or not). */
    fun observeHistory(): Flow<List<LastRead>> = flowOf(emptyList())

    suspend fun recordRead(read: LastRead) {}
}

/** Where a title was left on this device: the chapter, the page, and when (ms). */
data class LastRead(
    val sourceKey: String,
    val titleId: String,
    val chapterId: String,
    val page: Int,
    val readAt: Long,
) {
    /** "<source>/<title>": a source key never holds a "/" (`^[a-z0-9][a-z0-9-]{1,62}$`). */
    val key: String get() = "$sourceKey/$titleId"

    fun encode(): String = "$readAt:$page:$chapterId"

    companion object {
        fun decode(
            key: String,
            value: String,
        ): LastRead? {
            val sourceKey = key.substringBefore('/', "").ifEmpty { return null }
            val titleId = key.substringAfter('/', "").ifEmpty { return null }
            val parts = value.split(':', limit = 3)
            if (parts.size < 3) return null
            val readAt = parts[0].toLongOrNull() ?: return null
            val page = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return parts[2].ifEmpty { null }?.let { LastRead(sourceKey, titleId, it, page, readAt) }
        }
    }
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
