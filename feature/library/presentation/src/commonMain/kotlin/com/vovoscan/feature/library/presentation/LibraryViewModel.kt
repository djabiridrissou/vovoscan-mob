package com.vovoscan.feature.library.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.i18n.t
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.library.domain.LibraryEntry
import com.vovoscan.feature.library.domain.LibraryRepository
import com.vovoscan.feature.library.domain.NewChapters
import com.vovoscan.feature.library.domain.TitleRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryRow(
    val sourceKey: String,
    val titleId: String,
    val name: String,
    val coverUrl: String?,
    /** "Page 12" or "Not started". */
    val progress: String,
    /** Changed offline, not sent yet: small badge. */
    val pendingSync: Boolean,
    /** Its source is no longer in the user's list: greyed out (readable again if the source comes back). */
    val sourceRemoved: Boolean = false,
    /** "Source removed", or null. */
    val sourceNotice: String? = null,
    /** In the favourites: listed in the Favourites section, the heart red. */
    val favorite: Boolean = false,
    /** When the heart was given (ms): the Favourites section's order. */
    val favoritedAt: Long? = null,
    /** "+3 new": chapters after the last one read (yellow tilted badge on the cover); null when none or unknown. */
    val newBadge: String? = null,
) {
    val key: String get() = "$sourceKey/$titleId"

    /** What the row's heart does, for screen readers: "Add to favourites" / "Remove from favourites". */
    val favoriteAction: String get() = t(if (favorite) "marks.favoriteRemove" else "marks.favoriteAdd")
}

data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val freshness: Freshness = Freshness.Refreshing,
    val error: String? = null,
) {
    val empty: Boolean get() = rows.isEmpty() && freshness != Freshness.Refreshing
    val emptyMessage: String get() = t("library.empty")
    val pendingNotice: String?
        get() =
            rows.count { it.pendingSync }.takeIf { it > 0 }?.let { t("library.pending", "count" to it) }

    /** The library as it shows: Favourites first (most recently favourited first), then Following (the rest). */
    val sections: List<LibrarySection>
        get() =
            listOfNotNull(
                rows
                    .filter {
                        it.favorite
                    }.sortedByDescending { it.favoritedAt ?: 0L }
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        LibrarySection(
                            t("library.favorites"),
                            it,
                        )
                    },
                rows
                    .filterNot { it.favorite }
                    .takeIf { it.isNotEmpty() }
                    ?.let { LibrarySection(t("library.following"), it) },
            )
}

/** "Favourites" or "Following", with its rows. */
data class LibrarySection(
    val title: String,
    val rows: List<LibraryRow>,
)

sealed interface LibraryIntent {
    data object Sync : LibraryIntent

    data class Unfollow(
        val sourceKey: String,
        val titleId: String,
    ) : LibraryIntent

    /** The heart on a row: on or off (the title stays followed). */
    data class ToggleFavorite(
        val sourceKey: String,
        val titleId: String,
    ) : LibraryIntent

    /** Every source of the user's list (visible or not), sent by the app from the sources feature. */
    data class SourcesChanged(
        val keys: List<String>,
    ) : LibraryIntent
}

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val newChapters: NewChapters = NewChapters.Unknown,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()
    private var entries: List<LibraryEntry> = emptyList()

    /** Sources of the user's list; null until the app sends them (nothing is flagged before). */
    private var sourceKeys: Set<String>? = null

    /** New chapters of the titles of sources read on the device, counted from the device's copy, by title and last chapter. */
    private val counted = mutableMapOf<Pair<String, String?>, Int?>()

    init {
        repository
            .observe()
            .onEach { list ->
                entries = list
                render()
                countNewChapters(list)
            }.launchIn(viewModelScope)
        sync()
    }

    private fun render() = mutableState.update { it.copy(rows = entries.map(::row)) }

    // The server counts our own catalog's chapters; a source read on the device is counted from the device's copy.
    private fun countNewChapters(list: List<LibraryEntry>) {
        val missing = list.filter { it.chapterCount == null && "${it.sourceKey}/${it.titleRef}" to it.lastChapterRef !in counted }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            missing.forEach {
                counted["${it.sourceKey}/${it.titleRef}" to it.lastChapterRef] =
                    newChapters.count(it.sourceKey, it.titleRef, it.lastChapterRef)
            }
            render()
        }
    }

    fun onIntent(intent: LibraryIntent) {
        when (intent) {
            LibraryIntent.Sync -> sync()
            is LibraryIntent.Unfollow -> viewModelScope.launch { repository.unfollow(intent.sourceKey, intent.titleId) }
            is LibraryIntent.ToggleFavorite -> toggleFavorite(intent.sourceKey, intent.titleId)
            is LibraryIntent.SourcesChanged -> {
                // An empty list means the sources are not loaded yet, not that they were all removed.
                sourceKeys = intent.keys.toSet().takeIf { it.isNotEmpty() }
                render()
            }
        }
    }

    private fun sync() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.sync()
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            error = if (result.error == AppError.Network) null else result.error.userMessage(),
                        )
                }
            }
        }
    }

    private fun toggleFavorite(
        sourceKey: String,
        titleId: String,
    ) {
        val entry = entries.firstOrNull { it.sourceKey == sourceKey && it.titleRef == titleId } ?: return
        viewModelScope.launch {
            val result = repository.setFavorite(TitleRef(sourceKey, titleId, entry.titleName, entry.coverUrl), !entry.favorite)
            if (result is AppResult.Err) mutableState.update { it.copy(error = t("marks.favoriteError")) }
        }
    }

    private fun row(entry: LibraryEntry): LibraryRow {
        val removed = sourceKeys?.let { entry.sourceKey !in it } ?: false
        val fresh = entry.newChapters ?: counted["${entry.sourceKey}/${entry.titleRef}" to entry.lastChapterRef]
        return LibraryRow(
            sourceKey = entry.sourceKey,
            titleId = entry.titleRef,
            name = entry.titleName,
            coverUrl = entry.coverUrl,
            progress = if (entry.lastChapterRef == null) t("library.notStarted") else t("library.page", "n" to entry.lastPage + 1),
            pendingSync = entry.pendingSync,
            sourceRemoved = removed,
            sourceNotice = if (removed) t("library.sourceRemoved") else null,
            favorite = entry.favorite,
            favoritedAt = entry.favoritedAt,
            newBadge = fresh?.takeIf { it > 0 }?.let { t("library.newChapters", "count" to it) },
        )
    }
}
