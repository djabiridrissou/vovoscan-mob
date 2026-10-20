package com.vovoscan.feature.catalog.domain

import com.vovoscan.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/** The internal source, read through the vovoscan API; the others are read on the device. */
const val INTERNAL_SOURCE = "vovoscan"

/** Content type of a title. Only comics and books can be read today. */
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

/** Reading direction: right-to-left pages (manga), vertical strip (webtoon), text (book). */
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
    /** "98" or "98.5", never "98.0". */
    val label: String get() = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
}

data class TitleDetail(
    val summary: TitleSummary,
    val description: String,
    val authors: List<String>,
    val genres: List<String>,
    /** Newest to oldest. */
    val chapters: List<Chapter>,
)

/**
 * A source's catalog, offline first: screens read the local database (`observe…`), `refresh…` replaces it
 * with what the source says (the vovoscan API for the internal source, the device itself for the others).
 */
interface CatalogRepository {
    fun observeTitles(sourceKey: String): Flow<List<TitleSummary>>

    /** Reloads the first page; true if more pages remain. */
    suspend fun refreshTitles(sourceKey: String): AppResult<Boolean>

    suspend fun loadMoreTitles(sourceKey: String): AppResult<Boolean>

    /** Searches the source; offline, searches the titles already saved on the device. */
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
    /** Results taken from the local copy (network unavailable). */
    val offline: Boolean,
)
