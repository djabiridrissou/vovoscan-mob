package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** Sens de lecture : pages de droite à gauche (manga) ou bande verticale infinie (webtoon). */
enum class ReadingFormat {
    Manga,
    Webtoon,
    ;

    companion object {
        fun fromApi(value: String): ReadingFormat = if (value == "webtoon") Webtoon else Manga
    }
}

data class TitleSummary(
    val id: String,
    val name: String,
    val coverUrl: String?,
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
 * Catalogue hors ligne d'abord : les écrans lisent la base locale (`observe…`), les `refresh…` la remplacent par
 * ce que dit le serveur. Sans réseau, la copie locale reste affichée.
 */
interface CatalogRepository {
    fun observeTitles(): Flow<List<TitleSummary>>

    /** Relit la première page ; vrai s'il en reste d'autres. */
    suspend fun refreshTitles(): AppResult<Boolean>

    suspend fun loadMoreTitles(): AppResult<Boolean>

    /** Recherche sur le serveur ; hors ligne, dans les titres déjà gardés sur l'appareil. */
    suspend fun search(query: String): SearchResult

    fun observeTitle(id: String): Flow<TitleDetail?>

    suspend fun refreshTitle(id: String): AppResult<Unit>

    suspend fun clearLocal()
}

data class SearchResult(
    val titles: List<TitleSummary>,
    /** Résultats tirés de la copie locale (réseau indisponible). */
    val offline: Boolean,
)
