package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogRepository
import com.vovoscan.feature.catalog.domain.ContentKind
import com.vovoscan.feature.catalog.domain.INTERNAL_SOURCE
import com.vovoscan.feature.catalog.domain.ReadingFormat
import com.vovoscan.feature.catalog.domain.TitleDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChapterRow(
    val id: String,
    /** "Chapter 98" or "Chapter 98 · The last gate". */
    val label: String,
    /** "19 pages". */
    val meta: String,
)

data class TitleDetailUiState(
    val sourceKey: String = "",
    val isBook: Boolean = false,
    val name: String = "",
    val coverUrl: String? = null,
    /** Not "description": on iOS that name is `toString()` (NSObject) and would be renamed. */
    val synopsis: String = "",
    /** "WEBTOON ✦ Ongoing ✦ A. Author". */
    val meta: String = "",
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    /** Title never opened and no network: nothing to show. */
    val unavailable: Boolean = false,
    val error: String? = null,
) {
    /** A comic of our own catalog: each chapter can become a voiced episode (not books, not rule-based sources). */
    val offersEpisodes: Boolean get() = !isBook && sourceKey == INTERNAL_SOURCE && chapters.isNotEmpty()

    /** "Chapter 98 · The last gate" for a chapter of this title; "" when unknown. */
    fun chapterLabel(chapterId: String): String = chapters.firstOrNull { it.id == chapterId }?.label.orEmpty()

    /** The chapter after [chapterId] in reading order (the list runs newest to oldest); null for the latest. */
    fun nextChapterId(chapterId: String): String? {
        val index = chapters.indexOfFirst { it.id == chapterId }
        return if (index > 0) chapters[index - 1].id else null
    }
}

/** Title page: name, description, chapters. Readable offline once it has been opened. */
class TitleDetailViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val repository: CatalogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TitleDetailUiState())
    val state: StateFlow<TitleDetailUiState> = mutableState.asStateFlow()
    private var known = false

    init {
        repository
            .observeTitle(sourceKey, titleId)
            .onEach { detail ->
                if (detail != null) {
                    known = true
                    mutableState.update { it.show(detail) }
                }
            }.launchIn(viewModelScope)
        refresh()
    }

    fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refreshTitle(sourceKey, titleId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh, unavailable = false)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            unavailable = !known,
                            error = if (result.error == AppError.Network && known) null else result.error.userMessage(),
                        )
                }
            }
        }
    }

    private fun TitleDetailUiState.show(detail: TitleDetail) =
        copy(
            sourceKey = detail.summary.sourceKey,
            isBook = detail.summary.kind == ContentKind.Book,
            name = detail.summary.name,
            coverUrl = detail.summary.coverUrl,
            synopsis = detail.description,
            meta =
                listOfNotNull(
                    when {
                        detail.summary.kind == ContentKind.Book -> "BOOK"
                        detail.summary.format == ReadingFormat.Webtoon -> "WEBTOON"
                        else -> "MANGA"
                    },
                    detail.summary.status.replaceFirstChar { it.uppercase() },
                    detail.authors.joinToString(", ").ifBlank { null },
                ).joinToString(" ✦ "),
            genres = detail.genres,
            chapters =
                detail.chapters.map { chapter ->
                    if (detail.summary.kind == ContentKind.Book) {
                        ChapterRow(id = chapter.id, label = chapter.name ?: "Part ${chapter.label}", meta = "Book")
                    } else {
                        ChapterRow(
                            id = chapter.id,
                            label = listOfNotNull("Chapter ${chapter.label}", chapter.name).joinToString(" · "),
                            meta =
                                if (chapter.pageCount ==
                                    0
                                ) {
                                    ""
                                } else if (chapter.pageCount == 1) {
                                    "1 page"
                                } else {
                                    "${chapter.pageCount} pages"
                                },
                        )
                    }
                },
        )
}
