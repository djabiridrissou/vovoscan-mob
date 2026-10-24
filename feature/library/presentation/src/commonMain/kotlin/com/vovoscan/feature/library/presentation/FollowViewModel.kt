package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.LibrarySeries
import com.vovoscan.feature.library.domain.TitleRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FollowUiState(
    val followed: Boolean = false,
    /** Chapter and page to resume reading from, if the title has been read before. */
    val resumeChapterId: String? = null,
    val resumePage: Int = 0,
    /** In the favourites (the heart, red when on). */
    val favorite: Boolean = false,
    /** "Could not update your favourites.": the heart was put back as it was. */
    val error: String? = null,
) {
    val label: String get() = t(if (followed) "library.inLibrary" else "library.add")

    /** "Favourite" when on, "Add to favourites" otherwise: the title page's heart button. */
    val favoriteLabel: String get() = t(if (favorite) "marks.favorite" else "marks.favoriteAdd")

    /** What a tap does, for screen readers: "Remove from favourites" / "Add to favourites". */
    val favoriteAction: String get() = t(if (favorite) "marks.favoriteRemove" else "marks.favoriteAdd")

    /** "Continue · page 5" ("section" for a book), null if there is nothing to resume. */
    fun resumeLabel(book: Boolean): String? =
        resumeChapterId?.let {
            t(
                if (book) "library.continueSection" else "library.continuePage",
                "n" to resumePage + 1,
            )
        }
}

/** "Follow" button and reading resume on a title page. The app assembles it next to the title page. */
class FollowViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val repository: LibraryRepository,
    private val series: LibrarySeries = LibrarySeries.None,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    /** The library's entries of this title's series (this title's own entry only, when in none). */
    private var members: List<LibraryEntry> = emptyList()

    val state: StateFlow<FollowUiState> =
        combine(repository.observe(), series.observe(), error) { entries, seriesOf, error ->
            val entry = entries.firstOrNull { it.sourceKey == sourceKey && it.titleRef == titleId }
            val id = seriesOf["$sourceKey/$titleId"]
            members = if (id == null) listOfNotNull(entry) else entries.filter { seriesOf["${it.sourceKey}/${it.titleRef}"] == id }
            FollowUiState(
                followed = entry != null,
                resumeChapterId = entry?.lastChapterRef,
                resumePage = entry?.lastPage ?: 0,
                // The heart is the series', as on the library's row and the catalog's card: favourited at another
                // source, it is red here too.
                favorite = members.any { it.favorite },
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FollowUiState())

    /**
     * The heart: on at once (it follows the title too), off keeps the title followed and takes the heart from every
     * source of the series that has it. Offline it waits in the queue; a refusal puts it back and says so.
     */
    fun toggleFavorite(
        name: String,
        coverUrl: String?,
    ) {
        val on = !state.value.favorite
        val targets =
            if (on) {
                listOf(TitleRef(sourceKey, titleId, name, coverUrl))
            } else {
                members.filter { it.favorite }.map { TitleRef(it.sourceKey, it.titleRef, it.titleName, it.coverUrl) }
            }
        error.value = null
        viewModelScope.launch {
            val failed = targets.map { repository.setFavorite(it, on) }.any { it is AppResult.Err }
            if (failed) error.value = t("marks.favoriteError")
        }
    }

    fun toggle(
        name: String,
        coverUrl: String?,
    ) {
        viewModelScope.launch {
            if (state.value.followed) {
                repository.unfollow(
                    sourceKey,
                    titleId,
                )
            } else {
                repository.follow(TitleRef(sourceKey, titleId, name, coverUrl))
            }
        }
    }
}
