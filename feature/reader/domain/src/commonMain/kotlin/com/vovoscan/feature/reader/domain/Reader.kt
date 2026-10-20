package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** How a unit is read: right-to-left pages (manga), vertical strip (webtoon), text (book). */
enum class ReadingMode {
    PagedRightToLeft,
    Vertical,
    Book,
    ;

    companion object {
        fun from(
            kind: String,
            format: String,
        ): ReadingMode =
            when {
                kind == "book" -> Book
                format == "webtoon" -> Vertical
                else -> PagedRightToLeft
            }
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
) {
    val label: String
        get() =
            if (mode == ReadingMode.Book) {
                name ?: "Part ${numberLabel()}"
            } else {
                "Chapter " + numberLabel()
            }

    private fun numberLabel() = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}

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
