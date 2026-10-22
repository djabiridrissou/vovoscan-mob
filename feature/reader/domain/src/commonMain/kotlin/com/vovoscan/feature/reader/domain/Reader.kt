package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
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

        /** The format rule: a book is text, a webtoon a long strip, anything else right-to-left pages (manga). */
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

/** A comic's format, as the source declares it: what decides its default reading mode. */
enum class ComicFormat(
    val defaultMode: ReadingMode,
) {
    Manga(ReadingMode.PagedRightToLeft),
    Webtoon(ReadingMode.LongStrip),
    ;

    companion object {
        fun from(format: String): ComicFormat = if (format == "webtoon") Webtoon else Manga
    }
}

data class ReaderPage(
    val index: Int,
    /** Image URL: signed by vovoscan (internal source) or hosted by the source (read on the device). */
    val url: String,
    val width: Int,
    val height: Int,
) {
    /** Width / height, to reserve space before the image arrives (no layout jump). */
    val aspectRatio: Float get() = if (height > 0 && width > 0) width.toFloat() / height else 0.7f
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
                name ?: "Part ${numberLabel()}"
            } else {
                chapterLabel(number)
            }

    private fun numberLabel() = numberLabel(number)
}

/** "12" or "12.5", never "12.0". */
fun numberLabel(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

/** "Chapter 12". */
fun chapterLabel(number: Double): String = "Chapter " + numberLabel(number)

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
