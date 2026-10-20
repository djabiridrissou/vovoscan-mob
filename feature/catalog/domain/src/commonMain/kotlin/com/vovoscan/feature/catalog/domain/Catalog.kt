package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** La source interne, lue par l'API vovoscan ; les autres sont lues sur l'appareil. */
const val INTERNAL_SOURCE = "vovoscan"

/** Type de contenu d'un titre. Seuls les comics et les livres sont lus aujourd'hui. */
enum class ContentKind {
    Comic,
    Book,
    Other,
    ;

    companion object {
        fun fromApi(value: String): ContentKind =
            when (value) {
                "comic" -> Comic
                "book" -> Book
                else -> Other
            }
    }
}

/** Sens de lecture : pages de droite à gauche (manga), bande verticale (webtoon), texte (livre). */
enum class ReadingFormat {
    Manga,
    Webtoon,
    Text,
    ;

    companion object {
        fun fromApi(value: String): ReadingFormat =
            when (value) {
                "webtoon" -> Webtoon
                "text" -> Text
                else -> Manga
            }
    }
}

data class TitleSummary(
    val sourceKey: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
    val kind: ContentKind,
    val format: ReadingFormat,
    val status: String,
)

data class Chapter(
    val id: String,
    val number: Double,
    val name: String?,
    val pageCount: Int,
    val publishedAt: String?,
) {
    /** « 98 » ou « 98.5 », jamais « 98.0 ». */
    val label: String get() = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}

data class TitleDetail(
    val summary: TitleSummary,
    val description: String,
    val authors: List<String>,
    val genres: List<String>,
    /** Du plus récent au plus ancien. */
    val chapters: List<Chapter>,
)

/**
 * Catalogue d'une source, hors ligne d'abord : les écrans lisent la base locale (`observe…`), les `refresh…` la
 * remplacent par ce que dit la source — l'API vovoscan pour la source interne, l'appareil lui-même pour les autres.
 */
interface CatalogRepository {
    fun observeTitles(sourceKey: String): Flow<List<TitleSummary>>

    /** Relit la première page ; vrai s'il en reste d'autres. */
    suspend fun refreshTitles(sourceKey: String): AppResult<Boolean>

    suspend fun loadMoreTitles(sourceKey: String): AppResult<Boolean>

    /** Recherche chez la source ; hors ligne, dans les titres déjà gardés sur l'appareil. */
    suspend fun search(
        sourceKey: String,
        query: String,
    ): SearchResult

    fun observeTitle(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?>

    suspend fun refreshTitle(
        sourceKey: String,
        id: String,
    ): AppResult<Unit>

    suspend fun clearLocal()
}

data class SearchResult(
    val titles: List<TitleSummary>,
    /** Résultats tirés de la copie locale (réseau indisponible). */
    val offline: Boolean,
)
