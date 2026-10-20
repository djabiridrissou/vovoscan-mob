package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Comment se lit l'unité : pages de droite à gauche (manga), bande verticale (webtoon), texte (livre). */
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
    /** URL de l'image : signée par vovoscan (source interne) ou chez la source (lue sur l'appareil). */
    val url: String,
    val width: Int,
    val height: Int,
) {
    /** Largeur / hauteur, pour réserver la place avant que l'image n'arrive (pas de saut à l'affichage). */
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
    /** Livre : adresse du fichier EPUB (téléchargé puis ouvert sur l'appareil). */
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

/** Une section d'un livre (dans l'ordre de lecture) : titre de la table des matières et HTML nettoyé. */
data class BookSection(
    val index: Int,
    val title: String,
)

data class Book(
    val sections: List<BookSection>,
)

/**
 * Lecteur hors ligne d'abord : l'unité déjà ouverte se relit sur l'appareil. Un livre est téléchargé une fois,
 * ouvert sur l'appareil (EPUB) et gardé en sections nettoyées, lisibles ensuite sans réseau.
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

    /** Télécharge et ouvre le livre s'il n'est pas déjà sur l'appareil. */
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
 * Où va la progression de lecture. Port de la feature : l'application le branche sur la bibliothèque, sans que le
 * lecteur la connaisse. Sans attente : l'écriture se fait dans la portée de l'application.
 */
fun interface ReadingProgressSink {
    fun save(
        sourceKey: String,
        titleId: String,
        chapterId: String,
        pageIndex: Int,
    )
}
