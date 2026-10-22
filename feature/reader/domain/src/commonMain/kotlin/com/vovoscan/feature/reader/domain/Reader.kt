package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import kotlinx.coroutines.flow.Flow

/**
 * How a unit is read. Comics: paged (right to left, left to right, vertical) or a continuous strip (with or
 * without gaps between pages). Books: text.
 */
enum class ReadingMode {
    PagedRightToLeft,
    PagedLeftToRight,
    PagedVertical,
    LongStrip,
    LongStripGaps,
    Book,
    ;

    val isPaged: Boolean get() = this == PagedRightToLeft || this == PagedLeftToRight || this == PagedVertical

    val isStrip: Boolean get() = this == LongStrip || this == LongStripGaps

    /** Pages side by side (a horizontal swipe turns them). */
    val isHorizontal: Boolean get() = this == PagedRightToLeft || this == PagedLeftToRight

    companion object {
        /** The modes a comic can be read in, in the order the settings show them. */
        val comicModes: List<ReadingMode> = listOf(PagedRightToLeft, PagedLeftToRight, PagedVertical, LongStrip, LongStripGaps)

        /** The format rule: a book is text, every comic one continuous strip (`flow`, reader/SPEC.md §1). */
        fun from(
            kind: String,
            format: String,
        ): ReadingMode =
            when {
                kind == "book" -> Book
                else -> ComicFormat.from(format).defaultMode
            }
    }
}

/** A comic's format, as the source declares it: the per-format default mode and the paged direction. */
enum class ComicFormat(
    val defaultMode: ReadingMode,
    /** The way its pages and panels are read when paged: Japanese manga right to left, the others left to right. */
    val rightToLeft: Boolean,
) {
    Manga(ReadingMode.LongStrip, rightToLeft = true),
    Webtoon(ReadingMode.LongStrip, rightToLeft = false),

    /** Western comics and bandes dessinées. */
    Comic(ReadingMode.LongStrip, rightToLeft = false),
    ;

    /** The paged mode that reads it in its own direction. */
    val pagedMode: ReadingMode get() = if (rightToLeft) ReadingMode.PagedRightToLeft else ReadingMode.PagedLeftToRight

    companion object {
        /**
         * What a source says, in its own words: our API's `manga` / `webtoon` / `comic`, a plugin's own labels
         * ("Long Strip", "Web Comic", "manhwa", "manhua", "BD") or the work's original language (`ko`, `zh`: webtoons;
         * `en`, `fr`…: comics; `ja`: manga). A webtoon hint wins over the others; anything else is a manga (reader/SPEC.md
         * §1b, the backend's `core/formats.py`).
         */
        fun from(format: String): ComicFormat {
            val said = format.trim().lowercase()
            return when {
                said in STRIP_LANGUAGES || STRIP_WORDS.any { it in said } -> Webtoon
                said in COMIC_WORDS -> Comic
                else -> Manga
            }
        }

        /**
         * The pages' own shape: a chapter whose sized pages have a median height of at least [STRIP_RATIO] times their
         * width is a vertical strip, whatever the source said (decided again as more pages get their size). null while
         * no page has a size.
         */
        fun fromPages(pages: List<ReaderPage>): ComicFormat? {
            val ratios = pages.filter { it.hasSize }.map { it.height.toFloat() / it.width }.sorted()
            if (ratios.isEmpty()) return null
            val mid = ratios.size / 2
            val median = if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
            return if (median >= STRIP_RATIO) Webtoon else null
        }

        const val STRIP_RATIO = 2.2f
        private val STRIP_LANGUAGES = setOf("ko", "zh", "zh-hk", "zh-hans", "zh-hant", "ko-ro", "zh-ro")
        private val STRIP_WORDS = listOf("webtoon", "long strip", "longstrip", "web comic", "webcomic", "manhwa", "manhua", "vertical")
        private val COMIC_WORDS =
            setOf("comic", "comics", "western", "bd", "bande dessinée", "bande dessinee", "ltr", "en", "fr", "es", "it", "de", "pt", "nl")
    }
}

data class ReaderPage(
    val index: Int,
    /** Image URL: signed by vovoscan (internal source) or hosted by the source (read on the device). */
    val url: String,
    val width: Int,
    val height: Int,
) {
    /**
     * The size is known before the image arrives: our own pages (the API sends it). A source read on the device
     * usually doesn't say: the reader then shows a placeholder and takes the image's own size once it has loaded,
     * never this placeholder's shape (a webtoon strip squeezed into it would be unreadable).
     */
    val hasSize: Boolean get() = width > 0 && height > 0

    /** Width / height, to reserve space before the image arrives (no layout jump); the placeholder's without a size. */
    val aspectRatio: Float get() = if (hasSize) width.toFloat() / height else 0.7f
}

data class ReaderChapter(
    val sourceKey: String,
    val id: String,
    val titleId: String,
    val titleName: String,
    val number: Double,
    val name: String?,
    val mode: ReadingMode,
    val previousChapterId: String?,
    val nextChapterId: String?,
    val pages: List<ReaderPage>,
    /** Book: URL of the EPUB file (downloaded, then opened on the device). */
    val documentUrl: String?,
    /** The source's format (comics): decides the default mode with the user's per-format default. */
    val format: ComicFormat = if (mode == ReadingMode.LongStrip) ComicFormat.Webtoon else ComicFormat.Manga,
) {
    val label: String
        get() =
            if (mode == ReadingMode.Book) {
                name ?: t("reader.part", "n" to numberLabel())
            } else {
                chapterLabel(number)
            }

    private fun numberLabel() = numberLabel(number)
}

/** "12" or "12.5", never "12.0". */
fun numberLabel(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

/** "Chapter 12". */
fun chapterLabel(number: Double): String = t("reader.chapter", "n" to numberLabel(number))

/** A book section (in reading order): table-of-contents title and sanitized HTML. */
data class BookSection(
    val index: Int,
    val title: String,
)

data class Book(
    val sections: List<BookSection>,
)

/**
 * Offline-first reader: a unit already opened can be read again on the device. A book is downloaded once,
 * opened on the device (EPUB) and kept as sanitized sections, readable afterwards without a network.
 */
interface ReaderRepository {
    fun observe(
        sourceKey: String,
        chapterId: String,
    ): Flow<ReaderChapter?>

    suspend fun refresh(
        sourceKey: String,
        titleId: String,
        chapterId: String,
    ): AppResult<Unit>

    fun observeBook(
        sourceKey: String,
        chapterId: String,
    ): Flow<Book?>

    /** Downloads and opens the book if it is not already on the device. */
    suspend fun loadBook(
        sourceKey: String,
        chapterId: String,
    ): AppResult<Unit>

    suspend fun sectionHtml(
        sourceKey: String,
        chapterId: String,
        index: Int,
    ): String?

    suspend fun clearLocal()

    /** Erases what is kept on the device for one source (it left the user's list). */
    suspend fun clearSource(sourceKey: String)
}

/**
 * Where reading progress goes. A feature port: the app wires it to the library, without the reader knowing
 * about it. Non-suspending: the write runs in the app's scope.
 */
fun interface ReadingProgressSink {
    fun save(
        sourceKey: String,
        titleId: String,
        chapterId: String,
        pageIndex: Int,
    )
}
