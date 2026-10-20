package com.vovoscan.feature.catalog.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.catalog.domain.CatalogRepository
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
    /** « Chapter 98 » ou « Chapter 98 · The last gate ». */
    val label: String,
    /** « 19 pages ». */
    val meta: String,
)

data class TitleDetailUiState(
    val name: String = "",
    val coverUrl: String? = null,
    /** Pas « description » : sur iOS, ce nom est celui de `toString()` (NSObject) et serait renommé. */
    val synopsis: String = "",
    /** « WEBTOON ✦ Ongoing ✦ A. Author ». */
    val meta: String = "",
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    /** Titre jamais ouvert et réseau absent : rien à montrer. */
    val unavailable: Boolean = false,
    val error: String? = null,
)

/** Fiche d'un titre : nom, description, chapitres. Lisible hors ligne dès qu'elle a été ouverte une fois. */
class TitleDetailViewModel(
    private val titleId: String,
    private val repository: CatalogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TitleDetailUiState())
    val state: StateFlow<TitleDetailUiState> = mutableState.asStateFlow()
    private var known = false

    init {
        repository
            .observeTitle(titleId)
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
            val result = repository.refreshTitle(titleId)
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
            name = detail.summary.name,
            coverUrl = detail.summary.coverUrl,
            synopsis = detail.description,
            meta =
                listOfNotNull(
                    if (detail.summary.format == ReadingFormat.Webtoon) "WEBTOON" else "MANGA",
                    detail.summary.status.replaceFirstChar { it.uppercase() },
                    detail.authors.joinToString(", ").ifBlank { null },
                ).joinToString(" ✦ "),
            genres = detail.genres,
            chapters =
                detail.chapters.map { chapter ->
                    ChapterRow(
                        id = chapter.id,
                        label = listOfNotNull("Chapter ${chapter.label}", chapter.name).joinToString(" · "),
                        meta = if (chapter.pageCount == 1) "1 page" else "${chapter.pageCount} pages",
                    )
                },
        )
}
