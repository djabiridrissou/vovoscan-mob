package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.i18n.t
import com.vovoscan.feature.library.domain.LibraryRepository
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
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<FollowUiState> =
        combine(repository.observe(), error) { entries, error ->
            val entry = entries.firstOrNull { it.sourceKey == sourceKey && it.titleRef == titleId }
            FollowUiState(
                followed = entry != null,
                resumeChapterId = entry?.lastChapterRef,
                resumePage = entry?.lastPage ?: 0,
                favorite = entry?.favorite == true,
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FollowUiState())

    /**
     * The heart: on at once (it follows the title too), off keeps the title followed. Offline it waits in the queue; a
     * refusal puts it back and says so.
     */
    fun toggleFavorite(
        name: String,
        coverUrl: String?,
    ) {
        val on = !state.value.favorite
        error.value = null
        viewModelScope.launch {
            val result = repository.setFavorite(TitleRef(sourceKey, titleId, name, coverUrl), on)
            if (result is AppResult.Err) error.value = t("marks.favoriteError")
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
