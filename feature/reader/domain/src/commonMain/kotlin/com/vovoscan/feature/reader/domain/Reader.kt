package com.vovoscan.feature.reader.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Sens de lecture : pages de droite à gauche (manga) ou bande verticale continue (webtoon). */
enum class ReadingMode {
    PagedRightToLeft,
    Vertical,
    ;

    companion object {
        fun fromFormat(format: String): ReadingMode = if (format == "webtoon") Vertical else PagedRightToLeft
    }
}

data class ReaderPage(
    val index: Int,
    /** URL signée de l'image ; elle reste la même pendant une journée (cache d'images). */
    val url: String,
    val width: Int,
    val height: Int,
) {
    /** Largeur / hauteur, pour réserver la place avant que l'image n'arrive (pas de saut à l'affichage). */
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f
}

data class ReaderChapter(
    val id: String,
    val titleId: String,
    val titleName: String,
    val number: Double,
    val name: String?,
    val mode: ReadingMode,
    val previousChapterId: String?,
    val nextChapterId: String?,
    val pages: List<ReaderPage>,
) {
    val label: String get() = "Chapter " + if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}

/** Chapitre hors ligne d'abord : la copie locale tout de suite (déjà ouvert), puis relu auprès du serveur. */
interface ReaderRepository {
    fun observe(chapterId: String): Flow<ReaderChapter?>

    suspend fun refresh(chapterId: String): AppResult<Unit>

    suspend fun clearLocal()
}

/**
 * Où va la progression de lecture. Port de la feature : l'application le branche sur la bibliothèque,
 * sans que le lecteur la connaisse. Sans attente : l'écriture se fait dans la portée de l'application,
 * et survit donc à la fermeture du lecteur.
 */
fun interface ReadingProgressSink {
    fun save(
        titleId: String,
        chapterId: String,
        pageIndex: Int,
    )
}
