package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.TitleRef
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FollowUiState(
    val followed: Boolean = false,
    /** Chapitre et page où reprendre la lecture, si le titre a déjà été lu. */
    val resumeChapterId: String? = null,
    val resumePage: Int = 0,
) {
    val label: String get() = if (followed) "In your library ✓" else "Add to library →"

    /** « Continue · page 5 » (« section » pour un livre), null si rien à reprendre. */
    fun resumeLabel(book: Boolean): String? = resumeChapterId?.let { "Continue · ${if (book) "section" else "page"} ${resumePage + 1}" }
}

/** Bouton « suivre » et reprise de lecture sur la fiche d'un titre. L'application l'assemble à côté de la fiche. */
class FollowViewModel(
    private val sourceKey: String,
    private val titleId: String,
    private val repository: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<FollowUiState> =
        repository
            .observe()
            .map { entries ->
                val entry = entries.firstOrNull { it.sourceKey == sourceKey && it.titleRef == titleId }
                FollowUiState(followed = entry != null, resumeChapterId = entry?.lastChapterRef, resumePage = entry?.lastPage ?: 0)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, FollowUiState())

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
