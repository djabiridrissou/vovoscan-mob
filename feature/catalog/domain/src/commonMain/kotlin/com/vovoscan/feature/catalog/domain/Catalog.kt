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
    val synopsis: String,
    val authors: List<String>,
    val genres: List<String>,
    /** Newest to oldest. */
    val chapters: List<Chapter>,
)

/** A category of a source: its own id (sent to the source) and the name the reader sees. */
data class SourceCategory(
    val id: String,
    val name: String,
)

/** A title of a source's listing, with the page it came in on (the merged catalog goes page by page). */
data class ListedTitle(
    val title: TitleSummary,
    val page: Int,
)

/**
 * What the catalog shows, remembered on the device: every visible source (`sourceKeys` empty: "All", so a source
 * shown later joins in) or some of them, and a category by name (matched case-insensitively across sources), or none.
 */
data class CatalogFilter(
    val sourceKeys: List<String> = emptyList(),
    val categoryName: String? = null,
)

/** Where a title was left: its chapter and page, and when (ms). */
data class ResumePoint(
    val sourceKey: String,
    val titleId: String,
    val chapterId: String,
    val page: Int,
    val readAt: Long,
)

/**
 * Port: the titles this account has started, newest first, one point per title (the device's own reading and the
 * library's progress, synced from other devices). Wired by the app; the catalog does not know the reader or the library.
 */
fun interface ReadingResume {
    fun observe(): Flow<List<ResumePoint>>
}

object ResumePoints {
    /** One point per title, the most recent of every list wins; newest first. */
    fun merge(vararg lists: List<ResumePoint>): List<ResumePoint> =
        lists
            .asList()
            .flatten()
            .groupBy { it.sourceKey to it.titleId }
            .values
            .map { points -> points.maxBy { it.readAt } }
            .sortedByDescending { it.readAt }
}

/**
 * Each source's catalog, offline first: screens read the local database (`observe…`), `refresh…` replaces it
 * with what the source says (the vovoscan API for the internal source, the device itself for the others). A source
 * has one listing per category (`categoryId`), and one for everything (`null`).
 */
interface CatalogRepository {
    fun observeListing(
        sourceKey: String,
        categoryId: String? = null,
    ): Flow<List<ListedTitle>>

    /** Reloads the first page; true if more pages remain. */
    suspend fun refreshListing(
        sourceKey: String,
        categoryId: String? = null,
    ): AppResult<Boolean>

    /** The next page, when there is one; true if more pages remain. */
    suspend fun loadMoreListing(
        sourceKey: String,
        categoryId: String? = null,
    ): AppResult<Boolean>

    /** True while the saved listing has pages left to load (known before any network answer). */
    suspend fun hasMore(
        sourceKey: String,
        categoryId: String? = null,
    ): Boolean

    /** Searches the source; offline (or the source isn't answering), searches the titles already saved on the device. */
    suspend fun search(
        sourceKey: String,
        query: String,
        categoryId: String? = null,
    ): SearchResult

    /** The source's categories as last read (none until then, or when it has none). */
    fun observeCategories(sourceKey: String): Flow<List<SourceCategory>>

    suspend fun refreshCategories(sourceKey: String): AppResult<Unit>

    fun observeFilter(): Flow<CatalogFilter>

    suspend fun saveFilter(filter: CatalogFilter)

    /** Title pages list chapters oldest first (chapter 1 on top) unless the reader chose newest first. */
    fun observeNewestFirst(): Flow<Boolean>

    suspend fun saveNewestFirst(newestFirst: Boolean)

    fun observeTitle(
        sourceKey: String,
        id: String,
    ): Flow<TitleDetail?>

    suspend fun refreshTitle(
        sourceKey: String,
        id: String,
    ): AppResult<Unit>

    suspend fun clearLocal()

    /** Erases what is kept on the device for one source (it left the user's list). */
    suspend fun clearSource(sourceKey: String)
}

data class SearchResult(
    val titles: List<TitleSummary>,
    /** Results taken from the local copy (network unavailable, or the source isn't answering). */
    val offline: Boolean,
    /** The source that didn't answer (its name), when that is why the local copy was searched. */
    val unavailableSource: String? = null,
)
