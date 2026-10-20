package com.vovoscan.feature.reader.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vovoscan.core.common.AppError
import com.vovoscan.core.common.AppResult
import com.vovoscan.core.common.Freshness
import com.vovoscan.core.common.userMessage
import com.vovoscan.feature.reader.domain.ReaderChapter
import com.vovoscan.feature.reader.domain.ReaderPage
import com.vovoscan.feature.reader.domain.ReaderRepository
import com.vovoscan.feature.reader.domain.ReadingMode
import com.vovoscan.feature.reader.domain.ReadingProgressSink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val titleName: String = "",
    /** « Chapter 2 » ou « Chapter 2 · The last gate ». */
    val chapterLabel: String = "",
    val mode: ReadingMode = ReadingMode.Vertical,
    val pages: List<ReaderPage> = emptyList(),
    /** Page à afficher en ouvrant le chapitre (reprise de lecture), puis page courante. */
    val currentIndex: Int = 0,
    val previousChapterId: String? = null,
    val nextChapterId: String? = null,
    /** Barres du haut et du bas : un toucher au centre les masque ou les montre. */
    val chromeVisible: Boolean = true,
    val freshness: Freshness = Freshness.Refreshing,
    /** Chapitre jamais ouvert et pas de réseau : rien à montrer. */
    val unavailable: Boolean = false,
    val error: String? = null,
) {
    /** « 5 / 19 ». */
    val positionLabel: String get() = if (pages.isEmpty()) "" else "${currentIndex + 1} / ${pages.size}"

    val atEnd: Boolean get() = pages.isNotEmpty() && currentIndex >= pages.lastIndex

    /** Bouton de fin de chapitre. */
    val nextLabel: String? get() = nextChapterId?.let { "Next chapter →" }

    val rightToLeft: Boolean get() = mode == ReadingMode.PagedRightToLeft
}

sealed interface ReaderIntent {
    /** Page la plus visible à l'écran (défilement ou balayage). */
    data class PageVisible(
        val index: Int,
    ) : ReaderIntent

    data object ToggleChrome : ReaderIntent

    data object Retry : ReaderIntent
}

/**
 * Lecteur d'un chapitre. Toute la logique est ici, partagée par Compose et SwiftUI : sens de lecture, page
 * courante, reprise, enregistrement de la progression (après une courte pause sur une page, pas à chaque image).
 */
class ReaderViewModel(
    private val chapterId: String,
    startPage: Int,
    private val repository: ReaderRepository,
    private val progress: ReadingProgressSink,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState(currentIndex = startPage.coerceAtLeast(0)))
    val state: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private var titleId: String? = null
    private var saveJob: Job? = null

    init {
        repository
            .observe(chapterId)
            .onEach { chapter -> if (chapter != null) show(chapter) }
            .launchIn(viewModelScope)
        refresh()
    }

    fun onIntent(intent: ReaderIntent) {
        when (intent) {
            is ReaderIntent.PageVisible -> pageVisible(intent.index)
            ReaderIntent.ToggleChrome -> mutableState.update { it.copy(chromeVisible = !it.chromeVisible) }
            ReaderIntent.Retry -> refresh()
        }
    }

    private fun show(chapter: ReaderChapter) {
        titleId = chapter.titleId
        mutableState.update {
            it.copy(
                titleName = chapter.titleName,
                chapterLabel = listOfNotNull(chapter.label, chapter.name).joinToString(" · "),
                mode = chapter.mode,
                pages = chapter.pages,
                currentIndex = it.currentIndex.coerceIn(0, (chapter.pages.size - 1).coerceAtLeast(0)),
                previousChapterId = chapter.previousChapterId,
                nextChapterId = chapter.nextChapterId,
                unavailable = false,
            )
        }
    }

    private fun refresh() {
        mutableState.update { it.copy(freshness = Freshness.Refreshing, error = null) }
        viewModelScope.launch {
            val result = repository.refresh(chapterId)
            mutableState.update {
                when (result) {
                    is AppResult.Ok -> it.copy(freshness = Freshness.Fresh)
                    is AppResult.Err ->
                        it.copy(
                            freshness = Freshness.Offline,
                            unavailable = it.pages.isEmpty(),
                            error = if (result.error == AppError.Network && it.pages.isNotEmpty()) null else result.error.userMessage(),
                        )
                }
            }
        }
    }

    private fun pageVisible(index: Int) {
        val current = state.value
        if (index == current.currentIndex || index !in current.pages.indices) return
        mutableState.update { it.copy(currentIndex = index) }
        val title = titleId ?: return
        saveJob?.cancel()
        saveJob =
            viewModelScope.launch {
                delay(SAVE_DELAY_MS)
                progress.save(title, chapterId, index)
            }
    }

    override fun onCleared() {
        // En quittant le lecteur, la dernière page vue est enregistrée sans attendre la fin de la pause.
        val title = titleId
        if (saveJob?.isActive == true && title != null) {
            saveJob?.cancel()
            progress.save(title, chapterId, state.value.currentIndex)
        }
        super.onCleared()
    }

    private companion object {
        const val SAVE_DELAY_MS = 800L
    }
}
